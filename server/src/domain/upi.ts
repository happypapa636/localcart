/** UPI virtual payment addresses look like "name@bank" (NPCI allows letters, digits, dot, hyphen, underscore). */
export const UPI_ID = /^[a-zA-Z0-9._-]{2,256}@[a-zA-Z][a-zA-Z0-9.-]{1,63}$/;

export interface UpiPayment {
  upiId: string;
  payeeName: string;
  amountPaise: number;
  orderCode: string;
}

/** Builds a standard `upi://pay` deep link that any UPI app (GPay, PhonePe, Paytm, BHIM) can open. */
export function upiPayLink(p: UpiPayment): string {
  const params = new URLSearchParams({
    pa: p.upiId,
    pn: p.payeeName.slice(0, 50),
    am: (p.amountPaise / 100).toFixed(2),
    cu: 'INR',
    tn: `LocalCart order ${p.orderCode}`,
    tr: `LC${p.orderCode}`,
  });
  return `upi://pay?${params.toString().replace(/\+/g, '%20')}`;
}
