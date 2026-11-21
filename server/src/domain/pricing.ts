export interface PricedLine {
  quantity: number;
  unitPricePaise: number | null;
  available: boolean;
}

/** Line total in paise. Unpriced or unavailable lines contribute nothing. */
export function lineTotal(line: PricedLine): number {
  if (!line.available || line.unitPricePaise == null) return 0;
  return Math.round(line.quantity * line.unitPricePaise);
}

export interface Totals {
  subtotalPaise: number;
  deliveryFeePaise: number;
  totalPaise: number;
  unpricedCount: number;
}

export function computeTotals(lines: PricedLine[], deliveryFeePaise = 0): Totals {
  let subtotal = 0;
  let unpriced = 0;
  for (const line of lines) {
    if (!line.available) continue;
    if (line.unitPricePaise == null) {
      unpriced++;
      continue;
    }
    subtotal += lineTotal(line);
  }
  const fee = Math.max(0, Math.round(deliveryFeePaise));
  return { subtotalPaise: subtotal, deliveryFeePaise: fee, totalPaise: subtotal + fee, unpricedCount: unpriced };
}

/** "₹1,234.50" style formatting with Indian digit grouping; whole rupees drop the decimals. */
export function formatRupees(paise: number): string {
  const rupees = paise / 100;
  const hasFraction = paise % 100 !== 0;
  return `₹${rupees.toLocaleString('en-IN', {
    minimumFractionDigits: hasFraction ? 2 : 0,
    maximumFractionDigits: 2,
  })}`;
}
