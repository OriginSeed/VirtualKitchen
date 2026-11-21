import { useEffect, useRef } from 'react'
import { GOOGLE_CLIENT_ID } from '../../api/config'

type GoogleSignInButtonProps = {
  onCredential: (idToken: string) => void
  disabled?: boolean
}

export default function GoogleSignInButton({ onCredential, disabled }: GoogleSignInButtonProps) {
  const buttonRef = useRef<HTMLDivElement>(null)
  // Google keeps the callback it was initialised with; the ref lets it always call the latest prop.
  const onCredentialRef = useRef(onCredential)
  useEffect(() => {
    onCredentialRef.current = onCredential
  }, [onCredential])

  useEffect(() => {
    if (!GOOGLE_CLIENT_ID || disabled) {
      return
    }

    const google = window.google

    if (!google?.accounts?.id || !buttonRef.current) {
      return
    }

    google.accounts.id.initialize({
      client_id: GOOGLE_CLIENT_ID,
      callback: response => onCredentialRef.current(response.credential),
    })

    const container = buttonRef.current
    // Google draws a fixed-width button (200..400px). Match it to the card, which is narrower than 320px on
    // phones, and draw it again when the card's width changes (rotation, resize).
    let renderedWidth = 0
    const render = () => {
      const width = Math.max(200, Math.min(320, Math.floor(container.clientWidth)))
      if (width === renderedWidth) return
      renderedWidth = width
      container.innerHTML = ''
      google.accounts.id.renderButton(container, {
        theme: 'outline',
        size: 'large',
        width,
        text: 'continue_with',
      })
    }
    render()

    if (typeof ResizeObserver === 'undefined') return
    let frame = 0
    const observer = new ResizeObserver(() => {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(render)
    })
    observer.observe(container)
    return () => {
      cancelAnimationFrame(frame)
      observer.disconnect()
    }
  }, [disabled])

  if (!GOOGLE_CLIENT_ID) {
    return null
  }

  return <div ref={buttonRef} className="auth-google-button" />
}
