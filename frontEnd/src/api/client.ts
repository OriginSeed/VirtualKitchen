/**
 * Generic HTTP Client
 * Wraps fetch API with common error handling and configuration
 */

import { API_CONFIG } from './config'

interface FetchOptions extends RequestInit {
  params?: Record<string, string | number | boolean>
  timeout?: number
}

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
  const { params, timeout = API_CONFIG.timeout, ...fetchOptions } = options

  const url = buildUrl(endpoint, params)
  const controller = new AbortController()
  const externalSignal = fetchOptions.signal

  if (externalSignal?.aborted) {
    controller.abort(externalSignal.reason)
  } else if (externalSignal) {
    externalSignal.addEventListener('abort', () => controller.abort(externalSignal.reason), { once: true })
  }

  const timeoutId = timeout > 0
    ? window.setTimeout(() => controller.abort(new DOMException('Request timed out', 'AbortError')), timeout)
    : null

  let response: Response

  try {
    response = await fetch(url, {
      ...defaultFetchOptions,
      ...fetchOptions,
      signal: controller.signal,
      headers: {
        ...API_CONFIG.headers,
        ...fetchOptions.headers,
      },
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error('Request timed out. Please try again.')
    }

    throw error
  } finally {
    if (timeoutId != null) {
      window.clearTimeout(timeoutId)
    }
  }

  if (!response.ok) {
    const errorData = await response.json().catch(() => ({}))
    throw new Error(
      errorData.message || 
      `HTTP ${response.status}: ${response.statusText}`
    )
  }

  const data: ApiResponse<T> = await response.json()
  return data.data
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
    body: body ? JSON.stringify(body) : undefined,
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
    body: body ? JSON.stringify(body) : undefined,
  })
}

/**
 * PATCH request
 */
export async function apiPatch<T>(
  endpoint: string,
  body?: unknown,
  options?: FetchOptions
): Promise<T> {
  return request<T>(endpoint, {
    ...options,
    method: 'PATCH',
    body: body ? JSON.stringify(body) : undefined,
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

// Re-export for convenience
const defaultFetchOptions: RequestInit = {
  headers: API_CONFIG.headers,
}
