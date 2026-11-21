# AGENTS.md: LocalCart

LocalCart lets people order from their neighbourhood shops by chatting, the way Indians already order
from the local kirana on WhatsApp, but structured. A customer types or speaks an order ("2 kg aloo,
1 litre Amul milk, 5 eggs"); AI turns it into a cart matched to the shop's catalog; the shop confirms
availability and prices with a bill in one tap; payment is cash on delivery or UPI; and a live order
card tracks the order to the doorstep. Currency is ₹, timezone IST.

This file is the working guide for anyone (human or agent) changing the code. Keep it current.

## Repository layout

```
localcart/
  server/    Backend API (TypeScript, Hono on Vercel, Drizzle + Neon Postgres, Gemini, Vercel Blob)
  android/   Native app (Kotlin, Jetpack Compose, Nexconn Android Chat SDK)
  docs/      Screenshots used by the README
```

## User journeys

### Shopkeeper, first time
1. Install, pick "I run a shop", sign up with name, mobile number and password.
2. Create the shop: name, category, photo (camera or gallery, uploaded to Blob through the server),
   location from device GPS (permission prompt with a typed-address fallback), delivery radius,
   opening hours, UPI ID.
3. Build the catalog: add items one by one (name, unit, price, in-stock, photo) or paste/type a list
   ("Aloo 30/kg, Amul Taaza 500ml 28 ...") that AI turns into rows to review, fix and save.
4. Land on the dashboard: open/closed switch, orders by status, today's sales, chats with unread counts.

### Shopkeeper, returning
- Opens the app to the dashboard; a new cart arrives in real time (in-app banner, unread badge).
- Opens the chat, reviews the Cart card, taps "Prepare bill", marks items unavailable or changes
  prices/quantities, adds a delivery fee, sends the Bill card.
- After the customer accepts, moves the Order status card: Packed, Out for delivery, Delivered.
  Confirms UPI receipt or cash collected. Can cancel with a reason.
- Edge cases: shop closed (customers cannot start carts; chat still works), item out of stock,
  customer edits the cart before billing (card updates in place), double taps (409 stale_order).

### Customer, first time
1. Install, pick "I'm ordering", sign up, share location (permission prompt) or type an address.
2. See nearby shops sorted by distance, open ones first, with category filters and search (shop name
   or item name). Shop page shows catalog, hours, rating, delivery radius.
3. Tap "Chat to order", type a list or hold the mic to record a voice note (Hindi or English).
   Tap "Make cart" on that message: the server asks Gemini to extract items, matches them to the
   catalog and posts a draft Cart card. Unmatched items stay on the cart for the shop to price.
4. Edit quantities or remove lines, then "Send to shop". The shop's Bill card arrives; accept it and
   choose Cash on delivery or UPI (opens a real `upi://pay` intent to the shop's UPI ID with the amount),
   then "I've paid". The Order status card updates live until Delivered; then rate the shop.
5. Share delivery location as a location message from the chat attachment menu.

### Customer, returning
- Orders tab: active and past orders, one-tap reorder (same items at today's prices), rating.
- Chats tab: conversation list with unread badges, typing indicators, read receipts.
- Edge cases: offline (cached chat history from the SDK database, banners for connection state,
  retry on failed sends), AI busy (friendly retry message), voice note with no items (shows what was
  heard), token expiry (app refreshes the chat token and reconnects).

## Architecture

```
 Android app (Compose)                         Vercel (Hono API)                 External
 ┌──────────────────────────┐   HTTPS/JSON    ┌──────────────────────────┐
 │ ui/ screens + viewmodels │ ──────────────▶ │ /api/auth, /api/shops,   │──▶ Neon Postgres (source of truth)
 │ data/ repositories       │   Bearer JWT    │ /api/orders, /api/chats  │──▶ Gemini (order + catalog parsing)
 │ chat/ NexconnChat wrapper│                 │ services/orders.ts       │──▶ Vercel Blob (photos)
 └───────────┬──────────────┘                 └───────────┬──────────────┘
             │ Nexconn Android Chat SDK                   │ Platform Chat API (signed REST)
             ▼                                            ▼
        ┌───────────────────── Nexconn (Singapore data centre) ─────────────────────┐
        │ direct channels, text / image / HD voice / location messages, custom      │
        │ card messages (lcr:cart, lcr:bill, lcr:status), message metadata, message │
        │ update, read receipts, typing, unread counts, history                     │
        └────────────────────────────────────────────────────────────────────────────┘
```

Order state lives in Postgres. The server posts the Cart, Bill and Order status cards through the
Platform Chat API (with `hasMetadata`) and, on every state change, sets message metadata so both phones
receive `onMessageMetadataUpdated` and re-render the card in place. Cart edits use the message update
API, so the original card's content changes for both sides. Clients always re-fetch the order from the
API when a card is shown, so a missed realtime event can never leave a card wrong.

## Conventions
- TypeScript strict; zod validation on every input; Drizzle for all SQL (no string SQL with user input).
- Money is integer paise everywhere; quantities are decimals in the item's unit.
- Every Nexconn user id is `NEXCONN_ID_PREFIX` + 22 random alphanumerics.
- API errors are `{ error: { code, message } }` with messages safe to show to users.
- Commits end with the Co-Authored-By trailer; work lands through PRs (squash merge).

## Folder map

```
server/
  src/index.ts            Hono app (Vercel entrypoint, framework preset "hono" in vercel.json)
  src/dev.ts              local dev server on port 3106
  src/env.ts              validated env (zod)
  src/db/schema.ts        Drizzle schema; migrations in server/drizzle/
  src/routes/             auth, chat (config, token, chat peers), uploads, shops, catalog, orders + dashboard
  src/services/orders.ts  order state machine + card sync (send card, set metadata, update message)
  src/services/cards.ts   card payloads (lcr:cart, lcr:bill, lcr:status) and the metadata snapshot
  src/nexconn/            Platform Chat API client (signed requests) and signature helper
  src/ai/                 Gemini client (retries, fallback model), order parser, catalog list parser
  src/domain/             pure logic: units, pricing, order state, IST hours, geo, UPI links
  scripts/                migrate, e2e-order-flow (second-phone driver), upload-apk, ai-smoke
  test/                   vitest: unit tests + full-loop integration test on the real stack
android/app/src/main/java/app/localcart/
  LocalCartApp.kt, MainActivity.kt
  core/                   AppContainer (manual DI), SecureStore (Keystore AES-GCM)
  data/api/               Retrofit API, DTOs, error mapping
  data/repo/              session, auth, shops, catalog, orders (cache), peer directory
  chat/                   NexconnChat (SDK wrapper), CardMessages (custom message classes)
  domain/                 money, quantities, order status rules, formatting, validation
  media/                  VoiceRecorder (AAC), VoicePlayer, ImageFiles, LocationClient
  notify/                 Notifier (in-app banner + local notifications)
  ui/                     theme, components, nav, auth, customer, shop, chat, orders, account
android/app/src/test/     JVM unit tests for domain logic and card decoding
```

## Data model (Postgres)

- `users`: phone (unique), bcrypt hash, name, role (customer or shopkeeper), `nexconn_id` (prefix +
  random), cached Nexconn token, delivery address and lat/lng.
- `shops`: one per shopkeeper; category, photo, phone, address, lat/lng, delivery radius, IST opening
  hours, open switch, UPI ID, rating sum and count.
- `catalog_items`: shop, name, unit, price (paise), in stock, photo, archived (soft delete).
- `chats`: customer and shop pairs (who may see whose profile; drives the shop's chat list).
- `orders`: code, shop, customer, status (draft, placed, billed, accepted, packed, out_for_delivery,
  delivered, cancelled), source (text, voice, reorder) and source text, money (subtotal, delivery,
  total in paise), payment method and status, cancel reason and side, delivery location, Nexconn
  message ids of the three cards, cart revision, rating, per-status timestamps.
- `order_items`: lines with catalog link (null when unmatched), quantity, unit price (null means the
  shop prices it), availability, requested text, line total.
- `rate_events`: fixed-window rate limiting across serverless instances.

## Nexconn features used, and where

| Feature | Where |
| --- | --- |
| SDK init with the Singapore data centre (`AreaCode.SG`), push disabled | `android/.../chat/NexconnChat.kt` `initialize()` |
| Connect with a server-issued token; refresh on 31004, 31020 or `TOKEN_INCORRECT`; reconnect; kicked-offline handling | `NexconnChat.connect()`, `refreshTokenAndReconnect()`, `registerHandlers()`; UI in `ui/nav/LocalCartRoot.kt` and the chat connection strip |
| Connection status handler | `NexconnChat.registerHandlers()` |
| Direct channel per customer and shop pair | `DirectChannel(peerId)` throughout `NexconnChat.kt` |
| Text messages with read receipts (`needReceipt`) | `NexconnChat.sendText()` |
| Image messages (media upload with progress) | `NexconnChat.sendImage()`, `sendMedia()` |
| HD voice messages (AAC, 44.1 kHz) | `media/Media.kt` `VoiceRecorder`, `NexconnChat.sendVoice()` |
| Location messages (`RC:LBSMsg` with the required JPEG preview from `media/LocationThumbnail.kt`) | `NexconnChat.sendLocation()` calls `POST /api/chats/location`, which sends the built-in location message through the Platform Chat API as the user (SDK 26.4.2 rejects client-side `LocationMessage` sends with an undocumented 31610); received and rendered by the SDK on both phones in `ui/chat/ChatScreen.kt` |
| Custom message types `lcr:cart`, `lcr:bill`, `lcr:status` | `chat/CardMessages.kt` (registered before connect); sent by `server/src/services/orders.ts` |
| Message metadata for live card state | server `setMessageMetadata()` in `server/src/nexconn/client.ts`; client `onMessageMetadataUpdated` in `NexconnChat` and `ui/chat/ChatViewModel.kt` |
| Message update (cart edits change the original card) | server `updateDirectMessage()`; client `onMessagesUpdated` |
| Read receipts (send, receive, query) | `NexconnChat.markRead()`, `readMessageIds()`, `onMessageReceiptResponse` |
| Typing indicators | `NexconnChat.sendTyping()`, `onTypingStatusChanged`; "typing…" in the chat header |
| Unread counts (per channel and total) | `NexconnChat.refreshUnread()`, `clearUnreadCount`; badges on tabs, chat list, dashboard |
| Conversation list | `NexconnChat.conversations()` (`createChannelsQuery`) |
| History pagination from the local database | `NexconnChat.historyQuery()`, `loadPage()` (`createMessagesQuery`) |
| Failed-send retry | `NexconnChat.resend()` (`deleteMessagesForMe`, then resend) |
| Platform Chat API: token issue, user info update, direct message send (custom types, `hasMetadata`, `isEchoToSender`), metadata set, message update | `server/src/nexconn/client.ts` |

## Environment variables (names only)

Server (Vercel production and development; `server/.env.local` locally): `DATABASE_URL`,
`DATABASE_URL_UNPOOLED`, `BLOB_READ_WRITE_TOKEN`, `NEXCONN_APP_KEY`, `NEXCONN_APP_SECRET`,
`NEXCONN_API_BASE`, `NEXCONN_ID_PREFIX`, `JWT_SECRET`, `GEMINI_API_KEY`, `GEMINI_MODEL`,
`GEMINI_FALLBACK_MODEL`. The App Key reaches the app through `GET /api/config`; the secret never leaves
the server. Android: Gradle property `localcart.apiBaseUrl` (defaults to production) and
`android/keystore.properties` for release signing.

## Commands

```bash
# server (from server/)
vercel env pull .env.local --yes
npm run dev                  # http://localhost:3106
npm run typecheck
npm test                     # unit tests + real-stack integration test
npm run db:generate          # after schema changes; commit drizzle/
npm run db:migrate
vercel deploy --prod
npx tsx scripts/e2e-order-flow.ts <command>    # drive the second phone in a two-user test
npx tsx scripts/upload-apk.ts <apk> <version>  # publish the APK to Vercel Blob

# android (from android/)
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleRelease  # needs keystore.properties, see README
./gradlew :app:assembleDebug -Plocalcart.apiBaseUrl=http://10.0.2.2:3106/
./gradlew --stop
```

## Testing approach

- Server unit tests cover the pure domain (units, pricing, state machine, IST hours, geo, UPI, ids,
  signatures), parsing helpers and card payloads.
- `server/test/api.integration.test.ts` runs the whole loop in-process against real Neon, Nexconn and
  Gemini (including a real AAC voice note) and deletes its users afterwards.
- Android JVM unit tests cover money formatting, quantities, order rules, validation and card decoding.
- Emulator end-to-end: the release APK on `cortex_test` as one user, with the other user driven through
  the production API and the Platform Chat API by `scripts/e2e-order-flow.ts`; screenshots in `docs/`.

## Known limitations

- No FCM push: the shared Nexconn console has no FCM credentials and console changes are not allowed.
  Messages alert in real time while the app process is alive (in-app banner in the foreground, local
  notification in the background); nothing arrives after the process is killed until the app reopens.
- Chat history comes from the SDK's local database (synced while connected). After a reinstall, older
  messages are not re-downloaded; orders and their state are always available from the API.
- One phone per account at a time (Nexconn default); a second sign-in shows "open on another phone".
- UPI payment opens a real `upi://pay` intent, but confirmation is manual ("I've paid", then the shop
  confirms); there is no payment-gateway callback.
- Voice input on the emulator records the host microphone; the voice-to-cart path is verified with a
  real audio file in the integration test.
