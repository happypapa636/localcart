/**
 * Drives the "other phone" in a two-user test against a deployed LocalCart API, while a real device or
 * emulator runs the app as the first user. Every step goes through the public API (and, for plain chat
 * text, the Nexconn Platform Chat API as that user), exactly like a second phone would.
 *
 * Usage (from server/):
 *   npx tsx scripts/e2e-order-flow.ts <command> [args]
 *
 * Shop side (app on the device is the customer):
 *   shop:setup <lat> <lng>            create a shopkeeper, an open shop and a catalog near the point
 *   shop:bill                         bill the newest placed cart (catalog prices, ₹10 delivery)
 *   shop:advance                      move the newest accepted order one step (packed, out, delivered)
 *   shop:confirm-payment              confirm payment on the newest order
 *   shop:say "<text>"                 send a chat message to the customer
 *
 * Customer side (app on the device is the shopkeeper):
 *   customer:setup <lat> <lng>        create a customer located near the point
 *   customer:order "<list>"           open a chat with the nearest shop, make a cart with AI, send it
 *   customer:accept <cod|upi>         accept the newest bill; with upi also marks it paid
 *   customer:say "<text>"             send a chat message to the shop
 *
 *   status                            print the newest order seen by either side
 *   cleanup                           delete the test users created by this script
 *
 * State (test tokens and ids) is kept outside the repo, in E2E_STATE or the OS temp folder.
 * E2E_BASE_URL selects the API (default: production).
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { config } from 'dotenv';

config({ path: '.env.local' });

const BASE = (process.env.E2E_BASE_URL ?? 'https://localcart-api.vercel.app').replace(/\/$/, '');
const STATE_FILE = process.env.E2E_STATE ?? join(tmpdir(), 'localcart-e2e-state.json');

type Json = Record<string, any>;
interface Actor { token: string; phone: string; nexconnId: string; name: string }
interface State { shop?: Actor & { shopId?: string }; customer?: Actor; lastOrderId?: string }

const load = (): State => (existsSync(STATE_FILE) ? JSON.parse(readFileSync(STATE_FILE, 'utf8')) : {});
const save = (s: State) => writeFileSync(STATE_FILE, JSON.stringify(s, null, 2));

async function api(method: string, path: string, token?: string, body?: unknown, attempt = 0): Promise<Json> {
  try {
    const res = await fetch(`${BASE}/api${path}`, {
      method,
      headers: { ...(token ? { authorization: `Bearer ${token}` } : {}), ...(body ? { 'content-type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined,
      signal: AbortSignal.timeout(90_000),
    });
    const json = (await res.json()) as Json;
    if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${json.error?.message ?? JSON.stringify(json)}`);
    return json;
  } catch (err) {
    // This machine's network is flaky; retry idempotent reads and transport errors.
    const transport = err instanceof TypeError || /fetch failed|ECONNRESET|ENOTFOUND|timeout/i.test(String(err));
    if (transport && attempt < 3) {
      await new Promise((r) => setTimeout(r, 1500 * (attempt + 1)));
      return api(method, path, token, body, attempt + 1);
    }
    throw err;
  }
}

const phone = () => `9${Math.floor(100000000 + Math.random() * 899999999)}`;

async function signup(role: 'customer' | 'shopkeeper', name: string): Promise<Actor> {
  const p = phone();
  const res = await api('POST', '/auth/signup', undefined, { name, phone: p, password: 'e2e-password-123', role });
  return { token: res.token, phone: p, nexconnId: res.user.nexconnId, name };
}

async function sayAs(from: Actor, to: string, text: string) {
  const { sendDirectMessage } = await import('../src/nexconn/client.js');
  // Plain chat text from the second user, as their phone would send it.
  const id = await sendDirectMessage({ fromUserId: from.nexconnId, toUserId: to, messageType: 'RC:TxtMsg', content: { content: text }, pushContent: text });
  console.log('sent chat message', id);
}

async function newest(token: string, scope = 'active'): Promise<Json | undefined> {
  const list = await api('GET', `/orders?scope=${scope}`, token);
  return list.orders[0];
}

const [command, ...args] = process.argv.slice(2);
const state = load();

switch (command) {
  case 'shop:setup': {
    const [lat, lng] = args.map(Number);
    const shop = await signup('shopkeeper', 'E2E Shopkeeper');
    const created = await api('POST', '/shops', shop.token, {
      name: 'Sharma Fresh Mart',
      category: 'kirana',
      phone: shop.phone,
      addressText: 'Test shop for the LocalCart two-user demo',
      lat: (lat ?? 12.9716) + 0.004,
      lng: (lng ?? 77.5946) + 0.003,
      deliveryRadiusKm: 5,
      opensAt: '00:00',
      closesAt: '00:00',
      upiId: 'sharmafresh@okaxis',
    });
    await api('POST', '/shops/mine/items/bulk', shop.token, {
      items: [
        { name: 'Potato (Aloo)', unit: 'kg', pricePaise: 3000, inStock: true },
        { name: 'Onion (Pyaaz)', unit: 'kg', pricePaise: 4000, inStock: true },
        { name: 'Tomato (Tamatar)', unit: 'kg', pricePaise: 3500, inStock: true },
        { name: 'Amul Taaza Milk 500 ml', unit: 'pack', pricePaise: 2800, inStock: true },
        { name: 'Eggs', unit: 'pc', pricePaise: 700, inStock: true },
        { name: 'Aashirvaad Atta 5 kg', unit: 'pack', pricePaise: 26500, inStock: true },
        { name: 'Tata Salt 1 kg', unit: 'pack', pricePaise: 2800, inStock: true },
        { name: 'Amul Butter 100 g', unit: 'pack', pricePaise: 6000, inStock: false },
      ],
    });
    await api('POST', '/chat/token', shop.token, {});
    state.shop = { ...shop, shopId: created.shop.id };
    save(state);
    console.log('shop ready', created.shop.id, created.shop.name);
    break;
  }
  case 'shop:bill': {
    const s = state.shop!;
    const list = await api('GET', '/orders?scope=active', s.token);
    const placed = list.orders.find((o: Json) => o.status === 'placed');
    if (!placed) throw new Error('No placed cart to bill yet');
    const order = (await api('GET', `/orders/${placed.id}`, s.token)).order;
    const lines = order.items.map((i: Json) => ({ itemId: i.id, available: true, quantity: i.quantity, unitPricePaise: i.unitPricePaise ?? 2000 }));
    const billed = await api('POST', `/orders/${order.id}/bill`, s.token, { lines, deliveryFeePaise: 1000, note: 'Delivering in 20 minutes' });
    state.lastOrderId = order.id;
    save(state);
    console.log('billed', billed.order.code, billed.order.totalPaise / 100);
    break;
  }
  case 'shop:advance': {
    const s = state.shop!;
    const order = (await api('GET', '/orders?scope=active', s.token)).orders.find((o: Json) => ['accepted', 'packed', 'out_for_delivery'].includes(o.status));
    if (!order) throw new Error('No accepted order to move');
    const next = { accepted: 'packed', packed: 'out_for_delivery', out_for_delivery: 'delivered' }[order.status as string];
    const res = await api('POST', `/orders/${order.id}/status`, s.token, { status: next });
    console.log('order', res.order.code, 'is now', res.order.status);
    break;
  }
  case 'shop:confirm-payment': {
    const s = state.shop!;
    const order = await newest(s.token, 'all');
    const res = await api('POST', `/orders/${order!.id}/payment`, s.token, { action: 'confirmed' });
    console.log('payment', res.order.paymentStatus);
    break;
  }
  case 'shop:say': {
    const order = await newest(state.shop!.token, 'all');
    const detail = (await api('GET', `/orders/${order!.id}`, state.shop!.token)).order;
    await sayAs(state.shop!, detail.customer.nexconnId, args.join(' '));
    break;
  }
  case 'customer:setup': {
    const [lat, lng] = args.map(Number);
    const customer = await signup('customer', 'E2E Customer');
    await api('PATCH', '/me', customer.token, { lat: lat ?? 12.9716, lng: lng ?? 77.5946, addressText: 'Flat 4B, Test Residency' });
    await api('POST', '/chat/token', customer.token, {});
    state.customer = customer;
    save(state);
    console.log('customer ready', customer.nexconnId);
    break;
  }
  case 'customer:order': {
    const c = state.customer!;
    const me = (await api('GET', '/me', c.token)).user;
    const shops = (await api('GET', `/shops/nearby?lat=${me.lat}&lng=${me.lng}`, c.token)).shops;
    const shop = shops.find((s: Json) => s.openNow);
    if (!shop) throw new Error('No open shop nearby');
    await api('POST', '/chats', c.token, { shopId: shop.id });
    await sayAs(c, shop.ownerNexconnId, args.join(' '));
    const draft = await api('POST', '/orders/draft', c.token, { shopId: shop.id, text: args.join(' ') });
    console.log('cart', draft.order.code, draft.order.items.map((i: Json) => `${i.quantity} ${i.unit} ${i.name}`).join(', '));
    const placed = await api('POST', `/orders/${draft.order.id}/confirm`, c.token);
    state.lastOrderId = placed.order.id;
    save(state);
    console.log('sent to shop', shop.name, placed.order.status);
    break;
  }
  case 'customer:accept': {
    const c = state.customer!;
    const billed = (await api('GET', '/orders?scope=active', c.token)).orders.find((o: Json) => o.status === 'billed');
    if (!billed) throw new Error('No bill waiting');
    const method = args[0] === 'cod' ? 'cod' : 'upi';
    const res = await api('POST', `/orders/${billed.id}/accept`, c.token, { paymentMethod: method });
    console.log('accepted', res.order.code, method, res.order.upiLink ?? '');
    if (method === 'upi') {
      const paid = await api('POST', `/orders/${billed.id}/payment`, c.token, { action: 'marked_paid' });
      console.log('marked paid', paid.order.paymentStatus);
    }
    break;
  }
  case 'customer:say': {
    const order = await newest(state.customer!.token, 'all');
    const detail = (await api('GET', `/orders/${order!.id}`, state.customer!.token)).order;
    await sayAs(state.customer!, detail.shop.nexconnId, args.join(' '));
    break;
  }
  case 'status': {
    const actor = state.customer ?? state.shop;
    const order = await newest(actor!.token, 'all');
    console.log(JSON.stringify(order, null, 2));
    break;
  }
  case 'cleanup': {
    const { db } = await import('../src/db/client.js');
    const { users } = await import('../src/db/schema.js');
    const { inArray } = await import('drizzle-orm');
    const phones = [state.shop?.phone, state.customer?.phone].filter((p): p is string => Boolean(p));
    if (phones.length) await db().delete(users).where(inArray(users.phone, phones));
    save({});
    console.log('removed test users', phones.join(', '));
    break;
  }
  default:
    console.log(readFileSync(new URL(import.meta.url), 'utf8').split('*/')[0]);
}
