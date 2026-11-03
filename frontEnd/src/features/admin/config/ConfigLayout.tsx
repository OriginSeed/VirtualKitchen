import { NavLink, Outlet } from 'react-router-dom'
import { useApiData } from '../../../shared/hooks/useApiData'
import { AdminConfigApi } from './api/adminConfigApi'
import './configLayout.css'

const CONFIG_BASE = '/kitchen/admin/config'

const linkClass = ({ isActive }: { isActive: boolean }) => `cfl-link ${isActive ? 'is-active' : ''}`

/**
 * Admin → Config: a side nav of every runtime config section beside the selected one. The sections come from the
 * backend catalog, so a new section shows up here without a frontend change. Collapses to a tab row on phones.
 */
export default function ConfigLayout() {
  const sections = useApiData('config-sections', () => AdminConfigApi.list())

  return (
    <div className="cfl-root">
      <nav className="cfl-nav" aria-label="Config sections">
        <div className="cfl-title">Config</div>
        {sections.error && <div className="cfl-note" role="alert">{sections.error}</div>}
        {!sections.data && !sections.error && <div className="cfl-note">Loading…</div>}
        {sections.data?.map((section) => (
          <div key={section.id} className="cfl-item">
            <NavLink
              to={`${CONFIG_BASE}/${section.id}`}
              end
              className={linkClass}
              title={section.available ? section.description : section.unavailableReason ?? undefined}
            >
              {section.label}
              {!section.available && <span className="cfl-off">off</span>}
            </NavLink>
            {section.id === 'models' && (
              <NavLink to={`${CONFIG_BASE}/models/new`} className={({ isActive }) => `${linkClass({ isActive })} cfl-sub`}>
                + Add model
              </NavLink>
            )}
          </div>
        ))}
      </nav>
      <div className="cfl-content">
        <Outlet />
      </div>
    </div>
  )
}
