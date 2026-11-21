import type { Context } from 'hono';
import { HTTPException } from 'hono/http-exception';
import type { ContentfulStatusCode } from 'hono/utils/http-status';
import type { z } from 'zod';

/** A user-facing API error. `code` is stable for clients; `message` is safe to show. */
export class ApiError extends Error {
  constructor(
    public readonly status: ContentfulStatusCode,
    public readonly code: string,
    message: string,
    public readonly details?: unknown,
  ) {
    super(message);
  }
}

export const badRequest = (message: string, details?: unknown) =>
  new ApiError(400, 'bad_request', message, details);
export const unauthorized = (message = 'Sign in to continue.') => new ApiError(401, 'unauthorized', message);
export const forbidden = (message = 'You do not have access to this.') => new ApiError(403, 'forbidden', message);
export const notFound = (message = 'Not found.') => new ApiError(404, 'not_found', message);
export const conflict = (message: string, code = 'conflict') => new ApiError(409, code, message);
export const tooMany = (message: string) => new ApiError(429, 'rate_limited', message);

/** Parse and validate a JSON body with zod, mapping failures to a 400 with field details. */
export async function readJson<T extends z.ZodType>(c: Context, schema: T): Promise<z.infer<T>> {
  let raw: unknown;
  try {
    raw = await c.req.json();
  } catch {
    throw badRequest('Request body must be valid JSON.');
  }
  return parseWith(schema, raw);
}

export function parseWith<T extends z.ZodType>(schema: T, raw: unknown): z.infer<T> {
  const result = schema.safeParse(raw);
  if (!result.success) {
    const fields = result.error.issues.map((i) => ({ path: i.path.join('.'), message: i.message }));
    const first = fields[0];
    throw badRequest(first ? `${first.path || 'input'}: ${first.message}` : 'Invalid input.', fields);
  }
  return result.data;
}

export function errorResponse(err: unknown, c: Context) {
  if (err instanceof ApiError) {
    return c.json({ error: { code: err.code, message: err.message, details: err.details } }, err.status);
  }
  if (err instanceof HTTPException) {
    return c.json({ error: { code: 'http_error', message: err.message } }, err.status);
  }
  console.error('Unhandled error', err instanceof Error ? err.stack : err);
  return c.json({ error: { code: 'internal', message: 'Something went wrong on our side. Try again.' } }, 500);
}
