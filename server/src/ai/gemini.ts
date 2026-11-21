import { GoogleGenAI, type Part, type Schema } from '@google/genai';
import type { z } from 'zod';
import { env } from '../env.js';
import { ApiError } from '../lib/http.js';

let client: GoogleGenAI | null = null;

function ai(): GoogleGenAI {
  const key = env().GEMINI_API_KEY;
  if (!key) throw new ApiError(503, 'ai_unavailable', 'Smart ordering is not configured on this server yet.');
  if (!client) client = new GoogleGenAI({ apiKey: key });
  return client;
}

function models(): string[] {
  const { GEMINI_MODEL, GEMINI_FALLBACK_MODEL } = env();
  const list = [GEMINI_MODEL, GEMINI_FALLBACK_MODEL].filter((m): m is string => Boolean(m));
  if (list.length === 0) throw new ApiError(503, 'ai_unavailable', 'No AI model is configured on this server.');
  return [...new Set(list)];
}

function isRetryable(err: unknown): boolean {
  const msg = err instanceof Error ? err.message : String(err);
  return /\b(429|500|502|503|504)\b|UNAVAILABLE|RESOURCE_EXHAUSTED|overloaded|high demand|timed? ?out|aborted|ECONNRESET|ENOTFOUND|fetch failed/i.test(
    msg,
  );
}

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export interface GenerateJsonInput<T extends z.ZodType> {
  system: string;
  parts: Part[];
  responseSchema: Schema;
  validator: T;
  maxOutputTokens?: number;
}

/**
 * Structured JSON generation with retries and model fallback. Every model in the chain gets up to
 * two attempts with backoff on 429/5xx before moving to the next. Output is validated with zod.
 */
export async function generateJson<T extends z.ZodType>(input: GenerateJsonInput<T>): Promise<{ data: z.infer<T>; model: string }> {
  let lastError: unknown;
  for (const model of models()) {
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        const res = await ai().models.generateContent({
          model,
          contents: [{ role: 'user', parts: input.parts }],
          config: {
            systemInstruction: input.system,
            responseMimeType: 'application/json',
            responseSchema: input.responseSchema,
            temperature: 0.1,
            maxOutputTokens: input.maxOutputTokens ?? 2048,
            abortSignal: AbortSignal.timeout(30_000),
          },
        });
        const text = res.text ?? '';
        let raw: unknown;
        try {
          raw = JSON.parse(text);
        } catch {
          throw new Error(`Model returned invalid JSON: ${text.slice(0, 120)}`);
        }
        const parsed = input.validator.safeParse(raw);
        if (!parsed.success) throw new Error(`Model output failed validation: ${parsed.error.issues[0]?.message}`);
        return { data: parsed.data, model };
      } catch (err) {
        lastError = err;
        if (!isRetryable(err)) break; // try the next model straight away
        await sleep(600 * 2 ** attempt);
      }
    }
  }
  console.error('Gemini failed on all models', lastError instanceof Error ? lastError.message : lastError);
  throw new ApiError(503, 'ai_busy', 'The ordering assistant is busy right now. Try again in a minute.');
}
