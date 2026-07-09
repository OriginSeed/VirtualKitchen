import type { User } from '../../types/User'
import './KitchenNavbar.css'

interface Kitchen {
  id: number
  name: string
}

interface KitchenNavbarProps {
  user: User
  kitchen: Kitchen
  activeView: 'inventory' | 'shop'
  onViewChange: (view: 'inventory' | 'shop') => void
  onLogout: () => void
  onCreateRecipe: () => void
}

export default function KitchenNavbar({
  user,
  kitchen,
  activeView,
  onViewChange,
  onLogout,
  onCreateRecipe,
}: KitchenNavbarProps) {
  return (
    <nav className="kitchen-navbar">
      <div className="navbar-left">
        <div className="kitchen-header">
          <h1 className="kitchen-title">🍳 {kitchen.name}</h1>
        </div>
      </div>

      <div className="navbar-center">
        <div className="nav-tabs">
          <button
            className={`nav-tab ${activeView === 'inventory' ? 'active' : ''}`}
            onClick={() => onViewChange('inventory')}
          >
            My Inventory
          </button>
          <button
            className={`nav-tab ${activeView === 'shop' ? 'active' : ''}`}
            onClick={() => onViewChange('shop')}
          >
            🛒 Shop
          </button>
          <button className="nav-tab create-recipe-btn" onClick={onCreateRecipe}>
            ✏️ Create Recipe
          </button>
        </div>
      </div>

      <div className="navbar-right">
        <div className="profile-section">
          <div className="profile-avatar">{user.name.charAt(0).toUpperCase()}</div>
          <div className="profile-info">
            <div className="profile-name">{user.name}</div>
            <div className="profile-email">{user.email}</div>
          </div>
          <button className="logout-button" onClick={onLogout}>
            Logout
          </button>
        </div>
      </div>
    </nav>
  )
}
