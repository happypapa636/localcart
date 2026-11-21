import type { OrderStatus } from '../db/schema.js';

export type Actor = 'customer' | 'shop';

/** Statuses after which the order can no longer change (other than rating). */
export const FINAL_STATUSES: readonly OrderStatus[] = ['delivered', 'cancelled'];

/** Delivery progress a shop moves through after the customer accepts the bill. */
export const FULFILMENT_FLOW: readonly OrderStatus[] = ['accepted', 'packed', 'out_for_delivery', 'delivered'];

const CUSTOMER_CANCELLABLE: readonly OrderStatus[] = ['draft', 'placed', 'billed', 'accepted'];
const SHOP_CANCELLABLE: readonly OrderStatus[] = ['placed', 'billed', 'accepted', 'packed', 'out_for_delivery'];

export function canEditCart(status: OrderStatus): boolean {
  return status === 'draft' || status === 'placed';
}

export function canCancel(status: OrderStatus, actor: Actor): boolean {
  return (actor === 'customer' ? CUSTOMER_CANCELLABLE : SHOP_CANCELLABLE).includes(status);
}

/** The single next fulfilment step the shop may take, or null. */
export function nextFulfilmentStatus(status: OrderStatus): OrderStatus | null {
  const i = FULFILMENT_FLOW.indexOf(status);
  if (i < 0 || i === FULFILMENT_FLOW.length - 1) return null;
  return FULFILMENT_FLOW[i + 1] ?? null;
}

export function isValidFulfilmentStep(from: OrderStatus, to: OrderStatus): boolean {
  return nextFulfilmentStatus(from) === to;
}

export const STATUS_LABELS: Record<OrderStatus, string> = {
  draft: 'Draft cart',
  placed: 'Cart sent',
  billed: 'Bill sent',
  accepted: 'Accepted',
  packed: 'Packed',
  out_for_delivery: 'Out for delivery',
  delivered: 'Delivered',
  cancelled: 'Cancelled',
};

/** Orders that still need the shop's attention, used by the dashboard. */
export const ACTIVE_STATUSES: readonly OrderStatus[] = ['placed', 'billed', 'accepted', 'packed', 'out_for_delivery'];
