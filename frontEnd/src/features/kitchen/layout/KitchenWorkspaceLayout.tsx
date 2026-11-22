import { useEffect, useRef } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { groupKitchenSections, kitchenSectionPath } from './kitchenSections'
import './KitchenWorkspaceLayout.css'

/**
 * My Kitchen's pages (Overview, My Recipes, Pantry, Shop, Orders) with their side navigation — a horizontal tab
 * strip on narrow screens. Full-screen pages (the Recipe Tool, order wizard) sit outside it.
 */
export default function KitchenWorkspaceLayout() {
  const listRef = useRef<HTMLUListElement>(null)
  const { pathname } = useLocation()

  // On narrow screens the tabs scroll sideways: keep the current one in view (e.g. Orders, the last tab).
  useEffect(() => {
    const list = listRef.current
    const active = list?.querySelector<HTMLElement>('.kitchen-sidenav-link.active')
    if (!list || !active || list.scrollWidth <= list.clientWidth) return
    const listBox = list.getBoundingClientRect()
    const activeBox = active.getBoundingClientRect()
    const centre = activeBox.left - listBox.left + list.scrollLeft - (list.clientWidth - activeBox.width) / 2
    list.scrollTo({ left: Math.max(0, centre) })
  }, [pathname])

  return (
    <div className="kitchen-workspace">
      <nav className="kitchen-sidenav" aria-label="My Kitchen">
        <div className="kitchen-sidenav-title">My Kitchen</div>
        <ul ref={listRef} className="kitchen-sidenav-list">
          {groupKitchenSections().map((row) =>
            'heading' in row ? (
              <li key={`group-${row.heading}`} className="kitchen-sidenav-heading" aria-hidden="true">{row.heading}</li>
            ) : (
              <li key={row.section.path || 'overview'}>
                <NavLink
                  to={kitchenSectionPath(row.section)}
                  end={row.section.path === ''}
                  className={({ isActive }) => `kitchen-sidenav-link${isActive ? ' active' : ''}`}
                >
                  <span className="kitchen-sidenav-icon" aria-hidden="true">{row.section.icon}</span>
                  <span>{row.section.label}</span>
                </NavLink>
              </li>
            ),
          )}
        </ul>
      </nav>
      <div className="kitchen-workspace-main">
        <Outlet />
      </div>
    </div>
  )
}
