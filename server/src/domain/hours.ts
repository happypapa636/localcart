/** Opening hours are stored as "HH:MM" in India Standard Time (UTC+05:30, no DST). */
const IST_OFFSET_MINUTES = 330;

export const HHMM = /^([01]\d|2[0-3]):[0-5]\d$/;

export function toMinutes(hhmm: string): number {
  const [h, m] = hhmm.split(':').map(Number);
  return (h ?? 0) * 60 + (m ?? 0);
}

export function istMinutesNow(now: Date = new Date()): number {
  const utc = now.getUTCHours() * 60 + now.getUTCMinutes();
  return (utc + IST_OFFSET_MINUTES) % (24 * 60);
}

/** Whether the clock is inside opening hours. Handles hours that run past midnight (e.g. 18:00–02:00). */
export function withinHours(opensAt: string, closesAt: string, now: Date = new Date()): boolean {
  const open = toMinutes(opensAt);
  const close = toMinutes(closesAt);
  const t = istMinutesNow(now);
  if (open === close) return true; // open all day
  if (open < close) return t >= open && t < close;
  return t >= open || t < close;
}

/** A shop takes orders when the owner has it switched on and it is inside opening hours. */
export function isOpenNow(shop: { isOpen: boolean; opensAt: string; closesAt: string }, now = new Date()): boolean {
  return shop.isOpen && withinHours(shop.opensAt, shop.closesAt, now);
}

/** Start of "today" in IST, as a UTC Date, for daily sales figures. */
export function startOfIstDay(now: Date = new Date()): Date {
  const istNow = new Date(now.getTime() + IST_OFFSET_MINUTES * 60_000);
  istNow.setUTCHours(0, 0, 0, 0);
  return new Date(istNow.getTime() - IST_OFFSET_MINUTES * 60_000);
}
