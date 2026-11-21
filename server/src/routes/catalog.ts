import { and, asc, eq } from 'drizzle-orm';
import { Hono } from 'hono';
import { z } from 'zod';
import { parseCatalogList } from '../ai/catalogParser.js';
import { db } from '../db/client.js';
import { catalogItems, shops, type CatalogItem, type User } from '../db/schema.js';
import { UNITS } from '../domain/units.js';
import { type AuthVars, requireAuth, requireRole } from '../lib/auth.js';
import { badRequest, notFound, parseWith, readJson } from '../lib/http.js';
import { rateLimit } from '../lib/rateLimit.js';

const ItemBody = z.object({
  name: z.string().trim().min(1, 'Enter the item name.').max(80),
  unit: z.enum(UNITS),
  pricePaise: z.number().int().min(1, 'Price must be more than ₹0.').max(10_000_000),
  inStock: z.boolean().default(true),
  photoUrl: z.string().url().max(1000).nullable().optional(),
});

const ItemPatch = ItemBody.partial();

export function itemView(i: CatalogItem) {
  return {
    id: i.id,
    name: i.name,
    unit: i.unit,
    pricePaise: i.pricePaise,
    inStock: i.inStock,
    photoUrl: i.photoUrl,
    updatedAt: i.updatedAt,
  };
}

async function myShopId(user: User): Promise<string> {
  const [shop] = await db().select({ id: shops.id }).from(shops).where(eq(shops.ownerId, user.id)).limit(1);
  if (!shop) throw notFound('Create your shop first.');
  return shop.id;
}

export const catalogRoutes = new Hono<{ Variables: AuthVars }>();

catalogRoutes.get('/shops/mine/items', requireAuth, async (c) => {
  const shopId = await myShopId(requireRole(c, 'shopkeeper'));
  const items = await db()
    .select()
    .from(catalogItems)
    .where(and(eq(catalogItems.shopId, shopId), eq(catalogItems.archived, false)))
    .orderBy(asc(catalogItems.name));
  return c.json({ items: items.map(itemView) });
});

catalogRoutes.post('/shops/mine/items', requireAuth, async (c) => {
  const shopId = await myShopId(requireRole(c, 'shopkeeper'));
  const body = await readJson(c, ItemBody);
  const [item] = await db()
    .insert(catalogItems)
    .values({ ...body, photoUrl: body.photoUrl ?? null, shopId })
    .returning();
  return c.json({ item: itemView(item!) }, 201);
});

catalogRoutes.post('/shops/mine/items/bulk', requireAuth, async (c) => {
  const shopId = await myShopId(requireRole(c, 'shopkeeper'));
  const { items } = await readJson(c, z.object({ items: z.array(ItemBody).min(1).max(200) }));
  const created = await db()
    .insert(catalogItems)
    .values(items.map((i) => ({ ...i, photoUrl: i.photoUrl ?? null, shopId })))
    .returning();
  return c.json({ items: created.map(itemView) }, 201);
});

/** AI: turn a pasted or typed list into catalog rows for the shopkeeper to review. Nothing is saved. */
catalogRoutes.post('/shops/mine/items/parse', requireAuth, async (c) => {
  const user = requireRole(c, 'shopkeeper');
  await myShopId(user);
  const { text } = await readJson(c, z.object({ text: z.string().trim().min(3, 'Type or paste your list.').max(6000) }));
  await rateLimit(`ai-catalog:${user.id}`, 20, 3600, 'You have used the list reader a lot this hour. Try again later.');
  const result = await parseCatalogList(text);
  if (result.items.length === 0) throw badRequest('No products found in that list. Put one product per line with its price.');
  return c.json({ items: result.items });
});

catalogRoutes.patch('/shops/mine/items/:itemId', requireAuth, async (c) => {
  const shopId = await myShopId(requireRole(c, 'shopkeeper'));
  const itemId = parseWith(z.string().uuid('Item not found.'), c.req.param('itemId'));
  const body = await readJson(c, ItemPatch);
  const [item] = await db()
    .update(catalogItems)
    .set({ ...body, updatedAt: new Date() })
    .where(and(eq(catalogItems.id, itemId), eq(catalogItems.shopId, shopId), eq(catalogItems.archived, false)))
    .returning();
  if (!item) throw notFound('Item not found.');
  return c.json({ item: itemView(item) });
});

/** Items are archived rather than deleted so past orders keep their history. */
catalogRoutes.delete('/shops/mine/items/:itemId', requireAuth, async (c) => {
  const shopId = await myShopId(requireRole(c, 'shopkeeper'));
  const itemId = parseWith(z.string().uuid('Item not found.'), c.req.param('itemId'));
  const [item] = await db()
    .update(catalogItems)
    .set({ archived: true, updatedAt: new Date() })
    .where(and(eq(catalogItems.id, itemId), eq(catalogItems.shopId, shopId)))
    .returning();
  if (!item) throw notFound('Item not found.');
  return c.json({ ok: true });
});
