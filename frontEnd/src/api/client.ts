/**
 * Generic HTTP Client
 * Wraps fetch API with common error handling and configuration
 */

import { API_CONFIG } from './config'
import { expireSession, getStoredToken } from './authToken'

interface FetchOptions extends RequestInit {
  params?: Record<string, string | number | boolean>
  timeout?: number
  /**
   * Names the user action (e.g. `editor.save`) this request belongs to, sent as `X-UI-Action` so the
   * backend can tie every call of one click together. Lowercase letters, digits, `.`, `_`, `-`; max 64.
   */
  uiAction?: string
}

/** Per-call options for the API modules' functions that let a caller tag the request. */
export interface UiActionOptions {
  uiAction?: string
}

/**
 * A request that reached the server and got a non-2xx answer. Keeps the HTTP status so callers can
 * tell e.g. a validation error (422) or a conflict (409) from an outage (5xx) — a request that
 * never got a response (offline, timeout) throws a {@link NetworkError} instead.
 *
 * `message` is the backend's own message (empty when it sent none). Show errors through
 * `errorMessage()` in shared/errors.ts, which picks plain-language text from `code` and `status`.
 */
export class ApiError extends Error {
  readonly status: number
  /** The backend's `X-Request-ID` for this request, for users to quote when reporting the error. */
  readonly requestId?: string
  /** The backend's stable error code (e.g. `VALIDATION_FAILED`, `RATE_LIMITED`, `AI_BUSY`), when it sent one. */
  readonly code?: string
  /** Seconds the server asked to wait before retrying (`Retry-After`), on 429 / 503. */
  readonly retryAfterSeconds?: number

  constructor(message: string, status: number, requestId?: string, code?: string, retryAfterSeconds?: number) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.requestId = requestId
    this.code = code
    this.retryAfterSeconds = retryAfterSeconds
  }
}

/** A request that never got an answer: offline, server unreachable, blocked by CORS, or timed out. */
export class NetworkError extends Error {
  readonly code: 'NETWORK' | 'TIMEOUT'

  constructor(code: 'NETWORK' | 'TIMEOUT', options?: { cause?: unknown }) {
    super(code === 'TIMEOUT' ? 'Request timed out. Please try again.' : 'Unable to reach the server.', options)
    this.name = 'NetworkError'
    this.code = code
  }
}

/** `Retry-After` in seconds (it may also be an HTTP date), or undefined when absent or unreadable. */
const parseRetryAfter = (value: string | null): number | undefined => {
  if (!value) return undefined
  const seconds = Number(value)
  if (Number.isFinite(seconds)) return Math.max(0, seconds)
  const date = Date.parse(value)
  return Number.isNaN(date) ? undefined : Math.max(0, Math.ceil((date - Date.now()) / 1000))
}

/** The HTTP status of a failed request, or undefined when it never got a response. */
export const httpStatusOf = (error: unknown): number | undefined =>
  error instanceof ApiError ? error.status : undefined

interface ApiResponse<T> {
  success: boolean
  message?: string
  data: T
  timestamp?: string
}

/**
 * Build URL with query parameters
 */
function buildUrl(endpoint: string, params?: Record<string, string | number | boolean>): string {
  const url = new URL(endpoint, API_CONFIG.baseURL)

  if (params) {
    Object.entries(params).forEach(([key, value]) => {
      url.searchParams.append(key, String(value))
    })
  }

  return url.toString()
}

/**
 * Generic HTTP request method
 */
async function request<T>(
  endpoint: string,
  options: FetchOptions = {}
): Promise<T> {
  const { params, timeout = API_CONFIG.timeout, uiAction, ...fetchOptions } = options

  const url = buildUrl(endpoint, params)
  const controller = new AbortController()
  const externalSignal = fetchOptions.signal

  if (externalSignal?.aborted) {
    controller.abort(externalSignal.reason)
  } else if (externalSignal) {
    externalSignal.addEventListener('abort', () => controller.abort(externalSignal.reason), { once: true })
  }

  const timedOut = new DOMException('Request timed out', 'TimeoutError')
  const timeoutId = timeout > 0
    ? window.setTimeout(() => controller.abort(timedOut), timeout)
    : null
  const token = getStoredToken()

  let response: Response

  try {
    response = await fetch(url, {
      ...fetchOptions,
      signal: controller.signal,
      headers: {
        ...API_CONFIG.headers,
        ...(token ? { Authorization: `Bearer ${token}` } : {}),
        ...(uiAction ? { 'X-UI-Action': uiAction } : {}),
        ...fetchOptions.headers,
      },
    })
  } catch (error) {
    // Only our own timeout is reported as one; a caller's abort is passed through for it to recognise.
    if (controller.signal.reason === timedOut) {
      throw new NetworkError('TIMEOUT', { cause: error })
    }
    if (controller.signal.aborted) {
      throw error
    }

    // fetch rejects with a TypeError when the server can't be reached at all.
    throw new NetworkError('NETWORK', { cause: error })
  } finally {
    if (timeoutId != null) {
      window.clearTimeout(timeoutId)
    }
  }

  if (response.status === 401 && token) {
    expireSession()
  }

  if (!response.ok) {
    const errorData: { message?: unknown; code?: unknown; requestId?: unknown } =
      await response.json().catch(() => ({}))
    throw new ApiError(
      typeof errorData.message === 'string' ? errorData.message : '',
      response.status,
      response.headers.get('X-Request-ID')
        ?? (typeof errorData.requestId === 'string' ? errorData.requestId : undefined),
      typeof errorData.code === 'string' ? errorData.code : undefined,
      parseRetryAfter(response.headers.get('Retry-After')),
    )
  }

  const raw: unknown = await response.json().catch(() => null)

  // Support two response shapes:
  // 1) API wrapper: { success: boolean, message?: string, data: T }
  // 2) Raw payload: T
  if (raw && typeof raw === 'object' && 'data' in raw) {
    return (raw as ApiResponse<T>).data
  }

  return raw as T
}

/**
 * GET request
 */
export async function apiGet<T>(
  endpoint: string,
  options?: FetchOptions
): Promise<T> {
  return request<T>(endpoint, {
    ...options,
    method: 'GET',
  })
}

/**
 * POST request
 */
export async function apiPost<T>(
  endpoint: string,
  body?: unknown,
  options?: FetchOptions
): Promise<T> {
  return request<T>(endpoint, {
    ...options,
    method: 'POST',
    body: body == null ? undefined : JSON.stringify(body),
  })
}

/**
 * PUT request
 */
export async function apiPut<T>(
  endpoint: string,
  body?: unknown,
  options?: FetchOptions
): Promise<T> {
  return request<T>(endpoint, {
    ...options,
    method: 'PUT',
    body: body == null ? undefined : JSON.stringify(body),
  })
}

/**
 * DELETE request
 */
export async function apiDelete<T>(
  endpoint: string,
  options?: FetchOptions
): Promise<T> {
  return request<T>(endpoint, {
    ...options,
    method: 'DELETE',
  })
}


