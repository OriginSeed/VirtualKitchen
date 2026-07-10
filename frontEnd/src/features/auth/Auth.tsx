import { useState } from 'react'
import type { User } from '../../types/User'
import { AuthApi, KitchenApi } from '../../api'
import './Auth.css'

interface LoginPageProps {
  onLoginSuccess: (user: User, kitchen: any) => void
}

export default function Auth({ onLoginSuccess }: LoginPageProps) {
  const [mode, setMode] = useState<'login' | 'signup'>('signup')
  const [userName, setUserName] = useState('')
  const [userEmail, setUserEmail] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const handleCreateUser = async () => {
    if (!userName.trim() || !userEmail.trim()) {
      setError('Please enter both name and email')
      return
    }

    setLoading(true)
    setError('')

    try {
      // Create user
      const user = await AuthApi.createUser({
        name: userName.trim(),
        email: userEmail.trim(),
        passwordHash: 'demo-password', // Simple demo password
      })

      // Create kitchen for this user
      const kitchen = await KitchenApi.createKitchen({
        name: `${userName}'s Virtual Kitchen`,
        ownerId: user.id,
      })

      // Success - call parent callback
      onLoginSuccess(user, kitchen)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred')
      console.error('Login error:', err)
    } finally {
      setLoading(false)
    }
  }

  const handleLogin = async () => {
    if (!userEmail.trim()) {
      setError('Please enter your email')
      return
    }

    setLoading(true)
    setError('')

    try {
      // Fetch user by email
      const user = await AuthApi.getUserByEmail(userEmail.trim())

      // Fetch kitchen for this user
      const kitchensData = await KitchenApi.getKitchenByOwnerId(user.id)
      const kitchens = Array.isArray(kitchensData) ? kitchensData : [kitchensData]
      const kitchen = kitchens[0]

      if (!kitchen) {
        throw new Error('No kitchen found for this user')
      }

      // Success - call parent callback
      onLoginSuccess(user, kitchen)
    } catch (err) {
      setError(err instanceof Error ? err.message : 'An error occurred')
      console.error('Login error:', err)
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="login-page">
      <div className="login-container">
        <div className="login-card">
          <h1 className="login-title">Virtual Kitchen</h1>
          <p className="login-subtitle">Welcome to Your Digital Culinary Studio</p>

          <div className="toggle-container">
            <button
              className={`toggle-btn ${mode === 'signup' ? 'active' : ''}`}
              onClick={() => {
                setMode('signup')
                setError('')
              }}
            >
              Sign Up
            </button>
            <button
              className={`toggle-btn ${mode === 'login' ? 'active' : ''}`}
              onClick={() => {
                setMode('login')
                setError('')
              }}
            >
              Login
            </button>
          </div>

          <div className="login-form">
            {mode === 'signup' && (
              <div className="form-group">
                <label htmlFor="userName">Name</label>
                <input
                  id="userName"
                  type="text"
                  placeholder="Enter your name"
                  value={userName}
                  onChange={(e) => setUserName(e.target.value)}
                  disabled={loading}
                />
              </div>
            )}

            <div className="form-group">
              <label htmlFor="userEmail">Email</label>
              <input
                id="userEmail"
                type="email"
                placeholder="Enter your email"
                value={userEmail}
                onChange={(e) => setUserEmail(e.target.value)}
                disabled={loading}
              />
            </div>

            {error && <div className="error-message">{error}</div>}

            <button
              className="create-button"
              onClick={mode === 'signup' ? handleCreateUser : handleLogin}
              disabled={loading}
            >
              {loading ? (mode === 'signup' ? 'Creating...' : 'Logging in...') : (mode === 'signup' ? 'Create Account & Kitchen' : 'Login')}
            </button>
          </div>
        </div>
      </div>

      <footer className="login-footer">
        <div className="login-footer-content">
          <p>&copy; 2024 Virtual Kitchen. All rights reserved.</p>
          <div className="login-footer-links">
            <a href="#privacy">Privacy Policy</a>
            <span>•</span>
            <a href="#terms">Terms of Service</a>
            <span>•</span>
            <a href="#contact">Contact Us</a>
          </div>
        </div>
      </footer>
    </div>
  )
}
