import { readFileSync } from 'node:fs';
import { describe, expect, it } from 'vitest';
import { resolveLines } from '../src/ai/orderParser.js';
import type { CatalogItem } from '../src/db/schema.js';
import { normalizePhone } from '../src/routes/auth.js';
import { sniffAudioType } from '../src/routes/orders.js';
import { sniffImageType } from '../src/routes/uploads.js';
import { cartCardContent, orderMetadata } from '../src/services/cards.js';

const now = new Date();
const item = (id: string, name: string, unit: string, rupees: number, inStock = true): CatalogItem => ({
  id,
  shopId: 's1',
  name,
  unit,
  pricePaise: rupees * 100,
  inStock,
  photoUrl: null,
  archived: false,
  createdAt: now,
  updatedAt: now,
});

const catalog = [
  item('a', 'Potato (Aloo)', 'kg', 30),
  item('b', 'Amul Taaza Milk 500 ml', 'pack', 28),
  item('c', 'Eggs', 'pc', 7, false),
];

describe('resolveLines', () => {
  it('takes prices from the catalog, never from the model', () => {
    const lines = resolveLines(
      [{ requestedText: '2 kg aloo', catalogItemId: 'a', name: 'whatever', quantity: 2, unit: 'kg', note: null }],
      catalog,
    );
    expect(lines[0]).toMatchObject({ catalogItemId: 'a', name: 'Potato (Aloo)', unitPricePaise: 3000, quantity: 2 });
  });

  it('treats unknown ids as unmatched items for the shop to price', () => {
    const lines = resolveLines(
      [{ requestedText: '1 Maggi', catalogItemId: 'made-up', name: 'Maggi', quantity: 1, unit: 'packet', note: null }],
      catalog,
    );
    expect(lines[0]).toMatchObject({ catalogItemId: null, unitPricePaise: null, unit: 'pack', available: true });
  });

  it('marks out-of-stock matches unavailable and merges duplicates', () => {
    const lines = resolveLines(
      [
        { requestedText: '5 eggs', catalogItemId: 'c', name: 'Eggs', quantity: 5, unit: 'pc', note: null },
        { requestedText: '1 kg aloo', catalogItemId: 'a', name: 'Aloo', quantity: 1, unit: 'kg', note: null },
        { requestedText: 'aur 1 kilo aloo', catalogItemId: 'a', name: 'Aloo', quantity: 1, unit: 'kg', note: null },
      ],
      catalog,
    );
    expect(lines).toHaveLength(2);
    expect(lines.find((l) => l.catalogItemId === 'c')?.available).toBe(false);
    expect(lines.find((l) => l.catalogItemId === 'a')?.quantity).toBe(2);
  });

  it('rounds pack quantities up to whole packs', () => {
    const lines = resolveLines(
      [{ requestedText: '1 litre milk', catalogItemId: 'b', name: 'Milk', quantity: 1.6, unit: 'pack', note: null }],
      catalog,
    );
    expect(lines[0]?.quantity).toBe(2);
  });
});

describe('input sniffing', () => {
  it('normalises Indian mobile numbers', () => {
    expect(normalizePhone('+91 98765 43210')).toBe('9876543210');
    expect(normalizePhone('09876543210')).toBe('9876543210');
    expect(normalizePhone('12345')).toBeNull();
    expect(normalizePhone('5876543210')).toBeNull();
  });

  it('detects real audio formats from bytes', () => {
    expect(sniffAudioType(readFileSync('test/fixtures/order-en.aac'))).toBe('audio/aac');
    expect(sniffAudioType(readFileSync('test/fixtures/order-en.m4a'))).toBe('audio/mp4');
    expect(sniffAudioType(readFileSync('test/fixtures/order-en.wav'))).toBe('audio/wav');
    expect(sniffAudioType(new Uint8Array([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12]))).toBeNull();
  });

  it('detects image formats from bytes', () => {
    expect(sniffImageType(new Uint8Array([0xff, 0xd8, 0xff, 0xe0]))).toBe('image/jpeg');
    expect(sniffImageType(new Uint8Array([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a]))).toBe('image/png');
    expect(sniffImageType(new TextEncoder().encode('<svg onload=alert(1)>'))).toBeNull();
  });
});

describe('cards', () => {
  it('encodes unpriced lines with -1 and carries the live state as string metadata', () => {
    const order = {
      id: 'o1',
      code: 'K7Q4MX',
      cartRev: 2,
      source: 'text',
      sourceText: '2 kg aloo',
      status: 'placed',
      paymentStatus: 'none',
      cancelReason: null,
      rating: null,
    } as never;
    const shop = { name: 'Sharma General Store' } as never;
    const items = [
      { position: 0, name: 'Potato (Aloo)', quantity: 2, unit: 'kg', unitPricePaise: 3000, lineTotalPaise: 6000, available: true, catalogItemId: 'a' },
      { position: 1, name: 'Maggi', quantity: 1, unit: 'pack', unitPricePaise: null, lineTotalPaise: 0, available: true, catalogItemId: null },
    ] as never;
    const content = cartCardContent(order, shop, items);
    expect(content.items[1]).toMatchObject({ unitPricePaise: -1, matched: false });
    expect(content.subtotalPaise).toBe(6000);
    expect(content.unpricedCount).toBe(1);
    const meta = orderMetadata(order);
    expect(meta).toMatchObject({ status: 'placed', payment: 'none', rev: '2', rating: '' });
    expect(Object.values(meta).every((v) => typeof v === 'string')).toBe(true);
    expect(JSON.stringify(content).length).toBeLessThan(15 * 1024);
  });
});

describe('catalog list units', () => {
  it('never prices list items per gram or millilitre', async () => {
    const { sellingUnit } = await import('../src/ai/catalogParser.js');
    expect(sellingUnit('ml')).toBe('pack');
    expect(sellingUnit('g')).toBe('pack');
    expect(sellingUnit('kg')).toBe('kg');
    expect(sellingUnit('pc')).toBe('pc');
  });
});
