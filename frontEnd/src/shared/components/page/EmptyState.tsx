import type { ReactNode } from 'react'
import './page.css'

/** Nothing to show yet: what is missing, why, and what to do about it. */
export default function EmptyState({ icon, title, text, actions, compact = false }: {
  icon?: ReactNode
  title: ReactNode
  text?: ReactNode
  /** Buttons or links that fix the emptiness (create, shop, discover…). */
  actions?: ReactNode
  /** Smaller padding, for a card inside a page rather than the whole page body. */
  compact?: boolean
}) {
  return (
    <div className={`vk-state${compact ? ' vk-state-compact' : ''}`}>
      {icon && <div className="vk-state-icon" aria-hidden="true">{icon}</div>}
      <h3 className="vk-state-title">{title}</h3>
      {text && <p className="vk-state-text">{text}</p>}
      {actions && <div className="vk-state-actions">{actions}</div>}
    </div>
  )
}
