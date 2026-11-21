import { neon } from '@neondatabase/serverless';
import { drizzle, type NeonHttpDatabase } from 'drizzle-orm/neon-http';
import { env } from '../env.js';
import * as schema from './schema.js';

export type Db = NeonHttpDatabase<typeof schema>;

let instance: Db | null = null;

export function db(): Db {
  if (!instance) {
    instance = drizzle(neon(env().DATABASE_URL), { schema });
  }
  return instance;
}
