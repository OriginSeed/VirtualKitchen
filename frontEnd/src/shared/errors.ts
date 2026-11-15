/** First characters of the backend's `X-Request-ID` shown to users, enough to find the request in the logs. */
const REQUEST_REF_LENGTH = 8

/**
 * The `requestId` a failed API call carries (see ApiError in api/client.ts). Duck-typed so this module stays
 * free of the client, which reads import.meta.env and window at load time.
 */
const requestIdOf = (error: unknown): string | undefined => {
  if (typeof error !== 'object' || error === null || !('requestId' in error)) return undefined
  const { requestId } = error as { requestId: unknown }
  return typeof requestId === 'string' && requestId ? requestId : undefined
}

/**
 * A caught error's message for display (an ApiError carries the backend's message), else `fallback`. When the
 * error carries the backend's request id, ` (Ref: <first 8 chars>)` is appended so users can quote it.
 */
export const errorMessage = (error: unknown, fallback: string): string => {
  const message = error instanceof Error && error.message ? error.message : fallback
  const requestId = requestIdOf(error)
  return requestId ? `${message} (Ref: ${requestId.slice(0, REQUEST_REF_LENGTH)})` : message
}
