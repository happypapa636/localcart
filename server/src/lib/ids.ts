import { randomBytes } from 'node:crypto';

const ALNUM = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789';
// Order codes avoid look-alike characters (0/O, 1/I/L) so they can be read out over the phone.
const CODE_ALPHABET = 'ABCDEFGHJKMNPQRSTUVWXYZ23456789';

function randomFrom(alphabet: string, length: number): string {
  const bytes = randomBytes(length * 2);
  let out = '';
  for (let i = 0; out.length < length && i < bytes.length; i++) {
    const b = bytes[i]!;
    // Rejection sampling keeps the distribution uniform.
    if (b < Math.floor(256 / alphabet.length) * alphabet.length) {
      out += alphabet[b % alphabet.length];
    }
  }
  return out.length === length ? out : randomFrom(alphabet, length);
}

/** Nexconn user ids: app prefix + random alphanumerics (never sequential, <= 64 bytes). */
export function nexconnUserId(prefix: string): string {
  return `${prefix}${randomFrom(ALNUM, 22)}`;
}

/** Short human-readable order code, e.g. "K7Q4MX". */
export function orderCode(): string {
  return randomFrom(CODE_ALPHABET, 6);
}

export function nonce(): string {
  return randomFrom(ALNUM, 16);
}
