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
 * never got a response (offline, timeout) throws a plain Error instead.
 */
export class ApiError extends Error {
  readonly status: number
  /** The backend's `X-Request-ID` for this request, for users to quote when reporting the error. */
  readonly requestId?: string

  constructor(message: string, status: number, requestId?: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.requestId = requestId
  }
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
      throw new Error('Request timed out. Please try again.', { cause: error })
    }

    throw error
  } finally {
    if (timeoutId != null) {
      window.clearTimeout(timeoutId)
    }
  }

  if (response.status === 401 && token) {
    expireSession()
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}))
    throw new ApiError(
      errorData.message ||
      `HTTP ${response.status}: ${response.statusText}`,
      response.status,
      response.headers.get('X-Request-ID') ?? undefined,
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


