import { z } from 'zod';

const EnvSchema = z.object({
  DATABASE_URL: z.string().min(1),
  JWT_SECRET: z.string().min(32),
  NEXCONN_APP_KEY: z.string().min(1),
  NEXCONN_APP_SECRET: z.string().min(1),
  NEXCONN_API_BASE: z.string().url().default('https://api.sg-light-api.com'),
  NEXCONN_ID_PREFIX: z
    .string()
    .regex(/^[A-Za-z0-9]+$/, 'NEXCONN_ID_PREFIX must be alphanumeric')
    .default('lcr'),
  BLOB_READ_WRITE_TOKEN: z.string().optional(),
  GEMINI_API_KEY: z.string().optional(),
  GEMINI_MODEL: z.string().optional(),
  GEMINI_FALLBACK_MODEL: z.string().optional(),
});

export type Env = z.infer<typeof EnvSchema>;

let cached: Env | null = null;

/** Validated environment, read lazily so pure modules can be unit-tested without a full env. */
export function env(): Env {
  if (!cached) {
    const parsed = EnvSchema.safeParse(process.env);
    if (!parsed.success) {
      const names = parsed.error.issues.map((i) => i.path.join('.')).join(', ');
      throw new Error(`Invalid server environment: ${names}`);
    }
    cached = parsed.data;
  }
  return cached;
}
