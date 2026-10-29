import { API_CONFIG } from '../../../../api/config'

/** Resolves a stored media URL; locally stored files are served by the backend under a relative path. */
export function mediaUrl(url: string): string {
  try {
    return new URL(url, API_CONFIG.baseURL).toString()
  } catch {
    return url
  }
}
