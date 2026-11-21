import { and, count, eq, gt, lt } from 'drizzle-orm';
import { db } from '../db/client.js';
import { rateEvents } from '../db/schema.js';
import { tooMany } from './http.js';

/**
 * Fixed-window limiter backed by Postgres so it holds across serverless instances.
 * Records an event and throws 429 when more than `limit` events exist in the window.
 */
export async function rateLimit(bucket: string, limit: number, windowSeconds: number, message: string) {
  const since = new Date(Date.now() - windowSeconds * 1000);
  const [row] = await db()
    .select({ n: count() })
    .from(rateEvents)
    .where(and(eq(rateEvents.bucket, bucket), gt(rateEvents.createdAt, since)));
  if ((row?.n ?? 0) >= limit) throw tooMany(message);
  await db().insert(rateEvents).values({ bucket });
  // Opportunistic cleanup of old events (1% of calls) keeps the table small.
  if (Math.random() < 0.01) {
    await db()
      .delete(rateEvents)
      .where(lt(rateEvents.createdAt, new Date(Date.now() - 24 * 3600 * 1000)));
  }
}
