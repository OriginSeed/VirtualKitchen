import { useState } from 'react'
import type { User } from '../../types/User'
import './LoginPage.css'

interface LoginPageProps {
  onLoginSuccess: (user: User, kitchen: any) => void
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

export default function LoginPage({ onLoginSuccess }: LoginPageProps) {
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
      const userResponse = await fetch(`${API_BASE_URL}/api/v1/users`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: userName.trim(),
          email: userEmail.trim(),
          passwordHash: 'demo-password', // Simple demo password
        }),
      })

      if (!userResponse.ok) {
        throw new Error('Failed to create user')
      }

      const userData = await userResponse.json()
      const user = userData.data

      // Create kitchen for this user
      const kitchenResponse = await fetch(`${API_BASE_URL}/api/v1/kitchens`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: `${userName}'s Virtual Kitchen`,
          ownerId: user.id,
        }),
      })

      if (!kitchenResponse.ok) {
        throw new Error('Failed to create kitchen')
      }

      const kitchenData = await kitchenResponse.json()
      const kitchen = kitchenData.data

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
      const userResponse = await fetch(`${API_BASE_URL}/api/v1/users/email/${userEmail.trim()}`)

      if (!userResponse.ok) {
        throw new Error('User not found. Please sign up first.')
      }

      const userData = await userResponse.json()
      const user = userData.data

      // Fetch kitchen for this user
      const kitchenResponse = await fetch(`${API_BASE_URL}/api/v1/kitchens?ownerId=${user.id}`)

      if (!kitchenResponse.ok) {
        throw new Error('Failed to fetch kitchen')
      }

      const kitchenData = await kitchenResponse.json()
      const kitchens = kitchenData.data
      const kitchen = Array.isArray(kitchens) ? kitchens[0] : kitchens

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
