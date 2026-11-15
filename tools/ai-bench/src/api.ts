/**
 * Minimal HTTP client for the Virtual Kitchen API: Bearer auth, ApiResponse unwrapping, and
 * backoff on 429 (AI queue full) and transient 5xx/network errors.
 */

import type { AccountRef } from './config'

export class HttpError extends Error {
  constructor(readonly status: number, message: string, readonly body?: unknown) {
    super(message)
  }
}

export type ApiStats = { queueRejections: number; retries: number }

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

const MAX_ATTEMPTS = 6

export class ApiClient {
  readonly stats: ApiStats = { queueRejections: 0, retries: 0 }

  constructor(readonly baseUrl: string, private token: string | null = null) {}

  withToken(token: string): ApiClient {
    return new ApiClient(this.baseUrl, token)
  }

  /** Same account, separate retry/queue-rejection counters (one per bench run). */
  fork(): ApiClient {
    return new ApiClient(this.baseUrl, this.token)
  }

  url(path: string): string {
    return /^https?:\/\//.test(path) ? path : `${this.baseUrl}${path.startsWith('/') ? '' : '/'}${path}`
  }

  async request<T>(method: string, path: string, body?: unknown, options: { raw?: boolean } = {}): Promise<T> {
    for (let attempt = 1; ; attempt++) {
      let response: Response
      try {
        response = await fetch(this.url(path), {
          method,
          headers: {
            Accept: 'application/json',
            ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
            ...(this.token ? { Authorization: `Bearer ${this.token}` } : {}),
          },
          body: body !== undefined ? JSON.stringify(body) : undefined,
        })
      } catch (error) {
        if (attempt >= MAX_ATTEMPTS) throw new Error(`${method} ${path}: ${(error as Error).message}`)
        this.stats.retries++
        await sleep(backoff(attempt))
        continue
      }

      if ((response.status === 429 || response.status === 502 || response.status === 503 || response.status === 504)
        && attempt < MAX_ATTEMPTS) {
        if (response.status === 429) this.stats.queueRejections++
        this.stats.retries++
        const retryAfter = Number(response.headers.get('retry-after'))
        await sleep(Number.isFinite(retryAfter) && retryAfter > 0 ? retryAfter * 1000 : backoff(attempt))
        continue
      }

      const text = await response.text()
      let parsed: unknown = undefined
      if (text) {
        try {
          parsed = JSON.parse(text)
        } catch {
          parsed = text
        }
      }
      if (!response.ok) {
        const message = (parsed && typeof parsed === 'object' && 'message' in parsed && typeof parsed.message === 'string')
          ? parsed.message
          : typeof parsed === 'string' ? parsed.slice(0, 300) : response.statusText
        throw new HttpError(response.status, `${method} ${path} -> ${response.status}: ${message}`, parsed)
      }
      if (options.raw) return parsed as T
      // Every /api/v1 response is ApiResponse{success,message,data,timestamp}.
      if (parsed && typeof parsed === 'object' && 'success' in parsed && 'data' in parsed) {
        return (parsed as { data: T }).data
      }
      return parsed as T
    }
  }

  get<T>(path: string) { return this.request<T>('GET', path) }
  post<T>(path: string, body?: unknown) { return this.request<T>('POST', path, body ?? {}) }
  put<T>(path: string, body?: unknown) { return this.request<T>('PUT', path, body) }
  delete<T>(path: string) { return this.request<T>('DELETE', path) }

  /** Downloads a binary (generated image/audio); sends the token only to our own API. */
  async download(path: string): Promise<{ bytes: Uint8Array; contentType: string | null }> {
    const url = this.url(path)
    const ownApi = url.startsWith(this.baseUrl)
    const response = await fetch(url, { headers: ownApi && this.token ? { Authorization: `Bearer ${this.token}` } : {} })
    if (!response.ok) throw new HttpError(response.status, `GET ${url} -> ${response.status}`)
    return { bytes: new Uint8Array(await response.arrayBuffer()), contentType: response.headers.get('content-type') }
  }
}

const backoff = (attempt: number) => Math.min(30_000, 1000 * 2 ** (attempt - 1)) + Math.floor(Math.random() * 500)

export type SessionUser = { id: number; email: string; userType?: string }
export type Session = { api: ApiClient; user: SessionUser }

const env = (name: string): string => {
  const value = process.env[name]
  if (!value) throw new Error(`environment variable ${name} is not set`)
  return value
}

/** Logs in (or uses a provided token) and returns a client bound to that account. */
export async function openSession(baseUrl: string, ref: AccountRef, tokenOverride?: string): Promise<Session> {
  const anonymous = new ApiClient(baseUrl)
  let token = tokenOverride ?? (ref.tokenEnv ? process.env[ref.tokenEnv] : undefined)
  if (!token) {
    if (!ref.emailEnv || !ref.passwordEnv) throw new Error('no token and no emailEnv/passwordEnv to log in with')
    const result = await anonymous.post<{ token: string }>('/api/v1/auth/login', {
      email: env(ref.emailEnv),
      password: env(ref.passwordEnv),
    })
    token = result.token
  }
  const api = anonymous.withToken(token)
  const user = await api.get<SessionUser>('/api/v1/auth/me')
  return { api, user }
}

export { sleep }
