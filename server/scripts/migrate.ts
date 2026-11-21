/** Applies committed Drizzle migrations from ./drizzle using the unpooled connection. */
import { config } from 'dotenv';
import { neon } from '@neondatabase/serverless';
import { drizzle } from 'drizzle-orm/neon-http';
import { migrate } from 'drizzle-orm/neon-http/migrator';

config({ path: '.env.local' });

const url = process.env.DATABASE_URL_UNPOOLED ?? process.env.DATABASE_URL;
if (!url) throw new Error('DATABASE_URL_UNPOOLED is not set');

await migrate(drizzle(neon(url)), { migrationsFolder: './drizzle' });
console.log('Migrations applied');
