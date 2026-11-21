/**
 * Live check of the Gemini order parser with text and real audio files (AAC, M4A, WAV).
 * Usage: npx tsx scripts/ai-smoke.ts
 */
import { readFileSync } from 'node:fs';
import { config } from 'dotenv';
import type { CatalogItem } from '../src/db/schema.js';

config({ path: '.env.local' });

const { parseOrder } = await import('../src/ai/orderParser.js');
const { parseCatalogList } = await import('../src/ai/catalogParser.js');

const now = new Date();
const item = (id: string, name: string, unit: string, price: number): CatalogItem => ({
  id,
  shopId: 'shop',
  name,
  unit,
  pricePaise: price * 100,
  inStock: true,
  photoUrl: null,
  archived: false,
  createdAt: now,
  updatedAt: now,
});

const catalog = [
  item('11111111-1111-1111-1111-111111111111', 'Potato (Aloo)', 'kg', 30),
  item('22222222-2222-2222-2222-222222222222', 'Amul Taaza Milk 500 ml', 'pack', 28),
  item('33333333-3333-3333-3333-333333333333', 'Eggs', 'pc', 7),
  item('44444444-4444-4444-4444-444444444444', 'Tata Salt 1 kg', 'pack', 28),
  item('55555555-5555-5555-5555-555555555555', 'Onion (Pyaaz)', 'kg', 40),
];

const t0 = Date.now();
const text = await parseOrder({ kind: 'text', text: '2 kg aloo, 1 litre Amul milk, 5 eggs, aur aadha kilo pyaaz, 1 Maggi' }, catalog);
console.log(`text (${Date.now() - t0} ms, ${text.model})`, JSON.stringify(text.lines, null, 1));

for (const [file, mime] of [
  ['test/fixtures/order-en.aac', 'audio/aac'],
  ['test/fixtures/order-en.m4a', 'audio/mp4'],
  ['test/fixtures/order-en.wav', 'audio/wav'],
] as const) {
  const t = Date.now();
  try {
    const res = await parseOrder({ kind: 'audio', mimeType: mime, base64: readFileSync(file).toString('base64') }, catalog);
    console.log(`${mime} (${Date.now() - t} ms, ${res.model}) transcript="${res.transcript}"`);
    console.log(res.lines.map((l) => `${l.quantity} ${l.unit} ${l.name} ${l.catalogItemId ? 'matched' : 'unmatched'}`).join(' | '));
  } catch (err) {
    console.log(`${mime} FAILED`, err instanceof Error ? err.message : err);
  }
}

const t1 = Date.now();
const cat = await parseCatalogList('Aloo 30/kg\nPyaaz 40 rs kilo\nAmul taaza 500ml - 28\nAnde 7 each\nTata namak 1kg 28\nParle G 10 rupaye');
console.log(`catalog (${Date.now() - t1} ms, ${cat.model})`, JSON.stringify(cat.items));
