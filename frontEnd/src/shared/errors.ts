/** First characters of the backend's `X-Request-ID` shown to users, enough to find the request in the logs. */
const REQUEST_REF_LENGTH = 8

/**
 * What users read for each kind of failure. Raw backend text, HTTP status codes and exception names never reach
 * the screen: the backend's message is only shown for validation failures (400/422), which it writes for users.
 */
export const ERROR_TEXT = {
  network: "Can't reach the server. Check your internet connection and try again.",
  timeout: 'This is taking longer than expected. Please try again.',
  sessionExpired: 'Your session has expired. Please sign in again.',
  forbidden: "You don't have access to this.",
  notFound: "We couldn't find what you were looking for. It may have been removed.",
  conflict: 'This was changed somewhere else. Refresh the page and try again.',
  tooLarge: "That's too large to send. Please make it smaller and try again.",
  aiBusy: 'The AI kitchen is busy right now. Please try again in a minute.',
  aiUnavailable: 'The AI service had a problem. Please try again in a moment.',
  server: 'Something went wrong on our side. Please try again.',
  job: 'Something went wrong while the AI was working on this. Please try again.',
  jobTimeout: 'The AI took too long to respond. Please try again.',
} as const

/** "Too many requests" text, with the wait the server asked for when it gave one. */
export const rateLimitedText = (retryAfterSeconds?: number): string =>
  retryAfterSeconds && retryAfterSeconds > 0
    ? `You're going a bit fast. Please try again in ${formatWait(retryAfterSeconds)}.`
    : "You're going a bit fast. Please wait a moment and try again."

const formatWait = (seconds: number): string => {
  if (seconds < 60) return `${Math.ceil(seconds)} second${Math.ceil(seconds) === 1 ? '' : 's'}`
  const minutes = Math.ceil(seconds / 60)
  return `${minutes} minute${minutes === 1 ? '' : 's'}`
}

/** Reads one property of a caught value without assuming its type (duck-typed: see ApiError in api/client.ts). */
const prop = (error: unknown, key: string): unknown =>
  typeof error === 'object' && error !== null && key in error ? (error as Record<string, unknown>)[key] : undefined

const stringProp = (error: unknown, key: string): string | undefined => {
  const value = prop(error, key)
  return typeof value === 'string' && value ? value : undefined
}

const numberProp = (error: unknown, key: string): number | undefined => {
  const value = prop(error, key)
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined
}

/**
 * The plain-language text for a caught error, before the Ref suffix. ApiError / NetworkError (api/client.ts) are
 * recognised by their `status`, `code` and `retryAfterSeconds`; any other Error keeps its own message (those are
 * written in the frontend), and anything else gets `fallback`.
 */
const friendlyText = (error: unknown, fallback: string): string => {
  const code = stringProp(error, 'code')
  const status = numberProp(error, 'status')

  switch (code) {
    case 'NETWORK': return ERROR_TEXT.network
    case 'TIMEOUT': return ERROR_TEXT.timeout
    case 'AI_BUSY': return ERROR_TEXT.aiBusy
    case 'AI_UNAVAILABLE': return ERROR_TEXT.aiUnavailable
    case 'RATE_LIMITED': return rateLimitedText(numberProp(error, 'retryAfterSeconds'))
    case 'PAYLOAD_TOO_LARGE': return ERROR_TEXT.tooLarge
  }

  if (status !== undefined) {
    const backendMessage = error instanceof Error && error.message ? error.message : undefined
    if (status === 400 || status === 422) return backendMessage ?? fallback
    if (status === 401) return ERROR_TEXT.sessionExpired
    if (status === 403) return ERROR_TEXT.forbidden
    if (status === 404) return ERROR_TEXT.notFound
    if (status === 409) return ERROR_TEXT.conflict
    if (status === 413) return ERROR_TEXT.tooLarge
    if (status === 429) return rateLimitedText(numberProp(error, 'retryAfterSeconds'))
    if (status >= 500) return ERROR_TEXT.server
    return fallback
  }

  // The browser's own wording for a request that never got an answer.
  if (error instanceof TypeError && /fetch|network|load failed/i.test(error.message)) return ERROR_TEXT.network
  return error instanceof Error && error.message ? error.message : fallback
}

/**
 * A caught error's message for display: plain language chosen by the error's code and HTTP status (see
 * {@link ERROR_TEXT}), the backend's own message for validation failures, a frontend Error's message, else
 * `fallback`. When the error carries the backend's request id, ` (Ref: <first 8 chars>)` is appended so users can
 * quote it.
 */
export const errorMessage = (error: unknown, fallback: string): string => {
  const message = friendlyText(error, fallback)
  const requestId = stringProp(error, 'requestId')
  return requestId ? `${message} (Ref: ${requestId.slice(0, REQUEST_REF_LENGTH)})` : message
}

/** Signs that a stored job failure is technical text: exception names, status codes, URLs, JSON, provider names. */
const TECHNICAL = /[A-Za-z]+(Exception|Error)\b|\bHTTP\b|\bstatus\s*[=:]?\s*\d{3}\b|https?:\/\/|[{}[\]]|\bat [a-z]+\.|\b(api[ _-]?key|token|openai|gemini|deepinfra|ollama|supabase|mongo)\b/i

/**
 * The text to show for an AI job's stored failure (`errorMessage` / `failureReason`). The backend already cleans
 * these; this is the frontend's own guard, for older jobs and anything that slips through. Short messages in plain
 * words are kept; technical text becomes a plain message. Returns `fallback` when there is no failure text.
 */
export function friendlyJobError(raw: string | null | undefined, fallback: string): string
export function friendlyJobError(raw: string | null | undefined): string | undefined
export function friendlyJobError(raw: string | null | undefined, fallback?: string): string | undefined {
  if (!raw || !raw.trim()) return fallback
  const lower = raw.toLowerCase()
  if (lower.includes('timeout') || lower.includes('timed out')) return ERROR_TEXT.jobTimeout
  if (/\b429\b|\b503\b|rate.?limit|too many requests|\bbusy\b|quota|overloaded/.test(lower)) return ERROR_TEXT.aiBusy
  if (raw.length > 300 || TECHNICAL.test(raw)) return ERROR_TEXT.job
  return raw
}
