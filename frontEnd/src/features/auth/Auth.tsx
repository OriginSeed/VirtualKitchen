import { useEffect, useState } from 'react'
import type { User } from '../../types/User'
import { AuthenticationApi, KitchenApi } from '../../api'
import type { Kitchen } from '../../types/kitchen'
import { setStoredToken } from '../../api/authToken'
import GoogleSignInButton from './GoogleSignInButton'
import chefLogo from '../../assets/kitchen/blackShadowChef.png'
import { errorMessage } from '../../shared/errors'
import { clearDraft, draftKeys, readDraft, writeDraft } from '../../shared/drafts/draftStore'
import './Auth.css'

interface LoginPageProps {
  onLoginSuccess: (user: User, kitchen: Kitchen) => void
  onClose?: () => void
  /** The tab to open on ("Log in" vs "Get started"); a step already in progress this page load wins. */
  initialMode?: 'signup' | 'login'
  /** Shown above the form, e.g. why the user has to sign in again. */
  notice?: string
}

type View =
  | 'signup'
  | 'login'
  | 'verify-signup-otp'
  | 'login-otp-request'
  | 'login-otp-verify'
  | 'forgot-email'
  | 'forgot-otp'
  | 'forgot-reset'

type AuthDraft = { view: 'signup' | 'login'; name: string; email: string }
type AuthSecrets = { view: View | null; password: string; confirmPassword: string; otp: string; newPassword: string; confirmNewPassword: string }

const EMPTY_SECRETS: AuthSecrets = { view: null, password: '', confirmPassword: '', otp: '', newPassword: '', confirmNewPassword: '' }

// What was typed survives the form being closed by accident: the tab, name and email as a draft (so a
// reload keeps them too); passwords, codes and the exact step (a code / reset step that only makes
// sense right away) only in memory for this page load — they never touch storage. Both are cleared
// once sign-in succeeds.
let secretsDraft: AuthSecrets = EMPTY_SECRETS

const clearAuthDrafts = () => {
  clearDraft(draftKeys.auth())
  secretsDraft = EMPTY_SECRETS
}

const isTab = (view: View): view is AuthDraft['view'] => view === 'signup' || view === 'login'

export default function Auth({ onLoginSuccess, onClose, initialMode, notice }: LoginPageProps) {
  const [initialDraft] = useState(() => readDraft<Partial<AuthDraft>>(draftKeys.auth()))
  const [view, setView] = useState<View>(secretsDraft.view ?? initialMode ?? initialDraft?.view ?? 'signup')

  const [name, setName] = useState(initialDraft?.name ?? '')
  const [email, setEmail] = useState(initialDraft?.email ?? '')
  const [password, setPassword] = useState(secretsDraft.password)
  const [confirmPassword, setConfirmPassword] = useState(secretsDraft.confirmPassword)
  const [otp, setOtp] = useState(secretsDraft.otp)
  const [newPassword, setNewPassword] = useState(secretsDraft.newPassword)
  const [confirmNewPassword, setConfirmNewPassword] = useState(secretsDraft.confirmNewPassword)

  // Deeper steps belong to the tab they started from (a reset or code login starts from Login).
  const tab: AuthDraft['view'] = isTab(view) ? view : view === 'verify-signup-otp' ? 'signup' : 'login'
  useEffect(() => {
    if (name || email || tab !== 'signup') writeDraft<AuthDraft>(draftKeys.auth(), { view: tab, name, email })
    else clearDraft(draftKeys.auth())
  }, [tab, name, email])

  useEffect(() => {
    secretsDraft = { view, password, confirmPassword, otp, newPassword, confirmNewPassword }
  }, [view, password, confirmPassword, otp, newPassword, confirmNewPassword])

  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [info, setInfo] = useState('')

  const resetMessages = () => {
    setError('')
    setInfo('')
  }

  const switchMode = (mode: 'signup' | 'login') => {
    setView(mode)
    resetMessages()
    setPassword('')
    setConfirmPassword('')
  }

  const completeLogin = async (token: string, user: User) => {
    setStoredToken(token)

    const kitchensData = await KitchenApi.getKitchenByOwnerId(user.id)
    const kitchens = Array.isArray(kitchensData) ? kitchensData : [kitchensData]
    const kitchen = kitchens[0]

    if (!kitchen) {
      throw new Error('No kitchen found for this user')
    }

    clearAuthDrafts()
    onLoginSuccess(user, kitchen)
  }

  const withLoading = async (action: () => Promise<void>) => {
    setLoading(true)
    resetMessages()

    try {
      await action()
    } catch (err) {
      setError(errorMessage(err, 'An error occurred'))
    } finally {
      setLoading(false)
    }
  }

  const handleSignup = () =>
    withLoading(async () => {
      if (!name.trim() || !email.trim() || !password || !confirmPassword) {
        throw new Error('Please fill in all fields')
      }

      if (password !== confirmPassword) {
        throw new Error('Passwords do not match')
      }

      await AuthenticationApi.signup({
        name: name.trim(),
        email: email.trim(),
        password,
        confirmPassword,
      })

      setView('verify-signup-otp')
      setInfo(`We sent a verification code to ${email.trim()}.`)
    })

  const handleVerifySignupOtp = () =>
    withLoading(async () => {
      if (!otp.trim()) {
        throw new Error('Please enter the verification code')
      }

      const response = await AuthenticationApi.verifyEmailOtp(email.trim(), otp.trim())
      await completeLogin(response.token, response.user)
    })

  const handleResendSignupOtp = () =>
    withLoading(async () => {
      await AuthenticationApi.sendEmailVerificationOtp(email.trim())
      setInfo('A new verification code has been sent.')
    })

  const handleLogin = () =>
    withLoading(async () => {
      if (!email.trim() || !password) {
        throw new Error('Please enter your email and password')
      }

      const response = await AuthenticationApi.login({ email: email.trim(), password })
      await completeLogin(response.token, response.user)
    })

  const handleGoogleCredential = (idToken: string) =>
    withLoading(async () => {
      const response = await AuthenticationApi.loginWithGoogle(idToken)
      await completeLogin(response.token, response.user)
    })

  const handleRequestLoginOtp = () =>
    withLoading(async () => {
      if (!email.trim()) {
        throw new Error('Please enter your email')
      }

      await AuthenticationApi.sendLoginOtp(email.trim())
      setView('login-otp-verify')
      setInfo(`We sent a sign-in code to ${email.trim()}.`)
    })

  const handleVerifyLoginOtp = () =>
    withLoading(async () => {
      if (!otp.trim()) {
        throw new Error('Please enter the sign-in code')
      }

      const response = await AuthenticationApi.verifyLoginOtp(email.trim(), otp.trim())
      await completeLogin(response.token, response.user)
    })

  const handleRequestPasswordReset = () =>
    withLoading(async () => {
      if (!email.trim()) {
        throw new Error('Please enter your email')
      }

      await AuthenticationApi.forgotPassword(email.trim())
      setView('forgot-otp')
      setInfo(`We sent a password reset code to ${email.trim()}.`)
    })

  const handleVerifyForgotOtp = () =>
    withLoading(async () => {
      if (!otp.trim()) {
        throw new Error('Please enter the reset code')
      }

      await AuthenticationApi.verifyPasswordResetOtp(email.trim(), otp.trim())
      setView('forgot-reset')
      resetMessages()
    })

  const handleResetPassword = () =>
    withLoading(async () => {
      if (!newPassword || !confirmNewPassword) {
        throw new Error('Please fill in both password fields')
      }

      if (newPassword !== confirmNewPassword) {
        throw new Error('Passwords do not match')
      }

      await AuthenticationApi.resetPassword({
        email: email.trim(),
        newPassword,
        confirmNewPassword,
      })

      setView('login')
      setNewPassword('')
      setConfirmNewPassword('')
      setPassword('')
      setOtp('')
      setInfo('Password updated. Please sign in.')
    })

  return (
    <div
      className="auth-overlay"
      onClick={onClose ? (event) => {
        if (event.target === event.currentTarget) {
          onClose()
        }
      } : undefined}
    >
      <div className="auth-card">
        {onClose && (
          <button
            type="button"
            className="auth-close-btn"
            onClick={onClose}
            aria-label="Close"
          >
            ×
          </button>
        )}

        <div className="auth-icon">
          <img src={chefLogo} alt="Virtual Kitchen chef logo" />
        </div>

        <h1 className="auth-title">Virtual Kitchen</h1>
        <p className="auth-subtitle">Welcome to Your Digital Culinary Studio</p>

        {(view === 'signup' || view === 'login') && (
          <div className="auth-toggle" role="tablist" aria-label="Sign up or log in">
            <button
              type="button"
              role="tab"
              aria-selected={view === 'signup'}
              className={`auth-toggle-btn ${view === 'signup' ? 'active' : ''}`}
              onClick={() => switchMode('signup')}
            >
              Sign Up
            </button>
            <button
              type="button"
              role="tab"
              aria-selected={view === 'login'}
              className={`auth-toggle-btn ${view === 'login' ? 'active' : ''}`}
              onClick={() => switchMode('login')}
            >
              Login
            </button>
          </div>
        )}

        {notice && !info && !error && <div className="auth-info" role="status">{notice}</div>}
        {info && <div className="auth-info" role="status">{info}</div>}
        {error && <div className="auth-error" role="alert">{error}</div>}

        {view === 'signup' && (
          <div className="auth-form">
            <div className="auth-form-group">
              <label htmlFor="name">Name</label>
              <input
                id="name"
                type="text"
                placeholder="Enter your name"
                value={name}
                onChange={(e) => setName(e.target.value)}
                disabled={loading}
              />
            </div>

            <div className="auth-form-group">
              <label htmlFor="email">Email</label>
              <input
                id="email"
                type="email"
                placeholder="Enter your email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={loading}
              />
            </div>

            <div className="auth-form-group">
              <label htmlFor="password">Password</label>
              <input
                id="password"
                type="password"
                placeholder="At least 8 characters"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
              />
            </div>

            <div className="auth-form-group">
              <label htmlFor="confirmPassword">Confirm password</label>
              <input
                id="confirmPassword"
                type="password"
                placeholder="Re-enter your password"
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                disabled={loading}
                onKeyDown={(e) => e.key === 'Enter' && handleSignup()}
              />
            </div>

            <button className="auth-submit-btn" onClick={handleSignup} disabled={loading}>
              {loading ? 'Creating...' : 'Create Account'}
            </button>

            <div className="auth-divider"><span>or</span></div>
            <GoogleSignInButton onCredential={handleGoogleCredential} disabled={loading} />
          </div>
        )}

        {view === 'login' && (
          <div className="auth-form">
            <div className="auth-form-group">
              <label htmlFor="loginEmail">Email</label>
              <input
                id="loginEmail"
                type="email"
                placeholder="Enter your email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={loading}
              />
            </div>

            <div className="auth-form-group">
              <label htmlFor="loginPassword">Password</label>
              <input
                id="loginPassword"
                type="password"
                placeholder="Enter your password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                disabled={loading}
                onKeyDown={(e) => e.key === 'Enter' && handleLogin()}
              />
            </div>

            <button className="auth-submit-btn" onClick={handleLogin} disabled={loading}>
              {loading ? 'Logging in...' : 'Login'}
            </button>

            <div className="auth-link-row">
              <button className="auth-link-btn" onClick={() => { setView('login-otp-request'); resetMessages() }}>
                Sign in with an email code instead
              </button>
              <button className="auth-link-btn" onClick={() => { setView('forgot-email'); resetMessages() }}>
                Forgot password?
              </button>
            </div>

            <div className="auth-divider"><span>or</span></div>
            <GoogleSignInButton onCredential={handleGoogleCredential} disabled={loading} />
          </div>
        )}

        {view === 'verify-signup-otp' && (
          <div className="auth-form">
            <p className="auth-helper-text">Enter the 6-digit code we sent to {email}.</p>

            <div className="auth-form-group">
              <label htmlFor="signupOtp">Verification code</label>
              <input
                id="signupOtp"
                type="text"
                inputMode="numeric"
                placeholder="123456"
                value={otp}
                onChange={(e) => setOtp(e.target.value)}
                disabled={loading}
                onKeyDown={(e) => e.key === 'Enter' && handleVerifySignupOtp()}
              />
            </div>

            <button className="auth-submit-btn" onClick={handleVerifySignupOtp} disabled={loading}>
              {loading ? 'Verifying...' : 'Verify & Continue'}
            </button>

            <div className="auth-link-row">
              <button type="button" className="auth-link-btn" onClick={handleResendSignupOtp} disabled={loading}>
                Resend code
              </button>
              <button type="button" className="auth-link-btn" onClick={() => switchMode('signup')} disabled={loading}>
                Back to sign up
              </button>
            </div>
          </div>
        )}

        {view === 'login-otp-request' && (
          <div className="auth-form">
            <div className="auth-form-group">
              <label htmlFor="otpLoginEmail">Email</label>
              <input
                id="otpLoginEmail"
                type="email"
                placeholder="Enter your email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={loading}
                onKeyDown={(e) => e.key === 'Enter' && handleRequestLoginOtp()}
              />
            </div>

            <button className="auth-submit-btn" onClick={handleRequestLoginOtp} disabled={loading}>
              {loading ? 'Sending...' : 'Send sign-in code'}
            </button>

            <button className="auth-link-btn" onClick={() => switchMode('login')}>
              Back to password login
            </button>
          </div>
        )}

        {view === 'login-otp-verify' && (
          <div className="auth-form">
            <p className="auth-helper-text">Enter the 6-digit code we sent to {email}.</p>

            <div className="auth-form-group">
              <label htmlFor="loginOtp">Sign-in code</label>
              <input
                id="loginOtp"
                type="text"
                inputMode="numeric"
                placeholder="123456"
                value={otp}
                onChange={(e) => setOtp(e.target.value)}
                disabled={loading}
                onKeyDown={(e) => e.key === 'Enter' && handleVerifyLoginOtp()}
              />
            </div>

            <button className="auth-submit-btn" onClick={handleVerifyLoginOtp} disabled={loading}>
              {loading ? 'Verifying...' : 'Verify & Login'}
            </button>

            <div className="auth-link-row">
              <button type="button" className="auth-link-btn" onClick={handleRequestLoginOtp} disabled={loading}>
                Resend code
              </button>
              <button type="button" className="auth-link-btn" onClick={() => switchMode('login')} disabled={loading}>
                Back to login
              </button>
            </div>
          </div>
        )}

        {view === 'forgot-email' && (
          <div className="auth-form">
            <div className="auth-form-group">
              <label htmlFor="forgotEmail">Email</label>
              <input
                id="forgotEmail"
                type="email"
                placeholder="Enter your email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={loading}
                onKeyDown={(e) => e.key === 'Enter' && handleRequestPasswordReset()}
              />
            </div>

            <button className="auth-submit-btn" onClick={handleRequestPasswordReset} disabled={loading}>
              {loading ? 'Sending...' : 'Send reset code'}
            </button>

            <button className="auth-link-btn" onClick={() => switchMode('login')}>
              Back to login
            </button>
          </div>
        )}

        {view === 'forgot-otp' && (
          <div className="auth-form">
            <p className="auth-helper-text">Enter the 6-digit code we sent to {email}.</p>

            <div className="auth-form-group">
              <label htmlFor="forgotOtp">Reset code</label>
              <input
                id="forgotOtp"
                type="text"
                inputMode="numeric"
                placeholder="123456"
                value={otp}
                onChange={(e) => setOtp(e.target.value)}
                disabled={loading}
                onKeyDown={(e) => e.key === 'Enter' && handleVerifyForgotOtp()}
              />
            </div>

            <button className="auth-submit-btn" onClick={handleVerifyForgotOtp} disabled={loading}>
              {loading ? 'Verifying...' : 'Verify code'}
            </button>

            <div className="auth-link-row">
              <button type="button" className="auth-link-btn" onClick={handleRequestPasswordReset} disabled={loading}>
                Resend code
              </button>
              <button type="button" className="auth-link-btn" onClick={() => switchMode('login')} disabled={loading}>
                Back to login
              </button>
            </div>
          </div>
        )}

        {view === 'forgot-reset' && (
          <div className="auth-form">
            <div className="auth-form-group">
              <label htmlFor="newPassword">New password</label>
              <input
                id="newPassword"
                type="password"
                placeholder="At least 8 characters"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                disabled={loading}
              />
            </div>

            <div className="auth-form-group">
              <label htmlFor="confirmNewPassword">Confirm new password</label>
              <input
                id="confirmNewPassword"
                type="password"
                placeholder="Re-enter your new password"
                value={confirmNewPassword}
                onChange={(e) => setConfirmNewPassword(e.target.value)}
                disabled={loading}
                onKeyDown={(e) => e.key === 'Enter' && handleResetPassword()}
              />
            </div>

            <button className="auth-submit-btn" onClick={handleResetPassword} disabled={loading}>
              {loading ? 'Updating...' : 'Set new password'}
            </button>

            <button type="button" className="auth-link-btn" onClick={() => switchMode('login')} disabled={loading}>
              Back to login
            </button>
          </div>
        )}
      </div>
    </div>
  )
}
