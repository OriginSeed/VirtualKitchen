/**
 * Generic HTTP Client
 * Wraps fetch API with common error handling and configuration
 */

import { API_CONFIG } from './config'

interface FetchOptions extends RequestInit {
  params?: Record<string, string | number | boolean>
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
  const { params, ...fetchOptions } = options

  const url = buildUrl(endpoint, params)

  const response = await fetch(url, {
    ...defaultFetchOptions,
    ...fetchOptions,
    headers: {
      ...API_CONFIG.headers,
      ...fetchOptions.headers,
    },
  })

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
