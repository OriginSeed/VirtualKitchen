import { useEffect, useRef, useState, type KeyboardEvent as ReactKeyboardEvent, type ReactNode } from 'react'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import type { User } from '../../../types/User'
import logoIcon from '../../../assets/kitchen/blackShadowChef.png'
import { useCartCount } from '../../../shared/cart/useCartCount'
import { useTheme } from '../../../shared/theme/themeContext'
import { kitchenAreaOf, type KitchenArea } from './kitchenSections'
import './KitchenNavbar.css'

interface KitchenNavbarProps {
  user: User
  onLogout: () => void
  /** Extra status shown before the cart (the app puts the background-AI indicator here). */
  extras?: ReactNode
}

/** Where "+ New recipe" goes: My Recipes, with the create dialog open. */
export const NEW_RECIPE_PATH = '/kitchen/recipes?create=1'

type AreaLink = { area: KitchenArea; to: string; label: string; icon: string }

const areaLinks = (isAdmin: boolean): AreaLink[] => [
  { area: 'kitchen', to: '/kitchen', label: 'My Kitchen', icon: '🏠' },
  { area: 'discover', to: '/kitchen/discover', label: 'Discover', icon: '🌍' },
  ...(isAdmin ? [{ area: 'admin' as const, to: '/kitchen/admin', label: 'Admin', icon: '🛠️' }] : []),
]

function getInitials(name?: string) {
  if (!name?.trim()) return 'U'
  const parts = name.trim().split(/\s+/)
  if (parts.length === 1) return parts[0].charAt(0).toUpperCase()
  return (parts[0].charAt(0) + parts[parts.length - 1].charAt(0)).toUpperCase()
}

/** The menu's items that are laid out right now (the phone-only navigation is hidden on wider screens). */
const visibleMenuItems = (menu: HTMLElement | null) =>
  Array.from(menu?.querySelectorAll<HTMLElement>('[role="menuitem"]') ?? []).filter((item) => item.offsetParent !== null)

function ProfileMenu({ user, links, activeArea, onNavigate, onLogout }: {
  user: User
  links: AreaLink[]
  activeArea: KitchenArea
  onNavigate: (to: string) => void
  onLogout: () => void
}) {
  const menuRef = useRef<HTMLDivElement>(null)

  // Keyboard users land on the first item, and can move with the arrow keys.
  useEffect(() => {
    visibleMenuItems(menuRef.current)[0]?.focus()
  }, [])

  const moveFocus = (event: ReactKeyboardEvent) => {
    if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return
    event.preventDefault()
    const items = visibleMenuItems(menuRef.current)
    const index = items.indexOf(document.activeElement as HTMLElement)
    const next = event.key === 'ArrowDown' ? index + 1 : index - 1
    items[(next + items.length) % items.length]?.focus()
  }

  return (
    <div className="profile-menu" role="menu" aria-label="Account" ref={menuRef} onKeyDown={moveFocus}>
      <div className="profile-menu-header">
        <div className="profile-menu-avatar" aria-hidden="true">{getInitials(user.name)}</div>
        <div className="profile-menu-user">
          <div className="profile-menu-name">{user.name}</div>
          <div className="profile-menu-email">{user.email}</div>
        </div>
      </div>

      {/* On phones the main navigation lives here instead of the navbar. */}
      <div className="profile-menu-mobile-nav">
        <div className="profile-menu-divider" />
        <div className="profile-menu-section">
          {links.map((link) => (
            <button
              key={link.area}
              type="button"
              role="menuitem"
              className={`profile-menu-item${activeArea === link.area ? ' current' : ''}`}
              aria-current={activeArea === link.area ? 'page' : undefined}
              onClick={() => onNavigate(link.to)}
            >
              <span className="profile-menu-item-icon" aria-hidden="true">{link.icon}</span>
              <span><strong>{link.label}</strong></span>
            </button>
          ))}
        </div>
      </div>

      <div className="profile-menu-divider" />

      <div className="profile-menu-section">
        <button type="button" role="menuitem" className="profile-menu-item" onClick={() => onNavigate('/kitchen/settings')}>
          <span className="profile-menu-item-icon" aria-hidden="true">⚙️</span>
          <span>
            <strong>Account settings</strong>
            <small>AI models, narration voice and credits</small>
          </span>
        </button>
      </div>

      <div className="profile-menu-divider" />

      <button type="button" role="menuitem" className="profile-menu-item profile-menu-logout" onClick={onLogout}>
        <span className="profile-menu-item-icon" aria-hidden="true">🚪</span>
        <span>
          <strong>Log out</strong>
          <small>Sign out of Virtual Kitchen</small>
        </span>
      </button>
    </div>
  )
}

/**
 * The signed-in navbar: brand, the top-level areas (My Kitchen, Discover, and Admin for admins), then the
 * always-available actions — New recipe, background AI status, cart, theme and the profile menu.
 */
export default function KitchenNavbar({ user, onLogout, extras }: KitchenNavbarProps) {
  const [profileOpen, setProfileOpen] = useState(false)
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const { theme, toggleTheme } = useTheme()
  const cartCount = useCartCount(user.id)
  const activeArea = kitchenAreaOf(pathname)
  const links = areaLinks(user.userType === 'ADMIN')

  const profileRef = useRef<HTMLDivElement>(null)
  const triggerRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    if (!profileOpen) return
    const handleClickOutside = (event: MouseEvent) => {
      if (profileRef.current && !profileRef.current.contains(event.target as Node)) setProfileOpen(false)
    }
    const handleEscape = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setProfileOpen(false)
      triggerRef.current?.focus()
    }
    document.addEventListener('mousedown', handleClickOutside)
    document.addEventListener('keydown', handleEscape)
    return () => {
      document.removeEventListener('mousedown', handleClickOutside)
      document.removeEventListener('keydown', handleEscape)
    }
  }, [profileOpen])

  const go = (to: string) => {
    setProfileOpen(false)
    navigate(to)
  }

  return (
    <nav className="kitchen-navbar" aria-label="Main">
      <div className="navbar-brand">
        <Link to="/kitchen" className="navbar-brand-link" aria-label="Virtual Kitchen — My Kitchen">
          <span className="kitchen-logo">
            <img src={logoIcon} alt="" />
          </span>
          <span className="kitchen-brand-name">Virtual Kitchen</span>
        </Link>
      </div>

      <div className="navbar-navigation">
        {links.map((link) => (
          <Link
            key={link.area}
            to={link.to}
            className={`navbar-nav-item${activeArea === link.area ? ' active' : ''}`}
            aria-current={activeArea === link.area ? 'page' : undefined}
          >
            <span aria-hidden="true">{link.icon}</span>
            {link.label}
          </Link>
        ))}
      </div>

      <div className="navbar-profile" ref={profileRef}>
        <Link to={NEW_RECIPE_PATH} className="navbar-new-recipe" title="Create a new recipe">
          <span aria-hidden="true">+</span>
          <span className="navbar-new-recipe-label">New recipe</span>
        </Link>

        {extras}

        <Link
          to="/kitchen/shop?cart=open"
          className="navbar-icon-button navbar-cart"
          aria-label={cartCount > 0 ? `Cart, ${cartCount} ${cartCount === 1 ? 'item' : 'items'}` : 'Cart, empty'}
          title="Cart"
        >
          <span aria-hidden="true">🛒</span>
          {cartCount > 0 && <span className="navbar-cart-badge" aria-hidden="true">{cartCount > 99 ? '99+' : cartCount}</span>}
        </Link>

        <button
          type="button"
          className="navbar-icon-button"
          onClick={toggleTheme}
          aria-label={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
          title={theme === 'dark' ? 'Switch to light mode' : 'Switch to dark mode'}
        >
          {theme === 'dark' ? '☀️' : '🌙'}
        </button>

        <button
          ref={triggerRef}
          type="button"
          className={`profile-trigger ${profileOpen ? 'open' : ''}`}
          onClick={() => setProfileOpen((current) => !current)}
          aria-expanded={profileOpen}
          aria-haspopup="menu"
          aria-label="Open account menu"
        >
          <div className="profile-trigger-avatar" aria-hidden="true">{getInitials(user.name)}</div>
          <div className="profile-trigger-info">
            <span className="profile-trigger-name">{user.name}</span>
          </div>
          <span className={`profile-trigger-chevron ${profileOpen ? 'open' : ''}`} aria-hidden="true">▾</span>
        </button>

        {profileOpen && (
          <ProfileMenu
            user={user}
            links={links}
            activeArea={activeArea}
            onNavigate={go}
            onLogout={() => {
              setProfileOpen(false)
              onLogout()
            }}
          />
        )}
      </div>
    </nav>
  )
}
