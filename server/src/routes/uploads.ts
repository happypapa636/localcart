import { put } from '@vercel/blob';
import { Hono } from 'hono';
import { env } from '../env.js';
import { type AuthVars, currentUser, requireAuth } from '../lib/auth.js';
import { ApiError, badRequest } from '../lib/http.js';
import { rateLimit } from '../lib/rateLimit.js';

const MAX_BYTES = 6 * 1024 * 1024;
const ALLOWED: Record<string, string> = {
  'image/jpeg': 'jpg',
  'image/png': 'png',
  'image/webp': 'webp',
};

/** Detects the real image type from magic bytes instead of trusting the client's content type. */
export function sniffImageType(bytes: Uint8Array): string | null {
  if (bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'image/jpeg';
  if (bytes[0] === 0x89 && bytes[1] === 0x50 && bytes[2] === 0x4e && bytes[3] === 0x47) return 'image/png';
  if (
    bytes[0] === 0x52 &&
    bytes[1] === 0x49 &&
    bytes[2] === 0x46 &&
    bytes[3] === 0x46 &&
    bytes[8] === 0x57 &&
    bytes[9] === 0x45 &&
    bytes[10] === 0x42 &&
    bytes[11] === 0x50
  ) {
    return 'image/webp';
  }
  return null;
}

export const uploadRoutes = new Hono<{ Variables: AuthVars }>();

/** Uploads a shop or product photo to Vercel Blob and returns its public URL. */
uploadRoutes.post('/uploads', requireAuth, async (c) => {
  const user = currentUser(c);
  if (!env().BLOB_READ_WRITE_TOKEN) throw new ApiError(503, 'uploads_unavailable', 'Photo uploads are not configured.');
  await rateLimit(`upload:${user.id}`, 60, 3600, 'Too many uploads. Try again in a while.');
  const form = await c.req.parseBody();
  const file = form['file'];
  const kind = typeof form['kind'] === 'string' ? form['kind'] : 'item';
  if (!(file instanceof File)) throw badRequest('Attach a photo in the "file" field.');
  if (file.size > MAX_BYTES) throw badRequest('Photos must be smaller than 6 MB.');
  const bytes = new Uint8Array(await file.arrayBuffer());
  const type = sniffImageType(bytes);
  if (!type) throw badRequest('Only JPEG, PNG or WebP photos are supported.');
  const folder = kind === 'shop' ? 'shops' : 'items';
  const blob = await put(`localcart/${folder}/${user.id}.${ALLOWED[type]}`, Buffer.from(bytes), {
    access: 'public',
    contentType: type,
    addRandomSuffix: true,
    token: env().BLOB_READ_WRITE_TOKEN,
  });
  return c.json({ url: blob.url }, 201);
});
