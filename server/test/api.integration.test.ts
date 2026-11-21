/**
 * End-to-end API test against the real stack: Neon Postgres, Nexconn Platform API and Gemini.
 * Runs the Hono app in-process. Skipped when server/.env.local is missing. Cleans up its users.
 */
import { readFileSync } from 'node:fs';
import { config } from 'dotenv';
import { afterAll, beforeAll, describe, expect, it } from 'vitest';

config({ path: '.env.local' });
const enabled = Boolean(process.env.DATABASE_URL && process.env.NEXCONN_APP_SECRET && process.env.GEMINI_API_KEY);

type Json = Record<string, any>;

describe.skipIf(!enabled)('LocalCart API: full order loop on the real stack', () => {
  let app: { request: (path: string, init?: RequestInit) => Response | Promise<Response> };
  const createdPhones: string[] = [];
  const randomPhone = () => `9${Math.floor(100000000 + Math.random() * 899999999)}`;

  async function call(method: string, path: string, token?: string, body?: unknown) {
    const headers: Record<string, string> = {};
    if (token) headers.authorization = `Bearer ${token}`;
    let payload: RequestInit["body"];
    if (body instanceof FormData) payload = body;
    else if (body !== undefined) {
      headers['content-type'] = 'application/json';
      payload = JSON.stringify(body);
    }
    const res = await app.request(`/api${path}`, { method, headers, body: payload });
    const json = (await res.json()) as Json;
    return { status: res.status, json };
  }

  async function signup(role: 'customer' | 'shopkeeper', name: string) {
    const phone = randomPhone();
    createdPhones.push(phone);
    const res = await call('POST', '/auth/signup', undefined, { name, phone, password: 'test-password-123', role });
    expect(res.status).toBe(201);
    return { token: res.json.token as string, user: res.json.user as Json, phone };
  }

  let shopkeeper: Awaited<ReturnType<typeof signup>>;
  let customer: Awaited<ReturnType<typeof signup>>;
  let stranger: Awaited<ReturnType<typeof signup>>;
  let shopId = '';
  let orderId = '';

  beforeAll(async () => {
    app = (await import('../src/index.js')).default;
    shopkeeper = await signup('shopkeeper', 'Test Shopkeeper');
    customer = await signup('customer', 'Test Customer');
    stranger = await signup('customer', 'Test Stranger');
  });

  afterAll(async () => {
    if (!enabled) return;
    const { db } = await import('../src/db/client.js');
    const { users } = await import('../src/db/schema.js');
    const { inArray } = await import('drizzle-orm');
    if (createdPhones.length) await db().delete(users).where(inArray(users.phone, createdPhones));
  });

  it('rejects bad credentials and duplicate phones', async () => {
    const bad = await call('POST', '/auth/login', undefined, { phone: customer.phone, password: 'wrong-password' });
    expect(bad.status).toBe(401);
    const dup = await call('POST', '/auth/signup', undefined, {
      name: 'Again',
      phone: customer.phone,
      password: 'test-password-123',
      role: 'customer',
    });
    expect(dup.status).toBe(409);
    const ok = await call('POST', '/auth/login', undefined, { phone: customer.phone, password: 'test-password-123' });
    expect(ok.status).toBe(200);
  });

  it('issues Nexconn chat tokens', async () => {
    const res = await call('POST', '/chat/token', customer.token, {});
    expect(res.status).toBe(200);
    expect(res.json.userId).toMatch(/^lcr[A-Za-z0-9]{22}$/);
    expect(res.json.token.length).toBeGreaterThan(20);
    const again = await call('POST', '/chat/token', customer.token, {});
    expect(again.json.token).toBe(res.json.token);
  });

  it('lets a shopkeeper create a shop and catalog', async () => {
    const forbidden = await call('POST', '/shops', customer.token, {});
    expect(forbidden.status).toBe(403);
    const shop = await call('POST', '/shops', shopkeeper.token, {
      name: 'Integration Test Kirana',
      category: 'kirana',
      phone: shopkeeper.phone,
      addressText: 'Test address, far away from real users',
      lat: -60.5,
      lng: -45.5,
      deliveryRadiusKm: 3,
      opensAt: '00:00',
      closesAt: '00:00',
      upiId: 'testkirana@okaxis',
    });
    expect(shop.status).toBe(201);
    shopId = shop.json.shop.id;
    expect(shop.json.shop.openNow).toBe(true);

    const bulk = await call('POST', '/shops/mine/items/bulk', shopkeeper.token, {
      items: [
        { name: 'Potato (Aloo)', unit: 'kg', pricePaise: 3000, inStock: true },
        { name: 'Amul Taaza Milk 500 ml', unit: 'pack', pricePaise: 2800, inStock: true },
        { name: 'Eggs', unit: 'pc', pricePaise: 700, inStock: true },
        { name: 'Tata Salt 1 kg', unit: 'pack', pricePaise: 2800, inStock: true },
        { name: 'Onion (Pyaaz)', unit: 'kg', pricePaise: 4000, inStock: false },
      ],
    });
    expect(bulk.status).toBe(201);
    expect(bulk.json.items).toHaveLength(5);
  });

  it('parses a typed list into catalog rows with AI', async () => {
    const res = await call('POST', '/shops/mine/items/parse', shopkeeper.token, {
      text: 'Aloo 30/kg\nAmul taaza 500ml 28\nParle G 10 rupaye',
    });
    expect(res.status).toBe(200);
    expect(res.json.items.length).toBeGreaterThanOrEqual(3);
    expect(res.json.items.find((i: Json) => /parle/i.test(i.name))?.pricePaise).toBe(1000);
  });

  it('finds the shop nearby and opens a chat', async () => {
    const near = await call('GET', `/shops/nearby?lat=-60.51&lng=-45.51`, customer.token);
    expect(near.status).toBe(200);
    expect(near.json.shops.some((s: Json) => s.id === shopId)).toBe(true);
    const search = await call('GET', `/shops/nearby?lat=-60.51&lng=-45.51&q=amul`, customer.token);
    expect(search.json.shops.some((s: Json) => s.id === shopId)).toBe(true);
    const chat = await call('POST', '/chats', customer.token, { shopId });
    expect(chat.status).toBe(200);
    expect(chat.json.peer.nexconnId).toBe(shopkeeper.user.nexconnId);
    const peers = await call('GET', '/chats', shopkeeper.token);
    expect(peers.json.peers.some((p: Json) => p.nexconnId === customer.user.nexconnId)).toBe(true);
  });

  it('shares a delivery location as a Nexconn location message, only within a chat', async () => {
    const tiny = '/9j/4AAQSkZJRgABAQEASABIAAD/2wBDAP//////////////////////////////////////////////////////////////////////////////////////wgALCAABAAEBAREA/8QAFBABAAAAAAAAAAAAAAAAAAAAAP/aAAgBAQABPxA=';
    const body = { peerNexconnId: shopkeeper.user.nexconnId, lat: 12.97, lng: 77.64, place: 'Flat 4B, Test Residency', thumbnail: tiny };
    const ok = await call('POST', '/chats/location', customer.token, body);
    expect(ok.status).toBe(201);
    expect(ok.json.messageId).toBeTruthy();
    expect((await call('POST', '/chats/location', stranger.token, body)).status).toBe(404);
  });

  it('turns a typed order into a draft Cart card', async () => {
    const res = await call('POST', '/orders/draft', customer.token, {
      shopId,
      text: '2 kg aloo, 1 litre Amul milk, 5 eggs and 1 Maggi',
    });
    expect(res.status).toBe(201);
    const order = res.json.order;
    orderId = order.id;
    expect(order.status).toBe('draft');
    expect(order.cartMessageId).toBeTruthy();
    const names = order.items.map((i: Json) => i.name);
    expect(names).toContain('Potato (Aloo)');
    expect(order.items.find((i: Json) => i.name === 'Amul Taaza Milk 500 ml')?.quantity).toBe(2);
    expect(order.items.some((i: Json) => i.catalogItemId === null)).toBe(true); // Maggi is not in the catalog
  });

  it('keeps orders private to their customer and shop', async () => {
    expect((await call('GET', `/orders/${orderId}`, stranger.token)).status).toBe(404);
    expect((await call('POST', `/orders/${orderId}/bill`, customer.token, { lines: [] })).status).toBe(400);
  });

  it('lets the customer edit and send the cart', async () => {
    const current = (await call('GET', `/orders/${orderId}`, customer.token)).json.order;
    const items = current.items.map((i: Json) => ({
      catalogItemId: i.catalogItemId,
      name: i.name,
      unit: i.unit,
      quantity: i.name === 'Eggs' ? 6 : i.quantity,
      requestedText: i.requestedText,
    }));
    const edited = await call('PUT', `/orders/${orderId}/cart`, customer.token, { items });
    expect(edited.status).toBe(200);
    expect(edited.json.order.cartRev).toBe(2);
    expect(edited.json.order.items.find((i: Json) => i.name === 'Eggs').quantity).toBe(6);
    const early = await call('POST', `/orders/${orderId}/bill`, shopkeeper.token, { lines: [{ itemId: current.items[0].id, available: true, quantity: 1, unitPricePaise: 100 }] });
    expect(early.status).toBe(409);
    const confirmed = await call('POST', `/orders/${orderId}/confirm`, customer.token);
    expect(confirmed.status).toBe(200);
    expect(confirmed.json.order.status).toBe('placed');
  });

  it('lets the shop send a bill with availability, prices and a delivery fee', async () => {
    const order = (await call('GET', `/orders/${orderId}`, shopkeeper.token)).json.order;
    expect(order.customer.phone).toBe(customer.phone);
    const lines = order.items.map((i: Json) => ({
      itemId: i.id,
      available: i.name !== 'Eggs',
      quantity: i.quantity,
      unitPricePaise: i.unitPricePaise ?? 1400,
    }));
    const res = await call('POST', `/orders/${orderId}/bill`, shopkeeper.token, { lines, deliveryFeePaise: 1000, note: 'Eggs finished today' });
    expect(res.status).toBe(200);
    const billed = res.json.order;
    expect(billed.status).toBe('billed');
    expect(billed.billMessageId).toBeTruthy();
    // 2 kg aloo (6000) + 2 packs milk (5600) + Maggi priced by shop (1400) + delivery (1000)
    expect(billed.totalPaise).toBe(6000 + 5600 + 1400 + 1000);
    const twice = await call('POST', `/orders/${orderId}/bill`, shopkeeper.token, { lines, deliveryFeePaise: 1000 });
    expect(twice.status).toBe(409);
  });

  it('lets the customer accept with UPI and mark it paid; the shop confirms', async () => {
    const accepted = await call('POST', `/orders/${orderId}/accept`, customer.token, { paymentMethod: 'upi' });
    expect(accepted.status).toBe(200);
    expect(accepted.json.order.status).toBe('accepted');
    expect(accepted.json.order.statusMessageId).toBeTruthy();
    expect(accepted.json.order.upiLink).toContain('pa=testkirana%40okaxis');
    expect(accepted.json.order.upiLink).toContain('am=140.00');
    expect((await call('POST', `/orders/${orderId}/payment`, shopkeeper.token, { action: 'marked_paid' })).status).toBe(403);
    expect((await call('POST', `/orders/${orderId}/payment`, customer.token, { action: 'marked_paid' })).json.order.paymentStatus).toBe('marked_paid');
    expect((await call('POST', `/orders/${orderId}/payment`, shopkeeper.token, { action: 'confirmed' })).json.order.paymentStatus).toBe('confirmed');
  });

  it('moves fulfilment strictly in order and allows one rating after delivery', async () => {
    expect((await call('POST', `/orders/${orderId}/status`, shopkeeper.token, { status: 'delivered' })).status).toBe(409);
    for (const status of ['packed', 'out_for_delivery', 'delivered']) {
      const res = await call('POST', `/orders/${orderId}/status`, shopkeeper.token, { status });
      expect(res.status).toBe(200);
      expect(res.json.order.status).toBe(status);
    }
    const rated = await call('POST', `/orders/${orderId}/rate`, customer.token, { stars: 5, comment: 'Quick delivery' });
    expect(rated.status).toBe(200);
    expect((await call('POST', `/orders/${orderId}/rate`, customer.token, { stars: 1 })).status).toBe(409);
    const shop = await call('GET', `/shops/${shopId}`, customer.token);
    expect(shop.json.shop.rating).toBe(5);
  });

  it('shows the order in history and the dashboard, and reorders in one tap', async () => {
    const past = await call('GET', '/orders?scope=past', customer.token);
    expect(past.json.orders[0].id).toBe(orderId);
    const dash = await call('GET', '/dashboard', shopkeeper.token);
    expect(dash.json.todaySalesPaise).toBeGreaterThanOrEqual(14000);
    const re = await call('POST', `/orders/${orderId}/reorder`, customer.token);
    expect(re.status).toBe(201);
    expect(re.json.order.status).toBe('draft');
    expect(re.json.order.source).toBe('reorder');
    const cancelled = await call('POST', `/orders/${re.json.order.id}/cancel`, customer.token, { reason: 'Ordered by mistake' });
    expect(cancelled.json.order.status).toBe('cancelled');
  });

  it('turns a real voice note (AAC) into a cart', async () => {
    const form = new FormData();
    form.set('shopId', shopId);
    form.set('audio', new File([readFileSync('test/fixtures/order-en.aac')], 'order.aac', { type: 'audio/aac' }));
    const res = await call('POST', '/orders/draft', customer.token, form);
    expect(res.status).toBe(201);
    expect(res.json.order.source).toBe('voice');
    expect(res.json.transcript.toLowerCase()).toContain('potato');
    const names = res.json.order.items.map((i: Json) => i.name);
    expect(names).toEqual(expect.arrayContaining(['Potato (Aloo)', 'Eggs', 'Tata Salt 1 kg']));
  });
});
