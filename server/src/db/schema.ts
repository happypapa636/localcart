import {
  bigserial,
  boolean,
  doublePrecision,
  index,
  integer,
  numeric,
  pgTable,
  real,
  text,
  timestamp,
  uniqueIndex,
  uuid,
} from 'drizzle-orm/pg-core';

const createdAt = () => timestamp('created_at', { withTimezone: true }).notNull().defaultNow();
const updatedAt = () => timestamp('updated_at', { withTimezone: true }).notNull().defaultNow();

export const users = pgTable('users', {
  id: uuid('id').primaryKey().defaultRandom(),
  phone: text('phone').notNull().unique(),
  passwordHash: text('password_hash').notNull(),
  name: text('name').notNull(),
  role: text('role', { enum: ['customer', 'shopkeeper'] }).notNull(),
  nexconnId: text('nexconn_id').notNull().unique(),
  nexconnToken: text('nexconn_token'),
  addressText: text('address_text'),
  lat: doublePrecision('lat'),
  lng: doublePrecision('lng'),
  createdAt: createdAt(),
});

export const shops = pgTable(
  'shops',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    ownerId: uuid('owner_id')
      .notNull()
      .unique()
      .references(() => users.id, { onDelete: 'cascade' }),
    name: text('name').notNull(),
    category: text('category').notNull(),
    description: text('description'),
    photoUrl: text('photo_url'),
    phone: text('phone').notNull(),
    addressText: text('address_text').notNull(),
    lat: doublePrecision('lat').notNull(),
    lng: doublePrecision('lng').notNull(),
    deliveryRadiusKm: real('delivery_radius_km').notNull().default(3),
    opensAt: text('opens_at').notNull(),
    closesAt: text('closes_at').notNull(),
    isOpen: boolean('is_open').notNull().default(true),
    upiId: text('upi_id'),
    ratingSum: integer('rating_sum').notNull().default(0),
    ratingCount: integer('rating_count').notNull().default(0),
    createdAt: createdAt(),
    updatedAt: updatedAt(),
  },
  (t) => [index('shops_category_idx').on(t.category)],
);

export const catalogItems = pgTable(
  'catalog_items',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    shopId: uuid('shop_id')
      .notNull()
      .references(() => shops.id, { onDelete: 'cascade' }),
    name: text('name').notNull(),
    unit: text('unit').notNull(),
    pricePaise: integer('price_paise').notNull(),
    inStock: boolean('in_stock').notNull().default(true),
    photoUrl: text('photo_url'),
    archived: boolean('archived').notNull().default(false),
    createdAt: createdAt(),
    updatedAt: updatedAt(),
  },
  (t) => [index('catalog_items_shop_idx').on(t.shopId)],
);

export const chats = pgTable(
  'chats',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    shopId: uuid('shop_id')
      .notNull()
      .references(() => shops.id, { onDelete: 'cascade' }),
    customerId: uuid('customer_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    createdAt: createdAt(),
  },
  (t) => [uniqueIndex('chats_pair_idx').on(t.shopId, t.customerId)],
);

export const ORDER_STATUSES = [
  'draft',
  'placed',
  'billed',
  'accepted',
  'packed',
  'out_for_delivery',
  'delivered',
  'cancelled',
] as const;

export const PAYMENT_STATUSES = ['none', 'pending', 'marked_paid', 'confirmed'] as const;

export const orders = pgTable(
  'orders',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    code: text('code').notNull().unique(),
    shopId: uuid('shop_id')
      .notNull()
      .references(() => shops.id, { onDelete: 'cascade' }),
    customerId: uuid('customer_id')
      .notNull()
      .references(() => users.id, { onDelete: 'cascade' }),
    status: text('status', { enum: ORDER_STATUSES }).notNull().default('draft'),
    source: text('source', { enum: ['text', 'voice', 'reorder'] }).notNull(),
    sourceText: text('source_text'),
    subtotalPaise: integer('subtotal_paise').notNull().default(0),
    deliveryFeePaise: integer('delivery_fee_paise').notNull().default(0),
    totalPaise: integer('total_paise').notNull().default(0),
    billNote: text('bill_note'),
    paymentMethod: text('payment_method', { enum: ['cod', 'upi'] }),
    paymentStatus: text('payment_status', { enum: PAYMENT_STATUSES }).notNull().default('none'),
    cancelReason: text('cancel_reason'),
    cancelledBy: text('cancelled_by', { enum: ['customer', 'shop'] }),
    deliveryLat: doublePrecision('delivery_lat'),
    deliveryLng: doublePrecision('delivery_lng'),
    deliveryAddress: text('delivery_address'),
    cartMessageId: text('cart_message_id'),
    billMessageId: text('bill_message_id'),
    statusMessageId: text('status_message_id'),
    cartRev: integer('cart_rev').notNull().default(1),
    rating: integer('rating'),
    ratingComment: text('rating_comment'),
    ratedAt: timestamp('rated_at', { withTimezone: true }),
    placedAt: timestamp('placed_at', { withTimezone: true }),
    billedAt: timestamp('billed_at', { withTimezone: true }),
    acceptedAt: timestamp('accepted_at', { withTimezone: true }),
    packedAt: timestamp('packed_at', { withTimezone: true }),
    outForDeliveryAt: timestamp('out_for_delivery_at', { withTimezone: true }),
    deliveredAt: timestamp('delivered_at', { withTimezone: true }),
    cancelledAt: timestamp('cancelled_at', { withTimezone: true }),
    createdAt: createdAt(),
    updatedAt: updatedAt(),
  },
  (t) => [
    index('orders_shop_status_idx').on(t.shopId, t.status),
    index('orders_customer_created_idx').on(t.customerId, t.createdAt),
  ],
);

export const orderItems = pgTable(
  'order_items',
  {
    id: uuid('id').primaryKey().defaultRandom(),
    orderId: uuid('order_id')
      .notNull()
      .references(() => orders.id, { onDelete: 'cascade' }),
    catalogItemId: uuid('catalog_item_id').references(() => catalogItems.id, { onDelete: 'set null' }),
    position: integer('position').notNull(),
    name: text('name').notNull(),
    unit: text('unit').notNull(),
    quantity: numeric('quantity', { precision: 10, scale: 3, mode: 'number' }).notNull(),
    unitPricePaise: integer('unit_price_paise'),
    available: boolean('available').notNull().default(true),
    requestedText: text('requested_text'),
    lineTotalPaise: integer('line_total_paise').notNull().default(0),
  },
  (t) => [index('order_items_order_idx').on(t.orderId)],
);

export const rateEvents = pgTable(
  'rate_events',
  {
    id: bigserial('id', { mode: 'number' }).primaryKey(),
    bucket: text('bucket').notNull(),
    createdAt: createdAt(),
  },
  (t) => [index('rate_events_bucket_idx').on(t.bucket, t.createdAt)],
);

export type User = typeof users.$inferSelect;
export type Shop = typeof shops.$inferSelect;
export type CatalogItem = typeof catalogItems.$inferSelect;
export type Order = typeof orders.$inferSelect;
export type OrderItem = typeof orderItems.$inferSelect;
export type OrderStatus = (typeof ORDER_STATUSES)[number];
export type PaymentStatus = (typeof PAYMENT_STATUSES)[number];
