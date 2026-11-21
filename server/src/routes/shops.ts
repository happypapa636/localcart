import { and, asc, eq, gte, ilike, lte, or, sql } from 'drizzle-orm';
import { Hono } from 'hono';
import { z } from 'zod';
import { db } from '../db/client.js';
import { catalogItems, shops, users, type Shop } from '../db/schema.js';
import { boundingBox, haversineKm } from '../domain/geo.js';
import { HHMM, isOpenNow } from '../domain/hours.js';
import { UPI_ID } from '../domain/upi.js';
import { type AuthVars, currentUser, requireAuth, requireRole } from '../lib/auth.js';
import { conflict, notFound, parseWith, readJson } from '../lib/http.js';
import { updateUserInfo } from '../nexconn/client.js';
import { normalizePhone } from './auth.js';

export const SHOP_CATEGORIES = [
  'kirana',
  'fruits_vegetables',
  'dairy',
  'bakery',
  'meat_fish',
  'pharmacy',
  'sweets_snacks',
  'household',
  'other',
] as const;

const ShopBody = z.object({
  name: z.string().trim().min(2, 'Enter the shop name.').max(60),
  category: z.enum(SHOP_CATEGORIES),
  description: z.string().trim().max(200).nullable().optional(),
  photoUrl: z.string().url().max(1000).nullable().optional(),
  phone: z.string().transform((v, ctx) => {
    const p = normalizePhone(v);
    if (!p) {
      ctx.addIssue({ code: 'custom', message: 'Enter a valid 10-digit mobile number for the shop.' });
      return z.NEVER;
    }
    return p;
  }),
  addressText: z.string().trim().min(5, 'Enter the shop address.').max(300),
  lat: z.number().min(-90).max(90),
  lng: z.number().min(-180).max(180),
  deliveryRadiusKm: z.number().min(0.5).max(25),
  opensAt: z.string().regex(HHMM, 'Use HH:MM, e.g. 07:00'),
  closesAt: z.string().regex(HHMM, 'Use HH:MM, e.g. 22:00'),
  upiId: z
    .string()
    .trim()
    .regex(UPI_ID, 'Enter a valid UPI ID like shopname@okaxis')
    .nullable()
    .optional(),
});

const ShopPatch = ShopBody.partial().extend({ isOpen: z.boolean().optional() });

export function shopView(shop: Shop, opts: { ownerNexconnId?: string; distanceKm?: number | null } = {}) {
  return {
    id: shop.id,
    name: shop.name,
    category: shop.category,
    description: shop.description,
    photoUrl: shop.photoUrl,
    phone: shop.phone,
    addressText: shop.addressText,
    lat: shop.lat,
    lng: shop.lng,
    deliveryRadiusKm: shop.deliveryRadiusKm,
    opensAt: shop.opensAt,
    closesAt: shop.closesAt,
    isOpen: shop.isOpen,
    openNow: isOpenNow(shop),
    upiId: shop.upiId,
    rating: shop.ratingCount > 0 ? Math.round((shop.ratingSum / shop.ratingCount) * 10) / 10 : null,
    ratingCount: shop.ratingCount,
    ownerNexconnId: opts.ownerNexconnId ?? null,
    distanceKm: opts.distanceKm ?? null,
    deliversToYou: opts.distanceKm != null ? opts.distanceKm <= shop.deliveryRadiusKm : null,
  };
}

async function syncChatIdentity(ownerNexconnId: string, shop: Shop) {
  try {
    await updateUserInfo(ownerNexconnId, shop.name, shop.photoUrl);
  } catch (err) {
    console.error('nexconn user update failed', err);
  }
}

export const shopRoutes = new Hono<{ Variables: AuthVars }>();

shopRoutes.post('/shops', requireAuth, async (c) => {
  const user = requireRole(c, 'shopkeeper');
  const body = await readJson(c, ShopBody);
  const [shop] = await db()
    .insert(shops)
    .values({ ...body, ownerId: user.id, description: body.description ?? null, upiId: body.upiId ?? null })
    .onConflictDoNothing()
    .returning();
  if (!shop) throw conflict('You already have a shop. Edit it from Shop settings.', 'shop_exists');
  await syncChatIdentity(user.nexconnId, shop);
  return c.json({ shop: shopView(shop, { ownerNexconnId: user.nexconnId }) }, 201);
});

shopRoutes.get('/shops/mine', requireAuth, async (c) => {
  const user = requireRole(c, 'shopkeeper');
  const [shop] = await db().select().from(shops).where(eq(shops.ownerId, user.id)).limit(1);
  return c.json({ shop: shop ? shopView(shop, { ownerNexconnId: user.nexconnId }) : null });
});

shopRoutes.patch('/shops/mine', requireAuth, async (c) => {
  const user = requireRole(c, 'shopkeeper');
  const body = await readJson(c, ShopPatch);
  const [shop] = await db()
    .update(shops)
    .set({ ...body, updatedAt: new Date() })
    .where(eq(shops.ownerId, user.id))
    .returning();
  if (!shop) throw notFound('Create your shop first.');
  if (body.name !== undefined || body.photoUrl !== undefined) await syncChatIdentity(user.nexconnId, shop);
  return c.json({ shop: shopView(shop, { ownerNexconnId: user.nexconnId }) });
});

const NearbyQuery = z.object({
  lat: z.coerce.number().min(-90).max(90),
  lng: z.coerce.number().min(-180).max(180),
  radiusKm: z.coerce.number().min(0.5).max(30).default(10),
  category: z.enum(SHOP_CATEGORIES).optional(),
  q: z.string().trim().max(60).optional(),
});

/** Shops near a point, open ones first, then by distance. Search matches shop names and catalog items. */
shopRoutes.get('/shops/nearby', requireAuth, async (c) => {
  const q = parseWith(NearbyQuery, c.req.query());
  const box = boundingBox(q.lat, q.lng, q.radiusKm);
  const conditions = [
    gte(shops.lat, box.minLat),
    lte(shops.lat, box.maxLat),
    gte(shops.lng, box.minLng),
    lte(shops.lng, box.maxLng),
  ];
  if (q.category) conditions.push(eq(shops.category, q.category));
  if (q.q) {
    const pattern = `%${q.q.replace(/[%_\\]/g, (m) => `\\${m}`)}%`;
    conditions.push(
      or(
        ilike(shops.name, pattern),
        sql`exists (select 1 from ${catalogItems} ci where ci.shop_id = ${shops.id} and ci.archived = false and ci.name ilike ${pattern})`,
      )!,
    );
  }
  const rows = await db()
    .select({ shop: shops, ownerNexconnId: users.nexconnId })
    .from(shops)
    .innerJoin(users, eq(users.id, shops.ownerId))
    .where(and(...conditions))
    .limit(200);
  const list = rows
    .map((r) => shopView(r.shop, { ownerNexconnId: r.ownerNexconnId, distanceKm: haversineKm(q.lat, q.lng, r.shop.lat, r.shop.lng) }))
    .filter((s) => (s.distanceKm ?? 0) <= q.radiusKm)
    .sort((a, b) => Number(b.openNow) - Number(a.openNow) || (a.distanceKm ?? 0) - (b.distanceKm ?? 0))
    .map((s) => ({ ...s, distanceKm: Math.round((s.distanceKm ?? 0) * 100) / 100 }));
  return c.json({ shops: list });
});

shopRoutes.get('/shops/:id', requireAuth, async (c) => {
  const user = currentUser(c);
  const id = parseWith(z.string().uuid('Shop not found.'), c.req.param('id'));
  const [row] = await db()
    .select({ shop: shops, ownerNexconnId: users.nexconnId })
    .from(shops)
    .innerJoin(users, eq(users.id, shops.ownerId))
    .where(eq(shops.id, id))
    .limit(1);
  if (!row) throw notFound('Shop not found.');
  const items = await db()
    .select()
    .from(catalogItems)
    .where(and(eq(catalogItems.shopId, id), eq(catalogItems.archived, false)))
    .orderBy(asc(catalogItems.name));
  const distanceKm =
    user.lat != null && user.lng != null ? haversineKm(user.lat, user.lng, row.shop.lat, row.shop.lng) : null;
  return c.json({
    shop: shopView(row.shop, { ownerNexconnId: row.ownerNexconnId, distanceKm }),
    items: items
      .sort((a, b) => Number(b.inStock) - Number(a.inStock))
      .map((i) => ({
        id: i.id,
        name: i.name,
        unit: i.unit,
        pricePaise: i.pricePaise,
        inStock: i.inStock,
        photoUrl: i.photoUrl,
      })),
  });
});
