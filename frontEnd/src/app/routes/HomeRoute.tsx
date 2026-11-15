import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import Auth from '../../features/auth/Auth'
import HomePage from '../../features/home/HomePage'
import type { Kitchen } from '../../types/kitchen'
import type { User } from '../../types/User'
import { DEFAULT_SIGNED_IN_PATH } from '../session/returnPath'

/**
 * The landing page. "Get started" opens the sign-up dialog and "Log in" the log-in dialog; a visitor who is
 * already signed in goes straight to My Kitchen.
 */
export default function HomeRoute({ signedIn, onLoginSuccess }: {
  signedIn: boolean
  onLoginSuccess: (user: User, kitchen: Kitchen) => void
}) {
  const navigate = useNavigate()
  const [authMode, setAuthMode] = useState<'signup' | 'login' | null>(null)

  const handleAuthSuccess = (user: User, kitchen: Kitchen) => {
    onLoginSuccess(user, kitchen)
    setAuthMode(null)
    navigate(DEFAULT_SIGNED_IN_PATH)
  }

  const openAuth = (mode: 'signup' | 'login') => {
    if (signedIn) navigate(DEFAULT_SIGNED_IN_PATH)
    else setAuthMode(mode)
  }

  return (
    <>
      <HomePage signedIn={signedIn} onTryIt={() => openAuth('signup')} onLogin={() => openAuth('login')} />
      {authMode && (
        // Keyed so switching between "Log in" and "Get started" opens on the right tab.
        <Auth key={authMode} initialMode={authMode} onLoginSuccess={handleAuthSuccess} onClose={() => setAuthMode(null)} />
      )}
    </>
  )
}
