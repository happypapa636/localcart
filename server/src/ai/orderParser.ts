import { Type, type Part, type Schema } from '@google/genai';
import { z } from 'zod';
import type { CatalogItem } from '../db/schema.js';
import { normalizeUnit, roundQuantity, type Unit } from '../domain/units.js';
import { generateJson } from './gemini.js';

const SYSTEM = `You turn a customer's order for an Indian neighbourhood shop (kirana) into structured line items matched to that shop's catalog.
The order may be typed or spoken, in English, Hindi or Hinglish (Hindi written in Latin letters).

Rules:
- One output item per product the customer asks for. Ignore greetings and chit-chat. If nothing orderable is asked for, return no items.
- catalogItemId: the id of the catalog item that best matches the product. Use common Hindi names (aloo = potato, pyaaz = onion, tamatar = tomato, doodh = milk, anda = egg, chawal = rice, atta = wheat flour, cheeni = sugar, namak = salt, dahi = curd, dhaniya = coriander, mirchi = chilli, adrak = ginger, lahsun = garlic, tel = oil, dal = lentils, sabun = soap, biskut = biscuits). A brand the customer names (Amul, Tata, Aashirvaad, Parle) should match the item with that brand when it exists. Use null when no catalog item is a reasonable match. Never invent ids.
- quantity is expressed in the matched catalog item's unit. Convert when needed: 500 g of an item sold per kg is 0.5; 1 litre of milk sold as 500 ml packs is 2; if an item is sold per piece, "5 eggs" is 5. For pack, pc, dozen, box and bunch units, round up to a whole number.
- unit: the matched catalog item's unit. For unmatched items, the unit the customer used, one of kg, g, l, ml, pc, dozen, pack, bunch, box.
- If the customer gives no quantity, use 1.
- name: the catalog item's name when matched, otherwise a short clean product name in English.
- requestedText: the customer's own words for this item.
- note: a short note only when something needs the shop's attention (for example a conversion or a brand you could not find), otherwise null.
- transcript: for a voice note, a faithful transcription in the language spoken; for typed text, repeat the text.
- language: "en", "hi", "hinglish" or another ISO 639-1 code.`;

const RESPONSE_SCHEMA: Schema = {
  type: Type.OBJECT,
  properties: {
    transcript: { type: Type.STRING },
    language: { type: Type.STRING },
    items: {
      type: Type.ARRAY,
      items: {
        type: Type.OBJECT,
        properties: {
          requestedText: { type: Type.STRING },
          catalogItemId: { type: Type.STRING, nullable: true },
          name: { type: Type.STRING },
          quantity: { type: Type.NUMBER },
          unit: { type: Type.STRING },
          note: { type: Type.STRING, nullable: true },
        },
        required: ['requestedText', 'catalogItemId', 'name', 'quantity', 'unit'],
        propertyOrdering: ['requestedText', 'catalogItemId', 'name', 'quantity', 'unit', 'note'],
      },
    },
  },
  required: ['transcript', 'language', 'items'],
  propertyOrdering: ['transcript', 'language', 'items'],
};

const Output = z.object({
  transcript: z.string(),
  language: z.string(),
  items: z
    .array(
      z.object({
        requestedText: z.string(),
        catalogItemId: z.string().nullable(),
        name: z.string().min(1),
        quantity: z.number(),
        unit: z.string(),
        note: z.string().nullable().optional(),
      }),
    )
    .max(60),
});

export interface ParsedLine {
  catalogItemId: string | null;
  name: string;
  unit: Unit;
  quantity: number;
  unitPricePaise: number | null;
  available: boolean;
  requestedText: string;
  note: string | null;
}

export interface ParsedOrder {
  transcript: string;
  language: string;
  lines: ParsedLine[];
  model: string;
}

export type OrderInput = { kind: 'text'; text: string } | { kind: 'audio'; mimeType: string; base64: string };

function catalogContext(catalog: CatalogItem[]): string {
  const rows = catalog.map((c) => ({
    id: c.id,
    name: c.name,
    unit: c.unit,
    price: c.pricePaise / 100,
    inStock: c.inStock,
  }));
  return `Shop catalog (JSON, price in rupees per unit):\n${JSON.stringify(rows)}`;
}

/** Map model output onto the real catalog. Prices always come from the database, never the model. */
export function resolveLines(raw: z.infer<typeof Output>['items'], catalog: CatalogItem[]): ParsedLine[] {
  const byId = new Map(catalog.map((c) => [c.id, c]));
  const lines: ParsedLine[] = [];
  for (const item of raw) {
    const match = item.catalogItemId ? byId.get(item.catalogItemId) : undefined;
    if (match) {
      const unit = normalizeUnit(match.unit);
      lines.push({
        catalogItemId: match.id,
        name: match.name,
        unit,
        quantity: roundQuantity(item.quantity, unit),
        unitPricePaise: match.pricePaise,
        available: match.inStock,
        requestedText: item.requestedText.slice(0, 200),
        note: item.note ?? null,
      });
    } else {
      const unit = normalizeUnit(item.unit);
      lines.push({
        catalogItemId: null,
        name: item.name.slice(0, 80),
        unit,
        quantity: roundQuantity(item.quantity, unit),
        unitPricePaise: null,
        available: true,
        requestedText: item.requestedText.slice(0, 200),
        note: item.note ?? null,
      });
    }
  }
  // Merge duplicates of the same catalog item ("1 kg aloo ... aur 1 kg aloo").
  const merged: ParsedLine[] = [];
  for (const line of lines) {
    const existing = line.catalogItemId ? merged.find((m) => m.catalogItemId === line.catalogItemId) : undefined;
    if (existing) existing.quantity = roundQuantity(existing.quantity + line.quantity, existing.unit);
    else merged.push(line);
  }
  return merged;
}

export async function parseOrder(input: OrderInput, catalog: CatalogItem[]): Promise<ParsedOrder> {
  const parts: Part[] = [{ text: catalogContext(catalog) }];
  if (input.kind === 'text') {
    parts.push({ text: `Customer's typed order:\n${input.text}` });
  } else {
    parts.push({ text: "Customer's voice note follows. Transcribe it and extract the order." });
    parts.push({ inlineData: { mimeType: input.mimeType, data: input.base64 } });
  }
  const { data, model } = await generateJson({
    system: SYSTEM,
    parts,
    responseSchema: RESPONSE_SCHEMA,
    validator: Output,
    maxOutputTokens: 4096,
  });
  return { transcript: data.transcript, language: data.language, lines: resolveLines(data.items, catalog), model };
}
