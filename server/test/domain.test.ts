import { describe, expect, it } from 'vitest';
import { haversineKm, boundingBox } from '../src/domain/geo.js';
import { isOpenNow, startOfIstDay, withinHours } from '../src/domain/hours.js';
import {
  canCancel,
  canEditCart,
  isValidFulfilmentStep,
  nextFulfilmentStatus,
} from '../src/domain/orderState.js';
import { computeTotals, formatRupees, lineTotal } from '../src/domain/pricing.js';
import { normalizeUnit, roundQuantity } from '../src/domain/units.js';
import { UPI_ID, upiPayLink } from '../src/domain/upi.js';
import { nexconnUserId, orderCode } from '../src/lib/ids.js';
import { signRequest } from '../src/nexconn/signature.js';

// Times below are UTC; IST is UTC+05:30.
const atIst = (hh: number, mm: number) => new Date(Date.UTC(2026, 10, 18, hh, mm) - 330 * 60_000);

describe('units', () => {
  it('normalises spoken and written units', () => {
    expect(normalizeUnit('Kilo')).toBe('kg');
    expect(normalizeUnit('litres')).toBe('l');
    expect(normalizeUnit('packet')).toBe('pack');
    expect(normalizeUnit('pcs')).toBe('pc');
    expect(normalizeUnit('something odd')).toBe('pc');
    expect(normalizeUnit(null)).toBe('pc');
  });

  it('rounds quantities to the unit step and never below one step', () => {
    expect(roundQuantity(0.4, 'kg')).toBe(0.5);
    expect(roundQuantity(1.1, 'kg')).toBe(1);
    expect(roundQuantity(2.6, 'pack')).toBe(3);
    expect(roundQuantity(0, 'pc')).toBe(1);
    expect(roundQuantity(-3, 'kg')).toBe(0.25);
    expect(roundQuantity(120, 'g')).toBe(100);
  });
});

describe('pricing', () => {
  it('computes line totals in paise', () => {
    expect(lineTotal({ quantity: 2, unitPricePaise: 3000, available: true })).toBe(6000);
    expect(lineTotal({ quantity: 0.5, unitPricePaise: 4050, available: true })).toBe(2025);
    expect(lineTotal({ quantity: 2, unitPricePaise: 3000, available: false })).toBe(0);
    expect(lineTotal({ quantity: 2, unitPricePaise: null, available: true })).toBe(0);
  });

  it('sums available priced lines, counts unpriced lines, adds delivery fee', () => {
    const t = computeTotals(
      [
        { quantity: 2, unitPricePaise: 3000, available: true },
        { quantity: 2, unitPricePaise: 2800, available: true },
        { quantity: 1, unitPricePaise: null, available: true },
        { quantity: 5, unitPricePaise: 700, available: false },
      ],
      2000,
    );
    expect(t).toEqual({ subtotalPaise: 11600, deliveryFeePaise: 2000, totalPaise: 13600, unpricedCount: 1 });
  });

  it('formats rupees with Indian grouping', () => {
    expect(formatRupees(123456700)).toBe('₹12,34,567');
    expect(formatRupees(4550)).toBe('₹45.50');
  });
});

describe('order state machine', () => {
  it('allows cart edits only before billing', () => {
    expect(canEditCart('draft')).toBe(true);
    expect(canEditCart('placed')).toBe(true);
    expect(canEditCart('billed')).toBe(false);
  });

  it('walks fulfilment strictly in order', () => {
    expect(nextFulfilmentStatus('accepted')).toBe('packed');
    expect(nextFulfilmentStatus('packed')).toBe('out_for_delivery');
    expect(nextFulfilmentStatus('out_for_delivery')).toBe('delivered');
    expect(nextFulfilmentStatus('delivered')).toBeNull();
    expect(isValidFulfilmentStep('accepted', 'delivered')).toBe(false);
    expect(isValidFulfilmentStep('billed', 'packed')).toBe(false);
  });

  it('limits who can cancel when', () => {
    expect(canCancel('draft', 'customer')).toBe(true);
    expect(canCancel('draft', 'shop')).toBe(false);
    expect(canCancel('out_for_delivery', 'customer')).toBe(false);
    expect(canCancel('out_for_delivery', 'shop')).toBe(true);
    expect(canCancel('delivered', 'shop')).toBe(false);
  });
});

describe('opening hours (IST)', () => {
  it('handles same-day hours', () => {
    expect(withinHours('07:00', '22:00', atIst(8, 0))).toBe(true);
    expect(withinHours('07:00', '22:00', atIst(22, 0))).toBe(false);
    expect(withinHours('07:00', '22:00', atIst(6, 59))).toBe(false);
  });

  it('handles hours past midnight and all-day shops', () => {
    expect(withinHours('18:00', '02:00', atIst(1, 30))).toBe(true);
    expect(withinHours('18:00', '02:00', atIst(12, 0))).toBe(false);
    expect(withinHours('00:00', '00:00', atIst(3, 0))).toBe(true);
  });

  it('respects the manual open switch', () => {
    expect(isOpenNow({ isOpen: false, opensAt: '00:00', closesAt: '00:00' })).toBe(false);
  });

  it('computes the start of the IST day', () => {
    const start = startOfIstDay(atIst(10, 15));
    expect(start.toISOString()).toBe('2026-11-17T18:30:00.000Z');
  });
});

describe('geo', () => {
  it('measures distance between two Bengaluru points', () => {
    const d = haversineKm(12.9716, 77.5946, 12.9352, 77.6245); // MG Road to Koramangala
    expect(d).toBeGreaterThan(4.5);
    expect(d).toBeLessThan(5.5);
  });

  it('builds a bounding box that contains the radius', () => {
    const box = boundingBox(12.97, 77.59, 5);
    expect(box.maxLat - 12.97).toBeCloseTo(5 / 111.32, 4);
    expect(box.minLng).toBeLessThan(77.59);
  });
});

describe('UPI', () => {
  it('validates UPI IDs', () => {
    expect(UPI_ID.test('sharmastore@okaxis')).toBe(true);
    expect(UPI_ID.test('98765.43210@ybl')).toBe(true);
    expect(UPI_ID.test('no-at-sign')).toBe(false);
  });

  it('builds a upi://pay link with amount in rupees', () => {
    const link = upiPayLink({ upiId: 'sharma@okaxis', payeeName: 'Sharma General Store', amountPaise: 13600, orderCode: 'K7Q4MX' });
    expect(link.startsWith('upi://pay?')).toBe(true);
    const params = new URLSearchParams(link.slice('upi://pay?'.length));
    expect(params.get('pa')).toBe('sharma@okaxis');
    expect(params.get('am')).toBe('136.00');
    expect(params.get('cu')).toBe('INR');
    expect(params.get('tr')).toBe('LCK7Q4MX');
    expect(link).not.toContain('+');
  });
});

describe('ids and signatures', () => {
  it('creates prefixed alphanumeric Nexconn ids', () => {
    const id = nexconnUserId('lcr');
    expect(id).toMatch(/^lcr[A-Za-z0-9]{22}$/);
    expect(nexconnUserId('lcr')).not.toBe(id);
  });

  it('creates readable order codes without look-alike characters', () => {
    for (let i = 0; i < 50; i++) expect(orderCode()).toMatch(/^[A-HJKMNP-Z2-9]{6}$/);
  });

  it('signs Platform API requests as SHA1(secret + nonce + timestamp)', () => {
    expect(signRequest('secret', 'nonce', '1700000000000')).toMatch(/^[0-9a-f]{40}$/);
    expect(signRequest('a', 'b', 'c')).toBe('a9993e364706816aba3e25717850c26c9cd0d89d'); // sha1("abc")
  });
});
