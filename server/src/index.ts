import { Hono } from 'hono';
import { bodyLimit } from 'hono/body-limit';
import { secureHeaders } from 'hono/secure-headers';
import type { AuthVars } from './lib/auth.js';
import { errorResponse } from './lib/http.js';
import { authRoutes } from './routes/auth.js';
import { catalogRoutes } from './routes/catalog.js';
import { chatRoutes } from './routes/chat.js';
import { orderRoutes } from './routes/orders.js';
import { shopRoutes } from './routes/shops.js';
import { uploadRoutes } from './routes/uploads.js';

const app = new Hono<{ Variables: AuthVars }>();

app.use('*', secureHeaders());
app.use('/api/*', bodyLimit({ maxSize: 4.4 * 1024 * 1024, onError: (c) => c.json({ error: { code: 'too_large', message: 'That upload is too large.' } }, 413) }));

app.get('/', (c) =>
  c.json({ name: 'LocalCart API', docs: 'https://github.com/imthegoodboy/localcart', health: '/api/health' }),
);
app.get('/api/health', (c) => c.json({ ok: true, time: new Date().toISOString() }));

app.route('/api', authRoutes);
app.route('/api', chatRoutes);
app.route('/api', uploadRoutes);
app.route('/api', catalogRoutes);
app.route('/api', shopRoutes);
app.route('/api', orderRoutes);

app.notFound((c) => c.json({ error: { code: 'not_found', message: 'No such endpoint.' } }, 404));
app.onError(errorResponse);

export default app;
