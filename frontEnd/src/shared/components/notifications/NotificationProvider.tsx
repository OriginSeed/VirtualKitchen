import { useCallback, useRef, useState, type ReactNode } from 'react'
import { NotificationContext } from './notificationContext'
import './NotificationProvider.css'

type NotificationType = 'success' | 'error' | 'info'

type NotificationItem = {
  id: number
  message: string
  type: NotificationType
}

const AUTO_DISMISS_MS = 4000
/** Errors stay longer: they usually ask the user to do something, and are read more slowly. */
const ERROR_AUTO_DISMISS_MS = 8000

export function NotificationProvider({ children }: { children: ReactNode }) {
  const [notifications, setNotifications] = useState<NotificationItem[]>([])
  const nextId = useRef(0)

  const dismiss = useCallback((id: number) => {
    setNotifications(current => current.filter(item => item.id !== id))
  }, [])

  const notify = useCallback((message: string, type: NotificationType) => {
    const id = nextId.current++

    setNotifications(current => [...current, { id, message, type }])
    window.setTimeout(() => dismiss(id), type === 'error' ? ERROR_AUTO_DISMISS_MS : AUTO_DISMISS_MS)
  }, [dismiss])

  const notifySuccess = useCallback((message: string) => notify(message, 'success'), [notify])
  const notifyError = useCallback((message: string) => notify(message, 'error'), [notify])
  const notifyInfo = useCallback((message: string) => notify(message, 'info'), [notify])

  return (
    <NotificationContext.Provider value={{ notifySuccess, notifyError, notifyInfo }}>
      {children}

      <div className="notification-container" role="status" aria-live="polite">
        {notifications.map(item => (
          <div
            key={item.id}
            className={`notification-toast notification-toast-${item.type}`}
            // Errors interrupt the screen reader (assertive); other toasts wait their turn (the polite container).
            role={item.type === 'error' ? 'alert' : undefined}
          >
            <span className="notification-toast-icon" aria-hidden="true">
              {item.type === 'success' ? '✅' : item.type === 'error' ? '⚠️' : 'ℹ️'}
            </span>

            <span className="notification-toast-message">{item.message}</span>

            <button
              onClick={() => dismiss(item.id)}
              className="notification-toast-close"
              aria-label="Dismiss notification"
            >
              ✕
            </button>
          </div>
        ))}
      </div>
    </NotificationContext.Provider>
  )
}
