import type { ReactNode } from 'react'
import { Outlet, useNavigate } from 'react-router-dom'
import type { User } from '../../../types/User'
import AppFooter from '../../../shared/components/AppFooter'
import KitchenNavbar from './KitchenNavbar'
import './KitchenLayout.css'

interface KitchenPageProps {
  user: User
  onLogout: () => void
  /** Status the app adds to the navbar (e.g. background AI work), without kitchen importing other features. */
  navbarExtras?: ReactNode
}

/** Every signed-in page: the navbar above a scrolling body (with the footer at its end). */
export default function KitchenLayout({ user, onLogout, navbarExtras }: KitchenPageProps) {
  const navigate = useNavigate()

  // Navigate before clearing auth state so the /kitchen route guard
  // doesn't win the race and redirect to /auth instead of home.
  const handleLogoutAndGoHome = () => {
    navigate('/')
    onLogout()
  }
  return (
    <div className="kitchen-page">
      <KitchenNavbar user={user} onLogout={handleLogoutAndGoHome} extras={navbarExtras} />

      <div className="kitchen-body">
        <main className="kitchen-content">
          <Outlet />
        </main>
        <AppFooter />
      </div>
    </div>
  )
}
