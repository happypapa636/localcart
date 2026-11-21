/**
 * Selling units a shop can price an item by. Prices are always "per one unit",
 * e.g. aloo at ₹30 per kg, Amul Taaza at ₹28 per pack (500 ml).
 */
export const UNITS = ['kg', 'g', 'l', 'ml', 'pc', 'dozen', 'pack', 'bunch', 'box'] as const;
export type Unit = (typeof UNITS)[number];

export const UNIT_LABELS: Record<Unit, string> = {
  kg: 'kg',
  g: 'g',
  l: 'litre',
  ml: 'ml',
  pc: 'piece',
  dozen: 'dozen',
  pack: 'pack',
  bunch: 'bunch',
  box: 'box',
};

const ALIASES: Record<string, Unit> = {
  kg: 'kg',
  kgs: 'kg',
  kilo: 'kg',
  kilos: 'kg',
  kilogram: 'kg',
  kilograms: 'kg',
  g: 'g',
  gm: 'g',
  gms: 'g',
  gram: 'g',
  grams: 'g',
  l: 'l',
  ltr: 'l',
  litre: 'l',
  litres: 'l',
  liter: 'l',
  liters: 'l',
  ml: 'ml',
  pc: 'pc',
  pcs: 'pc',
  piece: 'pc',
  pieces: 'pc',
  nos: 'pc',
  no: 'pc',
  each: 'pc',
  unit: 'pc',
  dozen: 'dozen',
  dz: 'dozen',
  pack: 'pack',
  packet: 'pack',
  packets: 'pack',
  pkt: 'pack',
  bottle: 'pack',
  bunch: 'bunch',
  bundle: 'bunch',
  box: 'box',
};

export function normalizeUnit(raw: string | null | undefined): Unit {
  if (!raw) return 'pc';
  const key = raw.trim().toLowerCase().replace(/\.$/, '');
  return ALIASES[key] ?? 'pc';
}

export function isUnit(raw: string): raw is Unit {
  return (UNITS as readonly string[]).includes(raw);
}

/** Smallest sensible step for a quantity in this unit. */
export function quantityStep(unit: Unit): number {
  switch (unit) {
    case 'kg':
    case 'l':
      return 0.25;
    case 'g':
    case 'ml':
      return 50;
    default:
      return 1;
  }
}

/** Round a requested quantity to the unit's step, never below one step. */
export function roundQuantity(quantity: number, unit: Unit): number {
  const step = quantityStep(unit);
  if (!Number.isFinite(quantity) || quantity <= 0) return step;
  const rounded = Math.round(quantity / step) * step;
  return Math.max(step, Number(rounded.toFixed(3)));
}
