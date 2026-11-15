/**
 * Where to send a user after they sign in: back to the signed-in page they were trying to open, else My Kitchen.
 *
 * Only in-app kitchen paths are accepted, so a crafted `?from=` can never send a freshly signed-in user to
 * another site (`//evil.example`, `https://…`) or bounce them back to the auth pages.
 */

export const DEFAULT_SIGNED_IN_PATH = '/kitchen'

/** The query parameter on `/auth` that carries the page to return to. */
export const RETURN_PARAM = 'from'

export const isSafeReturnPath = (path: string | null | undefined): path is string => {
  if (!path) return false
  if (!path.startsWith('/kitchen')) return false
  // "/kitchen" itself, or a path/query/hash under it — not "/kitchenware".
  const rest = path.slice('/kitchen'.length)
  if (rest !== '' && !/^[/?#]/.test(rest)) return false
  // No protocol-relative or backslash tricks anywhere in the path.
  if (path.includes('//') || path.includes('\\')) return false
  return true
}

/** The validated return path, or My Kitchen. */
export const returnPathOr = (path: string | null | undefined, fallback = DEFAULT_SIGNED_IN_PATH): string =>
  isSafeReturnPath(path) ? path : fallback

/** The `/auth` URL that returns to `path` after signing in (plain `/auth` when there is nowhere useful to return). */
export const authPathFor = (path: string | null | undefined): string => {
  if (!isSafeReturnPath(path) || path === DEFAULT_SIGNED_IN_PATH) return '/auth'
  return `/auth?${RETURN_PARAM}=${encodeURIComponent(path)}`
}
