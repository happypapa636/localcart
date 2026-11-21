import type { Context } from 'hono';

/** Best-effort client IP behind Vercel's proxy, used only as a rate-limit key. */
export function clientIp(c: Context): string {
  const forwarded = c.req.header('x-forwarded-for');
  if (forwarded) return forwarded.split(',')[0]!.trim();
  return c.req.header('x-real-ip') ?? 'unknown';
}
