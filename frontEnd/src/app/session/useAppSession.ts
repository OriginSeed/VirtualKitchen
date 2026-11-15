import { useCallback, useEffect, useState } from 'react'
import { AuthenticationApi, KitchenApi } from '../../api'
import { clearStoredToken, isAuthenticated, onSessionExpired } from '../../api/authToken'
import { clearAllDrafts } from '../../shared/drafts/draftStore'
import type { Kitchen } from '../../types/kitchen'
import type { User } from '../../types/User'
import type { SignedInSession } from './signedIn'

const loadSession = async (): Promise<SignedInSession> => {
  const user = await AuthenticationApi.me()
  const kitchensData = await KitchenApi.getKitchenByOwnerId(user.id)
  const kitchen = (Array.isArray(kitchensData) ? kitchensData : [kitchensData])[0]
  if (!kitchen) {
    throw new Error('No kitchen found for this user')
  }
  return { user, kitchen }
}

/**
 * The signed-in user and their kitchen for the whole app: restored from the stored token on start-up, set on
 * login, cleared on logout or when the backend rejects the token (an expired session signs the user out).
 */
export function useAppSession() {
  const [session, setSession] = useState<SignedInSession | null>(null)
  const [restoring, setRestoring] = useState(isAuthenticated)
  // Why the user is signed out when it wasn't their choice; the sign-in form shows it until they sign in.
  const [notice, setNotice] = useState<string | null>(null)

  useEffect(() => {
    clearAllDrafts(undefined, { onlyExpired: true })
    if (!isAuthenticated()) return
    let cancelled = false
    loadSession()
      .then((restored) => {
        if (!cancelled) setSession(restored)
      })
      .catch((error) => {
        console.error('Session restore failed:', error)
        clearStoredToken()
        if (!cancelled) setNotice('We couldn’t restore your session. Please log in again.')
      })
      .finally(() => {
        if (!cancelled) setRestoring(false)
      })
    return () => {
      cancelled = true
    }
  }, [])

  const logout = useCallback(() => {
    clearStoredToken()
    // Unsent drafts may hold recipe content; the next person on this browser shouldn't see them.
    clearAllDrafts()
    setSession(null)
  }, [])

  useEffect(() => onSessionExpired(() => {
    logout()
    setNotice('Your session has expired. Please log in again.')
  }), [logout])

  const login = useCallback((user: User, kitchen: Kitchen) => {
    setNotice(null)
    setSession({ user, kitchen })
  }, [])

  return { session, restoring, notice, login, logout }
}
