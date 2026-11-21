import { Type, type Schema } from '@google/genai';
import { z } from 'zod';
import { normalizeUnit, type Unit } from '../domain/units.js';
import { generateJson } from './gemini.js';

const SYSTEM = `You help an Indian shopkeeper build their product catalog from a pasted or typed list.
The list may be messy, in English, Hindi or Hinglish, with prices written like "30/kg", "Rs 45", "₹28 per packet" or "40 rupaye kilo".

For every product in the list return:
- name: a clean, short product name in English with brand and pack size if given (for example "Amul Taaza Milk 500 ml", "Potato (Aloo)").
- unit: the selling unit the price applies to, one of kg, g, l, ml, pc, dozen, pack, bunch, box. Packaged goods with a fixed size are "pack".
- price: the price in rupees for one unit, or null if the list gives no price.
Skip lines that are not products. Never invent prices.`;

const RESPONSE_SCHEMA: Schema = {
  type: Type.OBJECT,
  properties: {
    items: {
      type: Type.ARRAY,
      items: {
        type: Type.OBJECT,
        properties: {
          name: { type: Type.STRING },
          unit: { type: Type.STRING },
          price: { type: Type.NUMBER, nullable: true },
        },
        required: ['name', 'unit', 'price'],
        propertyOrdering: ['name', 'unit', 'price'],
      },
    },
  },
  required: ['items'],
};

const Output = z.object({
  items: z
    .array(z.object({ name: z.string().min(1), unit: z.string(), price: z.number().nullable() }))
    .max(200),
});

export interface CatalogDraftItem {
  name: string;
  unit: Unit;
  pricePaise: number | null;
}

export async function parseCatalogList(text: string): Promise<{ items: CatalogDraftItem[]; model: string }> {
  const { data, model } = await generateJson({
    system: SYSTEM,
    parts: [{ text: `Shopkeeper's list:\n${text}` }],
    responseSchema: RESPONSE_SCHEMA,
    validator: Output,
    maxOutputTokens: 8192,
  });
  const items = data.items.map((i) => ({
    name: i.name.trim().slice(0, 80),
    unit: sellingUnit(normalizeUnit(i.unit)),
    pricePaise: i.price != null && i.price > 0 && i.price < 1_000_000 ? Math.round(i.price * 100) : null,
  }));
  return { items, model };
}

/**
 * A shop list price is never per gram or per millilitre ("Amul Taaza 500ml 28" is ₹28 a pack), but the
 * model sometimes copies the pack size into the unit. Treat those as packs.
 */
export function sellingUnit(unit: Unit): Unit {
  return unit === 'g' || unit === 'ml' ? 'pack' : unit;
}
