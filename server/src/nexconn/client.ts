import { env } from '../env.js';
import { nonce as makeNonce } from '../lib/ids.js';
import { signRequest } from './signature.js';

export class NexconnError extends Error {
  constructor(
    public readonly path: string,
    public readonly code: number | string,
    message: string,
  ) {
    super(`Nexconn ${path} failed (${code}): ${message}`);
  }
}

interface NexconnResponse<T> {
  code: number;
  msg?: string;
  errorMessage?: string;
  result?: T;
}

const RETRYABLE_HTTP = new Set([429, 500, 502, 503, 504]);

/** Signed POST to the Nexconn Platform Chat API with retry on transient failures. */
async function call<T>(path: string, body: unknown, opts: { retryNetwork?: boolean } = {}, attempt = 0): Promise<T> {
  const { NEXCONN_API_BASE, NEXCONN_APP_KEY, NEXCONN_APP_SECRET } = env();
  const nonce = makeNonce();
  const timestamp = Date.now().toString();
  let res: Response;
  try {
    res = await fetch(`${NEXCONN_API_BASE}${path}`, {
      method: 'POST',
      headers: {
        'App-Key': NEXCONN_APP_KEY,
        Nonce: nonce,
        Timestamp: timestamp,
        Signature: signRequest(NEXCONN_APP_SECRET, nonce, timestamp),
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout(10_000),
    });
  } catch (err) {
    // Message sends are not idempotent, so they only retry when the server answered with a retryable status.
    if (opts.retryNetwork !== false && attempt < 2) {
      await new Promise((r) => setTimeout(r, 300 * 2 ** attempt));
      return call<T>(path, body, opts, attempt + 1);
    }
    throw new NexconnError(path, 'network', err instanceof Error ? err.message : String(err));
  }
  if (RETRYABLE_HTTP.has(res.status) && attempt < 2) {
    await new Promise((r) => setTimeout(r, 300 * 2 ** attempt));
    return call<T>(path, body, opts, attempt + 1);
  }
  const text = await res.text();
  let json: NexconnResponse<T>;
  try {
    json = JSON.parse(text) as NexconnResponse<T>;
  } catch {
    throw new NexconnError(path, res.status, text.slice(0, 200));
  }
  if (json.code !== 0 && json.code !== 200) {
    throw new NexconnError(path, json.code, json.msg ?? json.errorMessage ?? 'unknown error');
  }
  return (json.result ?? ({} as T)) as T;
}

/** Issues (and thereby registers) a chat access token for a user. */
export async function issueAccessToken(userId: string, name: string, avatarUrl?: string | null) {
  const result = await call<{ userId: string; accessToken: string }>('/v4/auth/access-token/issue', {
    userId,
    name: name.slice(0, 64),
    ...(avatarUrl ? { avatarUrl } : {}),
  });
  return result.accessToken;
}

/** Updates the name/avatar Nexconn uses for this user (push titles, sender info). */
export async function updateUserInfo(userId: string, name: string, avatarUrl?: string | null) {
  await call('/v4/user/update', { userId, name: name.slice(0, 64), ...(avatarUrl ? { avatarUrl } : {}) });
}

export interface SendDirectInput {
  fromUserId: string;
  toUserId: string;
  messageType: string;
  content: Record<string, unknown>;
  pushContent?: string;
  metadata?: Record<string, string>;
  needReadReceipt?: boolean;
}

/**
 * Sends a direct message as `fromUserId`, echoed to the sender's own devices, with metadata enabled
 * so the server can update live card state later. Returns the Nexconn message id.
 */
export async function sendDirectMessage(input: SendDirectInput): Promise<string> {
  const result = await call<{ messageIds?: { userId: string; messageId: string }[] }>(
    '/v4/direct-channel/message/send',
    {
      fromUserId: input.fromUserId,
      toUserIds: [input.toUserId],
      messageType: input.messageType,
      content: JSON.stringify(input.content),
      ...(input.pushContent ? { pushContent: input.pushContent } : {}),
      isEchoToSender: 1,
      shouldPersist: 1,
      hasMetadata: true,
      metadata: input.metadata ?? {},
      needReadReceipt: input.needReadReceipt ? 1 : 0,
    },
    { retryNetwork: false },
  );
  const id = result.messageIds?.find((m) => m.userId === input.toUserId)?.messageId ?? result.messageIds?.[0]?.messageId;
  if (!id) throw new NexconnError('/v4/direct-channel/message/send', 'no_id', 'response had no message id');
  return id;
}

/** Sets metadata on a message previously sent with metadata enabled; both sides get a live update. */
export async function setMessageMetadata(input: {
  messageId: string;
  operatorUserId: string;
  peerUserId: string;
  metadata: Record<string, string>;
}) {
  await call('/v4/channel-type/message/metadata/set', {
    messageId: input.messageId,
    userId: input.operatorUserId,
    channelType: 1,
    channelId: input.peerUserId,
    metadata: input.metadata,
    isEchoToSender: 1,
  });
}

/** Replaces the content of a direct message sent within the last 24 hours. */
export async function updateDirectMessage(input: {
  fromUserId: string;
  toUserId: string;
  messageId: string;
  content: Record<string, unknown>;
}) {
  await call('/v4/direct-channel/message/update', {
    fromUserId: input.fromUserId,
    targetId: input.toUserId,
    messageId: input.messageId,
    content: JSON.stringify(input.content),
  });
}
