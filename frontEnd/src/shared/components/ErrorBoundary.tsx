import { Component, type ErrorInfo, type ReactNode } from 'react'
import './page/page.css'

type ErrorBoundaryState = { failed: boolean }

/**
 * Last line of defence for a rendering crash: instead of a blank page, users get a short apology and a way out
 * (reload, or go home). The error itself goes to the console only; nothing technical is shown.
 */
export default class ErrorBoundary extends Component<{ children: ReactNode }, ErrorBoundaryState> {
  state: ErrorBoundaryState = { failed: false }

  static getDerivedStateFromError(): ErrorBoundaryState {
    return { failed: true }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('Unhandled rendering error', error, info.componentStack)
  }

  render() {
    if (!this.state.failed) return this.props.children
    return (
      <div className="vk-state vk-state-error" role="alert" style={{ minHeight: '60vh', justifyContent: 'center' }}>
        <div className="vk-state-icon" aria-hidden="true">⚠️</div>
        <h3 className="vk-state-title">Something went wrong</h3>
        <p className="vk-state-text">
          This page ran into a problem. Your saved work is safe. Reload the page to continue, or go back to the home page.
        </p>
        <div className="vk-state-actions">
          <button type="button" className="vk-btn vk-btn-primary" onClick={() => window.location.reload()}>
            Reload page
          </button>
          <button type="button" className="vk-btn vk-btn-secondary" onClick={() => window.location.assign('/')}>
            Go to home
          </button>
        </div>
      </div>
    )
  }
}
