import { eq } from 'drizzle-orm';
import { Hono } from 'hono';
import { z } from 'zod';
import { db } from '../db/client.js';
import { users } from '../db/schema.js';
import { env } from '../env.js';
import {
  type AuthVars,
  currentUser,
  hashPassword,
  issueSession,
  requireAuth,
  selfView,
  verifyPassword,
} from '../lib/auth.js';
import { conflict, readJson, unauthorized } from '../lib/http.js';
import { nexconnUserId } from '../lib/ids.js';
import { rateLimit } from '../lib/rateLimit.js';
import { clientIp } from '../lib/request.js';

/** Indian mobile numbers: 10 digits starting 6-9, optionally written with +91 or a leading 0. */
export function normalizePhone(raw: string): string | null {
  const digits = raw.replace(/[^\d]/g, '');
  const local = digits.length === 12 && digits.startsWith('91') ? digits.slice(2) : digits.length === 11 && digits.startsWith('0') ? digits.slice(1) : digits;
  return /^[6-9]\d{9}$/.test(local) ? local : null;
}

const Phone = z.string().transform((v, ctx) => {
  const p = normalizePhone(v);
  if (!p) {
    ctx.addIssue({ code: 'custom', message: 'Enter a valid 10-digit Indian mobile number.' });
    return z.NEVER;
  }
  return p;
});

const SignupBody = z.object({
  name: z.string().trim().min(2, 'Enter your name.').max(60),
  phone: Phone,
  password: z.string().min(8, 'Use at least 8 characters.').max(128),
  role: z.enum(['customer', 'shopkeeper']),
});

const LoginBody = z.object({ phone: Phone, password: z.string().min(1).max(128) });

const UpdateMeBody = z.object({
  name: z.string().trim().min(2).max(60).optional(),
  addressText: z.string().trim().max(300).nullable().optional(),
  lat: z.number().min(-90).max(90).nullable().optional(),
  lng: z.number().min(-180).max(180).nullable().optional(),
});

export const authRoutes = new Hono<{ Variables: AuthVars }>();

authRoutes.post('/auth/signup', async (c) => {
  await rateLimit(`signup:${clientIp(c)}`, 10, 3600, 'Too many sign-ups from this network. Try again later.');
  const body = await readJson(c, SignupBody);
  const [existing] = await db().select({ id: users.id }).from(users).where(eq(users.phone, body.phone)).limit(1);
  if (existing) throw conflict('An account with this mobile number already exists. Sign in instead.', 'phone_taken');
  const [user] = await db()
    .insert(users)
    .values({
      name: body.name,
      phone: body.phone,
      passwordHash: await hashPassword(body.password),
      role: body.role,
      nexconnId: nexconnUserId(env().NEXCONN_ID_PREFIX),
    })
    .onConflictDoNothing()
    .returning();
  if (!user) throw conflict('An account with this mobile number already exists. Sign in instead.', 'phone_taken');
  return c.json({ token: await issueSession(user), user: selfView(user) }, 201);
});

authRoutes.post('/auth/login', async (c) => {
  const body = await readJson(c, LoginBody);
  await rateLimit(`login:${body.phone}`, 8, 900, 'Too many sign-in attempts. Wait 15 minutes and try again.');
  await rateLimit(`login-ip:${clientIp(c)}`, 40, 900, 'Too many sign-in attempts from this network. Try again later.');
  const [user] = await db().select().from(users).where(eq(users.phone, body.phone)).limit(1);
  if (!user || !(await verifyPassword(body.password, user.passwordHash))) {
    throw unauthorized('That mobile number and password do not match.');
  }
  return c.json({ token: await issueSession(user), user: selfView(user) });
});

authRoutes.get('/me', requireAuth, (c) => c.json({ user: selfView(currentUser(c)) }));

authRoutes.patch('/me', requireAuth, async (c) => {
  const user = currentUser(c);
  const body = await readJson(c, UpdateMeBody);
  const [updated] = await db()
    .update(users)
    .set({
      ...(body.name !== undefined ? { name: body.name } : {}),
      ...(body.addressText !== undefined ? { addressText: body.addressText } : {}),
      ...(body.lat !== undefined ? { lat: body.lat } : {}),
      ...(body.lng !== undefined ? { lng: body.lng } : {}),
    })
    .where(eq(users.id, user.id))
    .returning();
  return c.json({ user: selfView(updated ?? user) });
});
