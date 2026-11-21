import { and, asc, eq, inArray } from 'drizzle-orm';
import { db } from '../db/client.js';
import {
  catalogItems,
  chats,
  orderItems,
  orders,
  shops,
  users,
  type CatalogItem,
  type Order,
  type OrderItem,
  type OrderStatus,
  type Shop,
  type User,
} from '../db/schema.js';
import { canCancel, canEditCart, isValidFulfilmentStep, type Actor } from '../domain/orderState.js';
import { computeTotals, lineTotal } from '../domain/pricing.js';
import { normalizeUnit, roundQuantity } from '../domain/units.js';
import { upiPayLink } from '../domain/upi.js';
import { orderCode } from '../lib/ids.js';
import { ApiError, badRequest, conflict, forbidden, notFound } from '../lib/http.js';
import * as nexconn from '../nexconn/client.js';
import {
  CARD_TYPES,
  billCardContent,
  billPushText,
  cartCardContent,
  cartPushText,
  orderMetadata,
  statusCardContent,
  statusPushText,
} from './cards.js';

export interface OrderContext {
  order: Order;
  items: OrderItem[];
  shop: Shop;
  owner: User;
  customer: User;
  actor: Actor;
}

export interface LineInput {
  catalogItemId: string | null;
  name: string;
  unit: string;
  quantity: number;
  requestedText?: string | null;
  unitPricePaise?: number | null;
  available?: boolean;
}

const MAX_LINES = 40;

async function loadItems(orderId: string) {
  return db().select().from(orderItems).where(eq(orderItems.orderId, orderId)).orderBy(asc(orderItems.position));
}

/** Loads an order with everything needed to act on it, enforcing that `user` is its customer or shop owner. */
export async function loadOrderContext(orderId: string, user: User): Promise<OrderContext> {
  const [order] = await db().select().from(orders).where(eq(orders.id, orderId)).limit(1);
  if (!order) throw notFound('Order not found.');
  const [shop] = await db().select().from(shops).where(eq(shops.id, order.shopId)).limit(1);
  if (!shop) throw notFound('Order not found.');
  let actor: Actor;
  if (order.customerId === user.id) actor = 'customer';
  else if (shop.ownerId === user.id) actor = 'shop';
  else throw notFound('Order not found.');
  const people = await db()
    .select()
    .from(users)
    .where(inArray(users.id, [order.customerId, shop.ownerId]));
  const owner = people.find((p) => p.id === shop.ownerId);
  const customer = people.find((p) => p.id === order.customerId);
  if (!owner || !customer) throw notFound('Order not found.');
  return { order, items: await loadItems(order.id), shop, owner, customer, actor };
}

export function orderView(ctx: OrderContext) {
  const { order, items, shop, owner, customer, actor } = ctx;
  const totals = computeTotals(items, order.deliveryFeePaise);
  const showUpi =
    actor === 'customer' &&
    shop.upiId &&
    order.totalPaise > 0 &&
    ['billed', 'accepted', 'packed', 'out_for_delivery', 'delivered'].includes(order.status);
  return {
    id: order.id,
    code: order.code,
    status: order.status,
    source: order.source,
    sourceText: order.sourceText,
    viewerRole: actor,
    shop: {
      id: shop.id,
      name: shop.name,
      photoUrl: shop.photoUrl,
      phone: shop.phone,
      upiId: shop.upiId,
      nexconnId: owner.nexconnId,
    },
    customer: {
      id: customer.id,
      name: customer.name,
      nexconnId: customer.nexconnId,
      // The shop needs a phone number and address to deliver; customers already know their own.
      phone: actor === 'shop' ? customer.phone : null,
      addressText: actor === 'shop' ? customer.addressText : null,
    },
    items: items.map((i) => ({
      id: i.id,
      catalogItemId: i.catalogItemId,
      name: i.name,
      unit: i.unit,
      quantity: i.quantity,
      unitPricePaise: i.unitPricePaise,
      available: i.available,
      requestedText: i.requestedText,
      lineTotalPaise: i.lineTotalPaise,
    })),
    subtotalPaise: order.status === 'draft' || order.status === 'placed' ? totals.subtotalPaise : order.subtotalPaise,
    deliveryFeePaise: order.deliveryFeePaise,
    totalPaise: order.status === 'draft' || order.status === 'placed' ? totals.totalPaise : order.totalPaise,
    unpricedCount: totals.unpricedCount,
    billNote: order.billNote,
    paymentMethod: order.paymentMethod,
    paymentStatus: order.paymentStatus,
    cancelReason: order.cancelReason,
    cancelledBy: order.cancelledBy,
    delivery:
      order.deliveryLat != null && order.deliveryLng != null
        ? { lat: order.deliveryLat, lng: order.deliveryLng, address: order.deliveryAddress }
        : null,
    cartMessageId: order.cartMessageId,
    billMessageId: order.billMessageId,
    statusMessageId: order.statusMessageId,
    cartRev: order.cartRev,
    rating: order.rating,
    ratingComment: order.ratingComment,
    upiLink: showUpi
      ? upiPayLink({ upiId: shop.upiId!, payeeName: shop.name, amountPaise: order.totalPaise, orderCode: order.code })
      : null,
    createdAt: order.createdAt,
    placedAt: order.placedAt,
    billedAt: order.billedAt,
    acceptedAt: order.acceptedAt,
    packedAt: order.packedAt,
    outForDeliveryAt: order.outForDeliveryAt,
    deliveredAt: order.deliveredAt,
    cancelledAt: order.cancelledAt,
    updatedAt: order.updatedAt,
  };
}

/** Validates lines against the shop catalog; prices for catalog items always come from the database. */
async function resolveCartLines(shopId: string, lines: LineInput[]) {
  if (lines.length === 0) throw badRequest('Add at least one item to the cart.');
  if (lines.length > MAX_LINES) throw badRequest(`A cart can have at most ${MAX_LINES} items.`);
  const ids = lines.map((l) => l.catalogItemId).filter((x): x is string => Boolean(x));
  const catalog: CatalogItem[] = ids.length
    ? await db()
        .select()
        .from(catalogItems)
        .where(and(eq(catalogItems.shopId, shopId), inArray(catalogItems.id, ids)))
    : [];
  const byId = new Map(catalog.map((c) => [c.id, c]));
  return lines.map((l, position) => {
    const match = l.catalogItemId ? byId.get(l.catalogItemId) : undefined;
    if (l.catalogItemId && !match) throw badRequest('One of the items is no longer in this shop.');
    const unit = normalizeUnit(match?.unit ?? l.unit);
    const quantity = roundQuantity(l.quantity, unit);
    const unitPricePaise = match ? match.pricePaise : null;
    const available = match ? match.inStock : true;
    return {
      catalogItemId: match?.id ?? null,
      position,
      name: (match?.name ?? l.name).trim().slice(0, 80),
      unit,
      quantity,
      unitPricePaise,
      available,
      requestedText: l.requestedText?.slice(0, 200) ?? null,
      lineTotalPaise: lineTotal({ quantity, unitPricePaise, available }),
    };
  });
}

async function ensureChat(shopId: string, customerId: string) {
  await db().insert(chats).values({ shopId, customerId }).onConflictDoNothing();
}

/** Best-effort live update of card state; the order row stays the source of truth if this fails. */
async function pushMetadata(order: Order, ctx: Pick<OrderContext, 'owner' | 'customer'>, cards: ('cart' | 'bill' | 'status')[]) {
  const meta = orderMetadata(order);
  const jobs: Promise<unknown>[] = [];
  for (const card of cards) {
    if (card === 'cart' && order.cartMessageId) {
      jobs.push(
        nexconn.setMessageMetadata({
          messageId: order.cartMessageId,
          operatorUserId: ctx.customer.nexconnId,
          peerUserId: ctx.owner.nexconnId,
          metadata: meta,
        }),
      );
    }
    const shopCardId = card === 'bill' ? order.billMessageId : card === 'status' ? order.statusMessageId : null;
    if (shopCardId) {
      jobs.push(
        nexconn.setMessageMetadata({
          messageId: shopCardId,
          operatorUserId: ctx.owner.nexconnId,
          peerUserId: ctx.customer.nexconnId,
          metadata: meta,
        }),
      );
    }
  }
  const results = await Promise.allSettled(jobs);
  for (const r of results) if (r.status === 'rejected') console.error('metadata update failed', r.reason);
}

export interface CreateDraftInput {
  shop: Shop;
  customer: User;
  source: 'text' | 'voice' | 'reorder';
  sourceText: string | null;
  lines: LineInput[];
}

/** Creates a draft order and posts its Cart card into the customer–shop direct channel. */
export async function createDraftOrder(input: CreateDraftInput): Promise<OrderContext> {
  const { shop, customer } = input;
  const [owner] = await db().select().from(users).where(eq(users.id, shop.ownerId)).limit(1);
  if (!owner) throw notFound('Shop not found.');
  const resolved = await resolveCartLines(shop.id, input.lines);
  const totals = computeTotals(resolved);
  await ensureChat(shop.id, customer.id);

  const [order] = await db()
    .insert(orders)
    .values({
      code: orderCode(),
      shopId: shop.id,
      customerId: customer.id,
      status: 'draft',
      source: input.source,
      sourceText: input.sourceText?.slice(0, 2000) ?? null,
      subtotalPaise: totals.subtotalPaise,
      totalPaise: totals.totalPaise,
      deliveryLat: customer.lat,
      deliveryLng: customer.lng,
      deliveryAddress: customer.addressText,
    })
    .returning();
  if (!order) throw new ApiError(500, 'internal', 'Could not create the order.');
  const items = await db()
    .insert(orderItems)
    .values(resolved.map((l) => ({ ...l, orderId: order.id })))
    .returning();

  let messageId: string;
  try {
    messageId = await nexconn.sendDirectMessage({
      fromUserId: customer.nexconnId,
      toUserId: owner.nexconnId,
      messageType: CARD_TYPES.cart,
      content: cartCardContent(order, shop, items),
      pushContent: cartPushText(order, items),
      metadata: orderMetadata(order),
    });
  } catch (err) {
    await db().delete(orders).where(eq(orders.id, order.id));
    console.error('cart card send failed', err);
    throw new ApiError(502, 'chat_unavailable', 'Could not post the cart in chat. Check your connection and try again.');
  }
  const [saved] = await db()
    .update(orders)
    .set({ cartMessageId: messageId, updatedAt: new Date() })
    .where(eq(orders.id, order.id))
    .returning();
  return { order: saved ?? order, items, shop, owner, customer, actor: 'customer' };
}

function requireActor(ctx: OrderContext, actor: Actor) {
  if (ctx.actor !== actor) {
    throw forbidden(actor === 'shop' ? 'Only the shop can do this.' : 'Only the customer can do this.');
  }
}

/** Conditional status update: fails with 409 if someone else moved the order first. */
async function transition(order: Order, expected: OrderStatus[], patch: Partial<Order>): Promise<Order> {
  const [updated] = await db()
    .update(orders)
    .set({ ...patch, updatedAt: new Date() })
    .where(and(eq(orders.id, order.id), inArray(orders.status, expected)))
    .returning();
  if (!updated) throw conflict('This order changed in the meantime. Refresh and try again.', 'stale_order');
  return updated;
}

export async function editCart(ctx: OrderContext, lines: LineInput[]): Promise<OrderContext> {
  requireActor(ctx, 'customer');
  if (!canEditCart(ctx.order.status)) throw conflict('The shop has already billed this cart.', 'not_editable');
  const resolved = await resolveCartLines(ctx.shop.id, lines);
  const totals = computeTotals(resolved);
  const order = await transition(ctx.order, ['draft', 'placed'], {
    cartRev: ctx.order.cartRev + 1,
    subtotalPaise: totals.subtotalPaise,
    totalPaise: totals.totalPaise,
  });
  await db().delete(orderItems).where(eq(orderItems.orderId, order.id));
  const items = await db()
    .insert(orderItems)
    .values(resolved.map((l) => ({ ...l, orderId: order.id })))
    .returning();
  const next = { ...ctx, order, items };
  if (order.cartMessageId) {
    try {
      await nexconn.updateDirectMessage({
        fromUserId: ctx.customer.nexconnId,
        toUserId: ctx.owner.nexconnId,
        messageId: order.cartMessageId,
        content: cartCardContent(order, ctx.shop, items),
      });
    } catch (err) {
      console.error('cart card update failed', err);
    }
  }
  await pushMetadata(order, ctx, ['cart']);
  return next;
}

export async function confirmCart(ctx: OrderContext): Promise<OrderContext> {
  requireActor(ctx, 'customer');
  if (ctx.order.status !== 'draft') throw conflict('This cart was already sent.', 'already_placed');
  if (ctx.items.filter((i) => i.available).length === 0) {
    throw badRequest('Every item in this cart is out of stock. Edit the cart first.');
  }
  const order = await transition(ctx.order, ['draft'], { status: 'placed', placedAt: new Date() });
  await pushMetadata(order, ctx, ['cart']);
  return { ...ctx, order };
}

export interface BillLineInput {
  itemId: string;
  available: boolean;
  quantity: number;
  unitPricePaise: number;
}

export async function sendBill(
  ctx: OrderContext,
  input: { lines: BillLineInput[]; deliveryFeePaise: number; note: string | null },
): Promise<OrderContext> {
  requireActor(ctx, 'shop');
  if (ctx.order.status !== 'placed') {
    throw conflict(
      ctx.order.status === 'draft' ? 'The customer has not sent this cart yet.' : 'This cart was already billed.',
      'not_billable',
    );
  }
  const byId = new Map(input.lines.map((l) => [l.itemId, l]));
  const updatedLines = ctx.items.map((item) => {
    const edit = byId.get(item.id);
    if (!edit) throw badRequest('The bill must include every cart item.');
    const unit = normalizeUnit(item.unit);
    const quantity = roundQuantity(edit.quantity, unit);
    const unitPricePaise = Math.round(edit.unitPricePaise);
    const available = edit.available;
    if (available && unitPricePaise <= 0) throw badRequest(`Set a price for ${item.name} or mark it unavailable.`);
    return { ...item, quantity, unitPricePaise, available, lineTotalPaise: lineTotal({ quantity, unitPricePaise, available }) };
  });
  const totals = computeTotals(updatedLines, input.deliveryFeePaise);
  if (totals.subtotalPaise <= 0) throw badRequest('At least one item must be available to send a bill.');

  // Persist the bill first, then post the card; the conditional update guards against double billing.
  const order = await transition(ctx.order, ['placed'], {
    status: 'billed',
    billedAt: new Date(),
    subtotalPaise: totals.subtotalPaise,
    deliveryFeePaise: totals.deliveryFeePaise,
    totalPaise: totals.totalPaise,
    billNote: input.note?.slice(0, 300) || null,
  });
  const lineUpdates = updatedLines.map((l) =>
    db()
      .update(orderItems)
      .set({
        quantity: l.quantity,
        unitPricePaise: l.unitPricePaise,
        available: l.available,
        lineTotalPaise: l.lineTotalPaise,
      })
      .where(eq(orderItems.id, l.id)),
  );
  const [firstUpdate, ...restUpdates] = lineUpdates;
  if (firstUpdate) await db().batch([firstUpdate, ...restUpdates]);
  const items = await loadItems(order.id);

  let billMessageId: string;
  try {
    billMessageId = await nexconn.sendDirectMessage({
      fromUserId: ctx.owner.nexconnId,
      toUserId: ctx.customer.nexconnId,
      messageType: CARD_TYPES.bill,
      content: billCardContent(order, ctx.shop, items),
      pushContent: billPushText(order),
      metadata: orderMetadata(order),
    });
  } catch (err) {
    console.error('bill card send failed', err);
    await db()
      .update(orders)
      .set({ status: 'placed', billedAt: null, updatedAt: new Date() })
      .where(eq(orders.id, order.id));
    throw new ApiError(502, 'chat_unavailable', 'Could not post the bill in chat. Try again.');
  }
  const [saved] = await db()
    .update(orders)
    .set({ billMessageId, updatedAt: new Date() })
    .where(eq(orders.id, order.id))
    .returning();
  const finalOrder = saved ?? order;
  await pushMetadata(finalOrder, ctx, ['cart']);
  return { ...ctx, order: finalOrder, items };
}

export async function acceptBill(ctx: OrderContext, paymentMethod: 'cod' | 'upi'): Promise<OrderContext> {
  requireActor(ctx, 'customer');
  if (ctx.order.status !== 'billed') throw conflict('There is no bill waiting for you on this order.', 'not_billed');
  if (paymentMethod === 'upi' && !ctx.shop.upiId) throw badRequest('This shop has not added a UPI ID. Choose cash on delivery.');
  const order = await transition(ctx.order, ['billed'], {
    status: 'accepted',
    acceptedAt: new Date(),
    paymentMethod,
    paymentStatus: 'pending',
  });
  try {
    const statusMessageId = await nexconn.sendDirectMessage({
      fromUserId: ctx.owner.nexconnId,
      toUserId: ctx.customer.nexconnId,
      messageType: CARD_TYPES.status,
      content: statusCardContent(order, ctx.shop, ctx.items.filter((i) => i.available).length),
      pushContent: statusPushText(order),
      metadata: orderMetadata(order),
    });
    const [saved] = await db()
      .update(orders)
      .set({ statusMessageId, updatedAt: new Date() })
      .where(eq(orders.id, order.id))
      .returning();
    const finalOrder = saved ?? order;
    await pushMetadata(finalOrder, ctx, ['bill']);
    return { ...ctx, order: finalOrder };
  } catch (err) {
    console.error('status card send failed', err);
    await pushMetadata(order, ctx, ['bill']);
    return { ...ctx, order };
  }
}

export async function updatePayment(ctx: OrderContext, action: 'marked_paid' | 'confirmed'): Promise<OrderContext> {
  const { order: current } = ctx;
  if (!current.paymentMethod || current.status === 'cancelled') throw conflict('This order has no payment to update.');
  if (action === 'marked_paid') {
    requireActor(ctx, 'customer');
    if (current.paymentMethod !== 'upi') throw badRequest('Only UPI payments can be marked as paid.');
    if (current.paymentStatus !== 'pending') throw conflict('Payment was already marked.');
  } else {
    requireActor(ctx, 'shop');
    if (current.paymentStatus === 'confirmed') throw conflict('Payment is already confirmed.');
  }
  const [order] = await db()
    .update(orders)
    .set({ paymentStatus: action, updatedAt: new Date() })
    .where(and(eq(orders.id, current.id), eq(orders.paymentStatus, current.paymentStatus)))
    .returning();
  if (!order) throw conflict('Payment changed in the meantime. Refresh and try again.', 'stale_order');
  await pushMetadata(order, ctx, ['status']);
  return { ...ctx, order };
}

const STATUS_TIMESTAMP: Partial<Record<OrderStatus, keyof Order>> = {
  packed: 'packedAt',
  out_for_delivery: 'outForDeliveryAt',
  delivered: 'deliveredAt',
};

export async function advanceFulfilment(ctx: OrderContext, to: OrderStatus): Promise<OrderContext> {
  requireActor(ctx, 'shop');
  if (!isValidFulfilmentStep(ctx.order.status, to)) {
    throw conflict(`Cannot move this order from ${ctx.order.status} to ${to}.`, 'invalid_transition');
  }
  const stamp = STATUS_TIMESTAMP[to];
  const order = await transition(ctx.order, [ctx.order.status], {
    status: to,
    ...(stamp ? { [stamp]: new Date() } : {}),
  });
  await pushMetadata(order, ctx, ['status']);
  return { ...ctx, order };
}

export async function cancelOrder(ctx: OrderContext, reason: string): Promise<OrderContext> {
  if (!canCancel(ctx.order.status, ctx.actor)) {
    throw conflict('This order can no longer be cancelled.', 'not_cancellable');
  }
  const order = await transition(ctx.order, [ctx.order.status], {
    status: 'cancelled',
    cancelledAt: new Date(),
    cancelledBy: ctx.actor,
    cancelReason: reason.slice(0, 200),
  });
  const cards: ('cart' | 'bill' | 'status')[] = order.statusMessageId
    ? ['status']
    : order.billMessageId
      ? ['bill']
      : ['cart'];
  await pushMetadata(order, ctx, cards);
  return { ...ctx, order };
}

export async function rateOrder(ctx: OrderContext, stars: number, comment: string | null): Promise<OrderContext> {
  requireActor(ctx, 'customer');
  if (ctx.order.status !== 'delivered') throw conflict('You can rate an order after it is delivered.');
  if (ctx.order.rating != null) throw conflict('You already rated this order.');
  const [order] = await db()
    .update(orders)
    .set({ rating: stars, ratingComment: comment?.slice(0, 300) || null, ratedAt: new Date(), updatedAt: new Date() })
    .where(and(eq(orders.id, ctx.order.id), eq(orders.status, 'delivered')))
    .returning();
  if (!order) throw conflict('This order changed in the meantime.');
  const [shop] = await db()
    .update(shops)
    .set({ ratingSum: ctx.shop.ratingSum + stars, ratingCount: ctx.shop.ratingCount + 1 })
    .where(eq(shops.id, ctx.shop.id))
    .returning();
  await pushMetadata(order, ctx, ['status']);
  return { ...ctx, order, shop: shop ?? ctx.shop };
}

export async function setDeliveryLocation(
  ctx: OrderContext,
  loc: { lat: number; lng: number; address: string | null },
): Promise<OrderContext> {
  requireActor(ctx, 'customer');
  if (['delivered', 'cancelled'].includes(ctx.order.status)) throw conflict('This order is closed.');
  const [order] = await db()
    .update(orders)
    .set({ deliveryLat: loc.lat, deliveryLng: loc.lng, deliveryAddress: loc.address, updatedAt: new Date() })
    .where(eq(orders.id, ctx.order.id))
    .returning();
  return { ...ctx, order: order ?? ctx.order };
}
