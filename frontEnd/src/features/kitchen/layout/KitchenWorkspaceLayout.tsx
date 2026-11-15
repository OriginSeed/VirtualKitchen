import { NavLink, Outlet } from 'react-router-dom'
import { groupKitchenSections, kitchenSectionPath } from './kitchenSections'
import './KitchenWorkspaceLayout.css'

/**
 * My Kitchen's pages (Overview, My Recipes, Pantry, Shop, Orders) with their side navigation — a horizontal tab
 * strip on narrow screens. Full-screen pages (the Recipe Tool, order wizard) sit outside it.
 */
export default function KitchenWorkspaceLayout() {
  return (
    <div className="kitchen-workspace">
      <nav className="kitchen-sidenav" aria-label="My Kitchen">
        <div className="kitchen-sidenav-title">My Kitchen</div>
        <ul className="kitchen-sidenav-list">
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
