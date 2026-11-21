import bcrypt from 'bcryptjs';
import { eq } from 'drizzle-orm';
import type { Context, MiddlewareHandler } from 'hono';
import { SignJWT, jwtVerify } from 'jose';
import { db } from '../db/client.js';
import { users, type User } from '../db/schema.js';
import { env } from '../env.js';
import { forbidden, unauthorized } from './http.js';

const SESSION_DAYS = 30;
const ISSUER = 'localcart';

function secretKey(): Uint8Array {
  return new TextEncoder().encode(env().JWT_SECRET);
}

export async function hashPassword(password: string): Promise<string> {
  return bcrypt.hash(password, 11);
}

export async function verifyPassword(password: string, hash: string): Promise<boolean> {
  return bcrypt.compare(password, hash);
}

export async function issueSession(user: Pick<User, 'id' | 'role'>): Promise<string> {
  return new SignJWT({ role: user.role })
    .setProtectedHeader({ alg: 'HS256' })
    .setSubject(user.id)
    .setIssuer(ISSUER)
    .setIssuedAt()
    .setExpirationTime(`${SESSION_DAYS}d`)
    .sign(secretKey());
}

export async function verifySession(token: string): Promise<string | null> {
  try {
    const { payload } = await jwtVerify(token, secretKey(), { issuer: ISSUER, algorithms: ['HS256'] });
    return typeof payload.sub === 'string' ? payload.sub : null;
  } catch {
    return null;
  }
}

export type AuthVars = { user: User };

/** Requires a valid bearer session and loads the user row fresh on every request. */
export const requireAuth: MiddlewareHandler<{ Variables: AuthVars }> = async (c, next) => {
  const header = c.req.header('authorization') ?? '';
  const token = header.startsWith('Bearer ') ? header.slice(7).trim() : '';
  if (!token) throw unauthorized();
  const userId = await verifySession(token);
  if (!userId) throw unauthorized('Your session has expired. Sign in again.');
  const [user] = await db().select().from(users).where(eq(users.id, userId)).limit(1);
  if (!user) throw unauthorized('Your account no longer exists.');
  c.set('user', user);
  await next();
};

export function currentUser(c: Context<{ Variables: AuthVars }>): User {
  return c.get('user');
}

export function requireRole(c: Context<{ Variables: AuthVars }>, role: User['role']): User {
  const user = c.get('user');
  if (user.role !== role) {
    throw forbidden(role === 'shopkeeper' ? 'Only shopkeepers can do this.' : 'Only customers can do this.');
  }
  return user;
}

/** Public profile safe to return to the user themself. */
export function selfView(user: User) {
  return {
    id: user.id,
    name: user.name,
    phone: user.phone,
    role: user.role,
    nexconnId: user.nexconnId,
    addressText: user.addressText,
    lat: user.lat,
    lng: user.lng,
    createdAt: user.createdAt,
  };
}
