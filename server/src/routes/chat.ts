import { and, desc, eq, inArray } from 'drizzle-orm';
import { Hono } from 'hono';
import { z } from 'zod';
import { db } from '../db/client.js';
import { chats, orders, shops, users } from '../db/schema.js';
import { env } from '../env.js';
import { type AuthVars, currentUser, requireAuth, requireRole } from '../lib/auth.js';
import { ApiError, notFound, readJson } from '../lib/http.js';
import { rateLimit } from '../lib/rateLimit.js';
import { issueAccessToken, sendDirectMessage } from '../nexconn/client.js';
import { ACTIVE_STATUSES } from '../domain/orderState.js';

export const chatRoutes = new Hono<{ Variables: AuthVars }>();

/** Public client configuration. Only the App Key is exposed; the App Secret never leaves the server. */
chatRoutes.get('/config', (c) =>
  c.json({ nexconnAppKey: env().NEXCONN_APP_KEY, areaCode: 'SG', idPrefix: env().NEXCONN_ID_PREFIX }),
);

/**
 * Returns the caller's Nexconn access token, issuing (and registering the user) on first use.
 * `refresh: true` forces a new token, used by the app when a connect fails with an invalid token.
 */
chatRoutes.post('/chat/token', requireAuth, async (c) => {
  const user = currentUser(c);
  const body = await readJson(c, z.object({ refresh: z.boolean().optional() }).default({}));
  if (user.nexconnToken && !body.refresh) {
    return c.json({ userId: user.nexconnId, token: user.nexconnToken, appKey: env().NEXCONN_APP_KEY });
  }
  await rateLimit(`chat-token:${user.id}`, 20, 3600, 'Too many reconnects. Wait a few minutes and try again.');
  let displayName = user.name;
  let avatar: string | null = null;
  if (user.role === 'shopkeeper') {
    const [shop] = await db().select().from(shops).where(eq(shops.ownerId, user.id)).limit(1);
    if (shop) {
      displayName = shop.name;
      avatar = shop.photoUrl;
    }
  }
  let token: string;
  try {
    token = await issueAccessToken(user.nexconnId, displayName, avatar);
  } catch (err) {
    console.error('token issue failed', err);
    throw new ApiError(502, 'chat_unavailable', 'Chat is unreachable right now. Try again shortly.');
  }
  await db().update(users).set({ nexconnToken: token }).where(eq(users.id, user.id));
  return c.json({ userId: user.nexconnId, token, appKey: env().NEXCONN_APP_KEY });
});

/** Customer opens (or reopens) the direct channel with a shop. */
chatRoutes.post('/chats', requireAuth, async (c) => {
  const user = requireRole(c, 'customer');
  const { shopId } = await readJson(c, z.object({ shopId: z.string().uuid() }));
  const [row] = await db()
    .select({ shop: shops, owner: users })
    .from(shops)
    .innerJoin(users, eq(users.id, shops.ownerId))
    .where(eq(shops.id, shopId))
    .limit(1);
  if (!row) throw notFound('Shop not found.');
  await db().insert(chats).values({ shopId, customerId: user.id }).onConflictDoNothing();
  return c.json({
    peer: {
      nexconnId: row.owner.nexconnId,
      kind: 'shop',
      name: row.shop.name,
      photoUrl: row.shop.photoUrl,
      shopId: row.shop.id,
      customerId: null,
    },
  });
});

const LocationBody = z.object({
  peerNexconnId: z.string().min(3).max(64),
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  place: z.string().trim().min(1).max(200),
  // Base64 JPEG preview required by RC:LBSMsg; kept small to stay inside the message size limit.
  thumbnail: z.string().regex(/^[A-Za-z0-9+/=]+$/).max(12_000),
});

/** True when the two users are a customer and the owner of a shop they have a chat with. */
async function hasChatRelationship(me: { id: string; role: string }, peerNexconnId: string): Promise<boolean> {
  const [peer] = await db().select().from(users).where(eq(users.nexconnId, peerNexconnId)).limit(1);
  if (!peer) return false;
  const [customerId, ownerId] = me.role === 'customer' ? [me.id, peer.id] : [peer.id, me.id];
  const [row] = await db()
    .select({ id: chats.id })
    .from(chats)
    .innerJoin(shops, eq(shops.id, chats.shopId))
    .where(and(eq(chats.customerId, customerId), eq(shops.ownerId, ownerId)))
    .limit(1);
  return Boolean(row);
}

/**
 * Shares a location as a Nexconn location message (RC:LBSMsg). The Android SDK 26.4.2 rejects
 * client-side LocationMessage sends with an undocumented error (31610), so the server sends the same
 * built-in message type through the Platform Chat API as the user, echoed to their own devices.
 */
chatRoutes.post('/chats/location', requireAuth, async (c) => {
  const user = currentUser(c);
  const body = await readJson(c, LocationBody);
  if (!(await hasChatRelationship(user, body.peerNexconnId))) throw notFound('Chat not found.');
  await rateLimit(`location:${user.id}`, 30, 3600, 'You have shared your location many times this hour. Try again later.');
  try {
    const messageId = await sendDirectMessage({
      fromUserId: user.nexconnId,
      toUserId: body.peerNexconnId,
      messageType: 'RC:LBSMsg',
      content: { content: body.thumbnail, latitude: body.lat, longitude: body.lng, poi: body.place },
      pushContent: 'Shared a location',
    });
    return c.json({ messageId }, 201);
  } catch (err) {
    console.error('location send failed', err);
    throw new ApiError(502, 'chat_unavailable', 'Could not share the location right now. Try again.');
  }
});

/**
 * Everyone the caller has a chat relationship with, keyed by Nexconn user id, so the app can show
 * names and photos in the conversation list (Nexconn stores no app profiles).
 */
chatRoutes.get('/chats', requireAuth, async (c) => {
  const user = currentUser(c);
  if (user.role === 'customer') {
    const rows = await db()
      .select({ shop: shops, owner: users, chatCreatedAt: chats.createdAt })
      .from(chats)
      .innerJoin(shops, eq(shops.id, chats.shopId))
      .innerJoin(users, eq(users.id, shops.ownerId))
      .where(eq(chats.customerId, user.id))
      .orderBy(desc(chats.createdAt));
    return c.json({
      peers: rows.map((r) => ({
        nexconnId: r.owner.nexconnId,
        kind: 'shop',
        name: r.shop.name,
        photoUrl: r.shop.photoUrl,
        shopId: r.shop.id,
        customerId: null,
        activeOrders: 0,
      })),
    });
  }
  const [shop] = await db().select().from(shops).where(eq(shops.ownerId, user.id)).limit(1);
  if (!shop) return c.json({ peers: [] });
  const rows = await db()
    .select({ customer: users })
    .from(chats)
    .innerJoin(users, eq(users.id, chats.customerId))
    .where(eq(chats.shopId, shop.id))
    .orderBy(desc(chats.createdAt));
  const active = rows.length
    ? await db()
        .select({ customerId: orders.customerId })
        .from(orders)
        .where(
          and(
            eq(orders.shopId, shop.id),
            inArray(orders.status, [...ACTIVE_STATUSES]),
            inArray(
              orders.customerId,
              rows.map((r) => r.customer.id),
            ),
          ),
        )
    : [];
  return c.json({
    peers: rows.map((r) => ({
      nexconnId: r.customer.nexconnId,
      kind: 'customer',
      name: r.customer.name,
      photoUrl: null,
      shopId: shop.id,
      customerId: r.customer.id,
      activeOrders: active.filter((a) => a.customerId === r.customer.id).length,
    })),
  });
});
