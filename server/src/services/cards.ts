import type { Order, OrderItem, Shop } from '../db/schema.js';
import { computeTotals, formatRupees } from '../domain/pricing.js';

/** Custom Nexconn message types. Registered with identical names in the Android app. */
export const CARD_TYPES = {
  cart: 'lcr:cart',
  bill: 'lcr:bill',
  status: 'lcr:status',
} as const;

function itemsPayload(items: OrderItem[]) {
  return [...items]
    .sort((a, b) => a.position - b.position)
    .map((i) => ({
      name: i.name,
      quantity: i.quantity,
      unit: i.unit,
      unitPricePaise: i.unitPricePaise ?? -1,
      lineTotalPaise: i.lineTotalPaise,
      available: i.available,
      matched: i.catalogItemId != null,
    }));
}

export function cartCardContent(order: Order, shop: Shop, items: OrderItem[]) {
  const totals = computeTotals(items);
  return {
    v: 1,
    orderId: order.id,
    code: order.code,
    shopName: shop.name,
    rev: order.cartRev,
    source: order.source,
    sourceText: (order.sourceText ?? '').slice(0, 280),
    items: itemsPayload(items),
    subtotalPaise: totals.subtotalPaise,
    unpricedCount: totals.unpricedCount,
  };
}

export function billCardContent(order: Order, shop: Shop, items: OrderItem[]) {
  return {
    v: 1,
    orderId: order.id,
    code: order.code,
    shopName: shop.name,
    items: itemsPayload(items),
    subtotalPaise: order.subtotalPaise,
    deliveryFeePaise: order.deliveryFeePaise,
    totalPaise: order.totalPaise,
    note: order.billNote ?? '',
    upiId: shop.upiId ?? '',
  };
}

export function statusCardContent(order: Order, shop: Shop, itemCount: number) {
  return {
    v: 1,
    orderId: order.id,
    code: order.code,
    shopName: shop.name,
    totalPaise: order.totalPaise,
    paymentMethod: order.paymentMethod ?? '',
    itemCount,
  };
}

/**
 * Live state attached to card messages as Nexconn message metadata. Every card renders from the same
 * snapshot, so a single server-side update moves the card in place on both phones.
 */
export function orderMetadata(order: Order): Record<string, string> {
  return {
    status: order.status,
    payment: order.paymentStatus,
    rev: String(order.cartRev),
    reason: (order.cancelReason ?? '').slice(0, 200),
    rating: order.rating != null ? String(order.rating) : '',
    at: new Date().toISOString(),
  };
}

export function cartPushText(order: Order, items: OrderItem[]) {
  return `New cart ${order.code}: ${items.length} item${items.length === 1 ? '' : 's'}`;
}

export function billPushText(order: Order) {
  return `Bill for ${order.code}: ${formatRupees(order.totalPaise)}`;
}

export function statusPushText(order: Order) {
  return `Order ${order.code} accepted`;
}
