import './page.css'

/** Something failed to load: say what, show the reason, and offer to try again. */
export default function ErrorState({ title = 'Something went wrong', message, onRetry, compact = false }: {
  title?: string
  message?: string
  onRetry?: () => void
  compact?: boolean
}) {
  return (
    <div className={`vk-state vk-state-error${compact ? ' vk-state-compact' : ''}`} role="alert">
      <div className="vk-state-icon" aria-hidden="true">⚠️</div>
      <h3 className="vk-state-title">{title}</h3>
      {message && <p className="vk-state-text">{message}</p>}
      {onRetry && (
        <div className="vk-state-actions">
          <button type="button" className="vk-btn vk-btn-secondary" onClick={onRetry}>Try again</button>
        </div>
      )}
    </div>
  )
}
