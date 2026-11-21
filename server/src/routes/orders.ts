import { and, count, desc, eq, gte, inArray, lt, ne, sql, sum } from 'drizzle-orm';
import { Hono } from 'hono';
import { z } from 'zod';
import { parseOrder, type OrderInput } from '../ai/orderParser.js';
import { db } from '../db/client.js';
import { catalogItems, orderItems, orders, shops, users, type Order, type Shop, type User } from '../db/schema.js';
import { ACTIVE_STATUSES } from '../domain/orderState.js';
import { isOpenNow, startOfIstDay } from '../domain/hours.js';
import { type AuthVars, currentUser, requireAuth, requireRole } from '../lib/auth.js';
import { ApiError, badRequest, notFound, parseWith, readJson } from '../lib/http.js';
import { rateLimit } from '../lib/rateLimit.js';
import {
  acceptBill,
  advanceFulfilment,
  cancelOrder,
  confirmCart,
  createDraftOrder,
  editCart,
  loadOrderContext,
  orderView,
  rateOrder,
  sendBill,
  setDeliveryLocation,
  updatePayment,
} from '../services/orders.js';
import { shopView } from './shops.js';

const MAX_AUDIO_BYTES = 4 * 1024 * 1024;

/** Identify audio by magic bytes so Gemini gets a correct MIME type whatever the client claims. */
export function sniffAudioType(b: Uint8Array): string | null {
  if (b[0] === 0xff && b[1] !== undefined && (b[1] & 0xf6) === 0xf0) return 'audio/aac'; // ADTS
  if (b[4] === 0x66 && b[5] === 0x74 && b[6] === 0x79 && b[7] === 0x70) return 'audio/mp4'; // ftyp (m4a)
  if (b[0] === 0x52 && b[1] === 0x49 && b[2] === 0x46 && b[3] === 0x46 && b[8] === 0x57 && b[9] === 0x41) return 'audio/wav';
  if (b[0] === 0x4f && b[1] === 0x67 && b[2] === 0x67 && b[3] === 0x53) return 'audio/ogg';
  if ((b[0] === 0x49 && b[1] === 0x44 && b[2] === 0x33) || (b[0] === 0xff && b[1] !== undefined && (b[1] & 0xe0) === 0xe0)) {
    return 'audio/mpeg';
  }
  if (b[0] === 0x66 && b[1] === 0x4c && b[2] === 0x61 && b[3] === 0x43) return 'audio/flac';
  return null;
}

const LineSchema = z.object({
  catalogItemId: z.string().uuid().nullable(),
  name: z.string().trim().min(1).max(80),
  unit: z.string().max(20),
  quantity: z.number().positive().max(10_000),
  requestedText: z.string().max(200).nullable().optional(),
});

const Uuid = z.string().uuid('Order not found.');

async function loadShopForOrdering(shopId: string): Promise<Shop> {
  const [shop] = await db().select().from(shops).where(eq(shops.id, shopId)).limit(1);
  if (!shop) throw notFound('Shop not found.');
  if (!isOpenNow(shop)) {
    throw new ApiError(409, 'shop_closed', `${shop.name} is not taking orders right now. Hours: ${shop.opensAt}–${shop.closesAt}.`);
  }
  return shop;
}

async function shopCatalog(shopId: string) {
  return db()
    .select()
    .from(catalogItems)
    .where(and(eq(catalogItems.shopId, shopId), eq(catalogItems.archived, false)));
}

async function readOrderInput(c: Parameters<typeof currentUser>[0]): Promise<{ shopId: string; input: OrderInput }> {
  const type = c.req.header('content-type') ?? '';
  if (type.includes('multipart/form-data')) {
    const form = await c.req.parseBody();
    const shopId = parseWith(z.string().uuid('Shop not found.'), form['shopId']);
    const file = form['audio'];
    if (!(file instanceof File)) throw badRequest('Attach the voice note in the "audio" field.');
    if (file.size === 0) throw badRequest('The voice note is empty. Record again.');
    if (file.size > MAX_AUDIO_BYTES) throw badRequest('Voice notes must be under 4 MB (about 2 minutes).');
    const bytes = new Uint8Array(await file.arrayBuffer());
    const mimeType = sniffAudioType(bytes);
    if (!mimeType) throw badRequest('That audio format is not supported. Record a new voice note.');
    return { shopId, input: { kind: 'audio', mimeType, base64: Buffer.from(bytes).toString('base64') } };
  }
  const body = await readJson(
    c,
    z.object({ shopId: z.string().uuid('Shop not found.'), text: z.string().trim().min(2, 'Type what you need.').max(2000) }),
  );
  return { shopId: body.shopId, input: { kind: 'text', text: body.text } };
}

function summary(o: Order, shop: Pick<Shop, 'id' | 'name' | 'photoUrl'>, customer: Pick<User, 'id' | 'name'>, itemCount: number) {
  return {
    id: o.id,
    code: o.code,
    status: o.status,
    shop: { id: shop.id, name: shop.name, photoUrl: shop.photoUrl },
    customer: { id: customer.id, name: customer.name },
    totalPaise: o.totalPaise,
    itemCount,
    paymentMethod: o.paymentMethod,
    paymentStatus: o.paymentStatus,
    rating: o.rating,
    createdAt: o.createdAt,
    updatedAt: o.updatedAt,
  };
}

export const orderRoutes = new Hono<{ Variables: AuthVars }>();

/**
 * Customer sends a typed list or a voice note; Gemini turns it into lines matched to the catalog and the
 * server posts a draft Cart card in the chat.
 */
orderRoutes.post('/orders/draft', requireAuth, async (c) => {
  const user = requireRole(c, 'customer');
  const { shopId, input } = await readOrderInput(c);
  const shop = await loadShopForOrdering(shopId);
  await rateLimit(`ai-order:${user.id}`, 30, 3600, 'You have made a lot of carts this hour. Try again in a while.');
  const catalog = await shopCatalog(shop.id);
  if (catalog.length === 0) throw new ApiError(409, 'empty_catalog', `${shop.name} has not added any items yet. Chat with them instead.`);
  const parsed = await parseOrder(input, catalog);
  if (parsed.lines.length === 0) {
    throw new ApiError(
      422,
      'no_items',
      input.kind === 'audio'
        ? `No items heard in that voice note. Heard: "${parsed.transcript.slice(0, 120)}"`
        : 'No items found in that message. List what you need, like "2 kg aloo, 1 litre milk".',
    );
  }
  const ctx = await createDraftOrder({
    shop,
    customer: user,
    source: input.kind === 'audio' ? 'voice' : 'text',
    sourceText: input.kind === 'audio' ? parsed.transcript : input.text,
    lines: parsed.lines.map((l) => ({ ...l, requestedText: l.requestedText })),
  });
  return c.json(
    {
      order: orderView(ctx),
      transcript: parsed.transcript,
      language: parsed.language,
      notes: parsed.lines.filter((l) => l.note).map((l) => ({ name: l.name, note: l.note })),
    },
    201,
  );
});

const ListQuery = z.object({
  scope: z.enum(['active', 'past', 'all']).default('all'),
  before: z.string().datetime().optional(),
  peer: z.string().max(80).optional(),
});

orderRoutes.get('/orders', requireAuth, async (c) => {
  const user = currentUser(c);
  const q = parseWith(ListQuery, c.req.query());
  const conditions = [];
  if (user.role === 'customer') {
    conditions.push(eq(orders.customerId, user.id));
  } else {
    const [shop] = await db().select({ id: shops.id }).from(shops).where(eq(shops.ownerId, user.id)).limit(1);
    if (!shop) return c.json({ orders: [] });
    conditions.push(eq(orders.shopId, shop.id), ne(orders.status, 'draft'));
  }
  if (q.scope === 'active') conditions.push(inArray(orders.status, ['draft', ...ACTIVE_STATUSES]));
  if (q.scope === 'past') conditions.push(inArray(orders.status, ['delivered', 'cancelled']));
  if (q.before) conditions.push(lt(orders.createdAt, new Date(q.before)));
  if (q.peer) {
    // Orders with one chat peer (shop owner or customer), for the chat header's order strip.
    const [peer] = await db().select().from(users).where(eq(users.nexconnId, q.peer)).limit(1);
    if (!peer) return c.json({ orders: [] });
    if (user.role === 'customer') {
      const [peerShop] = await db().select({ id: shops.id }).from(shops).where(eq(shops.ownerId, peer.id)).limit(1);
      if (!peerShop) return c.json({ orders: [] });
      conditions.push(eq(orders.shopId, peerShop.id));
    } else {
      conditions.push(eq(orders.customerId, peer.id));
    }
  }
  const rows = await db()
    .select({ order: orders, shop: shops, customer: users })
    .from(orders)
    .innerJoin(shops, eq(shops.id, orders.shopId))
    .innerJoin(users, eq(users.id, orders.customerId))
    .where(and(...conditions))
    .orderBy(desc(orders.createdAt))
    .limit(30);
  const counts = rows.length
    ? await db()
        .select({ orderId: orderItems.orderId, n: count() })
        .from(orderItems)
        .where(inArray(orderItems.orderId, rows.map((r) => r.order.id)))
        .groupBy(orderItems.orderId)
    : [];
  const countMap = new Map(counts.map((r) => [r.orderId, Number(r.n)]));
  return c.json({ orders: rows.map((r) => summary(r.order, r.shop, r.customer, countMap.get(r.order.id) ?? 0)) });
});

orderRoutes.get('/orders/:id', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  return c.json({ order: orderView(ctx) });
});

orderRoutes.put('/orders/:id/cart', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  const { items } = await readJson(c, z.object({ items: z.array(LineSchema).min(1).max(40) }));
  return c.json({ order: orderView(await editCart(ctx, items)) });
});

orderRoutes.post('/orders/:id/confirm', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  await loadShopForOrdering(ctx.shop.id);
  return c.json({ order: orderView(await confirmCart(ctx)) });
});

orderRoutes.post('/orders/:id/bill', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  const body = await readJson(
    c,
    z.object({
      lines: z
        .array(
          z.object({
            itemId: z.string().uuid(),
            available: z.boolean(),
            quantity: z.number().positive().max(10_000),
            unitPricePaise: z.number().int().min(0).max(10_000_000),
          }),
        )
        .min(1)
        .max(40),
      deliveryFeePaise: z.number().int().min(0).max(100_000).default(0),
      note: z.string().trim().max(300).nullable().optional(),
    }),
  );
  return c.json({ order: orderView(await sendBill(ctx, { ...body, note: body.note ?? null })) });
});

orderRoutes.post('/orders/:id/accept', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  const { paymentMethod } = await readJson(c, z.object({ paymentMethod: z.enum(['cod', 'upi']) }));
  return c.json({ order: orderView(await acceptBill(ctx, paymentMethod)) });
});

orderRoutes.post('/orders/:id/payment', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  const { action } = await readJson(c, z.object({ action: z.enum(['marked_paid', 'confirmed']) }));
  return c.json({ order: orderView(await updatePayment(ctx, action)) });
});

orderRoutes.post('/orders/:id/status', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  const { status } = await readJson(c, z.object({ status: z.enum(['packed', 'out_for_delivery', 'delivered']) }));
  return c.json({ order: orderView(await advanceFulfilment(ctx, status)) });
});

orderRoutes.post('/orders/:id/cancel', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  const { reason } = await readJson(c, z.object({ reason: z.string().trim().min(2, 'Tell them why.').max(200) }));
  return c.json({ order: orderView(await cancelOrder(ctx, reason)) });
});

orderRoutes.post('/orders/:id/rate', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  const body = await readJson(
    c,
    z.object({ stars: z.number().int().min(1).max(5), comment: z.string().trim().max(300).nullable().optional() }),
  );
  return c.json({ order: orderView(await rateOrder(ctx, body.stars, body.comment ?? null)) });
});

orderRoutes.put('/orders/:id/delivery-location', requireAuth, async (c) => {
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), currentUser(c));
  const body = await readJson(
    c,
    z.object({
      lat: z.number().min(-90).max(90),
      lng: z.number().min(-180).max(180),
      address: z.string().trim().max(300).nullable().optional(),
    }),
  );
  return c.json({ order: orderView(await setDeliveryLocation(ctx, { ...body, address: body.address ?? null })) });
});

/** One-tap reorder: the same items at today's prices, posted as a new draft Cart card. */
orderRoutes.post('/orders/:id/reorder', requireAuth, async (c) => {
  const user = requireRole(c, 'customer');
  const ctx = await loadOrderContext(parseWith(Uuid, c.req.param('id')), user);
  const shop = await loadShopForOrdering(ctx.shop.id);
  await rateLimit(`reorder:${user.id}`, 30, 3600, 'Too many reorders this hour. Try again later.');
  const catalog = await shopCatalog(shop.id);
  const live = new Map(catalog.map((i) => [i.id, i]));
  const lines = ctx.items
    .filter((i) => i.available || i.catalogItemId == null)
    .filter((i) => i.catalogItemId == null || live.has(i.catalogItemId))
    .map((i) => ({
      catalogItemId: i.catalogItemId,
      name: i.name,
      unit: i.unit,
      quantity: i.quantity,
      requestedText: i.requestedText,
    }));
  if (lines.length === 0) throw new ApiError(409, 'nothing_to_reorder', 'None of those items are sold here any more.');
  const created = await createDraftOrder({
    shop,
    customer: user,
    source: 'reorder',
    sourceText: `Reorder of ${ctx.order.code}`,
    lines,
  });
  return c.json({ order: orderView(created) }, 201);
});

orderRoutes.get('/dashboard', requireAuth, async (c) => {
  const user = requireRole(c, 'shopkeeper');
  const [shop] = await db().select().from(shops).where(eq(shops.ownerId, user.id)).limit(1);
  if (!shop) return c.json({ shop: null });
  const dayStart = startOfIstDay();
  const statusCounts = await db()
    .select({ status: orders.status, n: count() })
    .from(orders)
    .where(and(eq(orders.shopId, shop.id), inArray(orders.status, [...ACTIVE_STATUSES])))
    .groupBy(orders.status);
  const [sales] = await db()
    .select({ total: sum(orders.totalPaise), n: count() })
    .from(orders)
    .where(and(eq(orders.shopId, shop.id), eq(orders.status, 'delivered'), gte(orders.deliveredAt, dayStart)));
  const [today] = await db()
    .select({ n: count() })
    .from(orders)
    .where(and(eq(orders.shopId, shop.id), ne(orders.status, 'draft'), gte(orders.placedAt, dayStart)));
  const [pendingPayments] = await db()
    .select({ n: count() })
    .from(orders)
    .where(
      and(
        eq(orders.shopId, shop.id),
        ne(orders.status, 'cancelled'),
        inArray(orders.paymentStatus, ['pending', 'marked_paid']),
        sql`${orders.paymentMethod} is not null`,
      ),
    );
  const counts: Record<string, number> = Object.fromEntries(ACTIVE_STATUSES.map((s) => [s, 0]));
  for (const row of statusCounts) counts[row.status] = Number(row.n);
  return c.json({
    shop: shopView(shop, { ownerNexconnId: user.nexconnId }),
    counts,
    todaySalesPaise: Number(sales?.total ?? 0),
    todayDelivered: Number(sales?.n ?? 0),
    todayOrders: Number(today?.n ?? 0),
    pendingPayments: Number(pendingPayments?.n ?? 0),
  });
});
