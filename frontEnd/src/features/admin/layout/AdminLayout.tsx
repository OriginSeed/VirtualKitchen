import { NavLink, Outlet } from 'react-router-dom'
import { ADMIN_BASE, ADMIN_SECTIONS } from './adminSections'
import './admin.css'

/**
 * The admin area: a sub-navbar of every admin feature above the selected feature's page. The route is only offered
 * to admins; the backend enforces the ADMIN role on every call.
 */
export default function AdminLayout() {
  return (
    <div className="admin-area">
      <nav className="admin-subnav" aria-label="Admin features">
        <span className="admin-subnav-title">Admin</span>
        {ADMIN_SECTIONS.map(section => (
          <NavLink
            key={section.path}
            to={`${ADMIN_BASE}/${section.path}`}
            className={({ isActive }) => `admin-subnav-item ${isActive ? 'is-active' : ''}`}
          >
            <span aria-hidden>{section.icon}</span>
            {section.label}
          </NavLink>
        ))}
      </nav>
      <Outlet />
    </div>
  )
}
