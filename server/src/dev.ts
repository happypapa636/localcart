import { config } from 'dotenv';

config({ path: '.env.local' });

const { serve } = await import('@hono/node-server');
const { default: app } = await import('./index.js');

const port = Number(process.env.PORT ?? 3106);
serve({ fetch: app.fetch, port }, (info) => {
  console.log(`LocalCart API listening on http://localhost:${info.port}`);
});
