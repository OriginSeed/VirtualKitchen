import { useEffect, useRef, useState } from 'react'
import { Link, NavLink, useNavigate } from 'react-router-dom'
import type { User } from '../../../types/User'
import logoIcon from '../../../assets/kitchen/blackShadowChef.png'
import recipesIcon from '../../../assets/kitchen/recipeIcon.png'
import { useTheme } from '../../../shared/theme/themeContext'
import './KitchenNavbar.css'
import type { Kitchen } from '../../../types/kitchen'

interface KitchenNavbarProps {
  user: User
  kitchen: Kitchen
  onLogout: () => void
}

function getInitials(name?: string) {
  if (!name?.trim()) {
    return 'U'
  }

  const parts = name.trim().split(/\s+/)

  if (parts.length === 1) {
    return parts[0].charAt(0).toUpperCase()
  }

  return (
    parts[0].charAt(0) +
    parts[parts.length - 1].charAt(0)
  ).toUpperCase()
}

function ProfileMenu({
  user,
  onOrderHistory,
  onLogout,
}: {
  user: User
  onOrderHistory: () => void
  onLogout: () => void
}) {
  return (
    <div className="profile-menu">
      <div className="profile-menu-header">
        <div className="profile-menu-avatar">
          {getInitials(user.name)}
        </div>

        <div className="profile-menu-user">
          <div className="profile-menu-name">
            {user.name}
          </div>

          <div className="profile-menu-email">
            {user.email}
          </div>
        </div>
      </div>

      <div className="profile-menu-divider" />

      <div className="profile-menu-section">
        <div className="profile-menu-section-label">
          Account
        </div>

        <button
          className="profile-menu-item"
          disabled
        >
          <span className="profile-menu-item-icon">
            👤
          </span>

          <span>
            <strong>Profile</strong>
            <small>View your account information</small>
          </span>
        </button>

        <button
          className="profile-menu-item"
          onClick={onOrderHistory}
        >
          <span className="profile-menu-item-icon">
            📦
          </span>

          <span>
            <strong>Order History</strong>
            <small>View your previous orders</small>
          </span>
        </button>

        <button
          className="profile-menu-item"
          disabled
        >
          <span className="profile-menu-item-icon">
            ⚙️
          </span>

          <span>
            <strong>Account Settings</strong>
            <small>Manage your account</small>
          </span>
        </button>
      </div>

      <div className="profile-menu-divider" />

      <button
        className="profile-menu-item profile-menu-logout"
        onClick={onLogout}
      >
        <span className="profile-menu-item-icon">
          🚪
        </span>

        <span>
          <strong>Log out</strong>
          <small>Sign out of Virtual Kitchen</small>
        </span>
      </button>
    </div>
  )
}

export default function KitchenNavbar({
  user,
  onLogout,
}: KitchenNavbarProps) {
  const [profileOpen, setProfileOpen] =
    useState(false)
  const navigate = useNavigate()
  const { theme, toggleTheme } = useTheme()

  const profileRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const handleClickOutside = (event: MouseEvent) => {
      if (
        profileRef.current &&
        !profileRef.current.contains(
          event.target as Node,
        )
      ) {
        setProfileOpen(false)
      }
    }

    if (profileOpen) {
      document.addEventListener(
        'mousedown',
        handleClickOutside,
      )
    }

    return () => {
      document.removeEventListener(
        'mousedown',
        handleClickOutside,
      )
    }
  }, [profileOpen])

  const handleOrderHistory = () => {
    setProfileOpen(false)
    navigate('/kitchen/orders')
  }

  const handleLogout = () => {
    setProfileOpen(false)
    onLogout()
  }

  return (
    <nav className="kitchen-navbar">
      {/* Brand */}
      <div className="navbar-brand">
        {/* <div className="kitchen-logo">
          🍳
        </div> */}
        <div className="kitchen-logo" aria-hidden={false}>
          <img
            src={logoIcon}
            alt="Virtual Kitchen chef logo"
          />
        </div>

        <div className="kitchen-brand-text">
          <Link
            to="/"
            state={{ hideLogin: true }}
          >
          <span className="kitchen-brand-name">
            Virtual Kitchen
          </span>
          </Link>

          {/* <span className="kitchen-name">
            {kitchen.name}
          </span> */}
        </div>
      </div>

      {/* Main navigation */}
      <div className="navbar-navigation">
        <NavLink
          to="/kitchen/inventory"
          end
          className={({ isActive }) =>
            `navbar-nav-item ${
              isActive ? 'active' : ''
            }`
          }
        >
          <span>📦</span>
          My Kitchen
        </NavLink>

        <NavLink
          to="/kitchen/shop"
          end
          className={({ isActive }) =>
            `navbar-nav-item ${
              isActive ? 'active' : ''
            }`
          }
        >
          <span>🛒</span>
          Shop
        </NavLink>

        <NavLink
          to="/kitchen/recipes"
          className={({ isActive }) =>
            `navbar-nav-item ${
              isActive ? 'active' : ''
            }`
          }
        >
          <span><img src={recipesIcon} alt="Recipes" className="recipe-icon" /></span>
          My Recipes
        </NavLink>

        {user.userType === 'ADMIN' && (
          <NavLink
            to="/kitchen/admin/ai-analytics"
            className={({ isActive }) =>
              `navbar-nav-item ${
                isActive ? 'active' : ''
              }`
            }
          >
            <span>📊</span>
            AI Analytics
          </NavLink>
        )}

        {user.userType === 'ADMIN' && (
          <NavLink
            to="/kitchen/admin/ai-evaluation"
            className={({ isActive }) =>
              `navbar-nav-item ${
                isActive ? 'active' : ''
              }`
            }
          >
            <span>🧪</span>
            AI Evaluation
          </NavLink>
        )}
      </div>

      {/* Profile */}
      <div
        className="navbar-profile"
        ref={profileRef}
      >
        <button
          type="button"
          className="theme-toggle"
          onClick={toggleTheme}
          aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
          title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {theme === 'dark' ? '☀️' : '🌙'}
        </button>

        <button
          className={`profile-trigger ${
            profileOpen ? 'open' : ''
          }`}
          onClick={() =>
            setProfileOpen(current => !current)
          }
          aria-expanded={profileOpen}
          aria-label="Open profile menu"
        >
          <div className="profile-trigger-avatar">
            {getInitials(user.name)}
          </div>

          <div className="profile-trigger-info">
            <span className="profile-trigger-name">
              {user.name}
            </span>

            {/* <span className="profile-trigger-kitchen">
              {kitchen.name}
            </span> */}
          </div>

          <span
            className={`profile-trigger-chevron ${
              profileOpen ? 'open' : ''
            }`}
          >
            ▾
          </span>
        </button>

        {profileOpen && (
          <ProfileMenu
            user={user}
            onOrderHistory={
              handleOrderHistory
            }
            onLogout={handleLogout}
          />
        )}
      </div>
    </nav>
  )
}