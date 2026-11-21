import { createHash } from 'node:crypto';

/** Nexconn Platform API signature: hex SHA1 of appSecret + nonce + timestamp. */
export function signRequest(appSecret: string, nonce: string, timestamp: string): string {
  return createHash('sha1').update(appSecret + nonce + timestamp).digest('hex');
}
