import type { ReactNode } from 'react'
import './page.css'

/** The eyebrow / title / subtitle block at the top of a page, with the page's main actions on the right. */
export default function PageHeader({ eyebrow, title, subtitle, actions }: {
  eyebrow?: ReactNode
  title: ReactNode
  subtitle?: ReactNode
  actions?: ReactNode
}) {
  return (
    <header className="vk-page-header">
      <div className="vk-page-header-text">
        {eyebrow && <div className="vk-page-eyebrow">{eyebrow}</div>}
        <h1 className="vk-page-title">{title}</h1>
        {subtitle && <p className="vk-page-subtitle">{subtitle}</p>}
      </div>
      {actions && <div className="vk-page-actions">{actions}</div>}
    </header>
  )
}
