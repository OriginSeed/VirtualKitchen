import { useState } from 'react'
import type { User } from '../../types/User'
import KitchenNavbar from './KitchenNavbar'
import InventoryView from './InventoryView'
import InventoryShopView from './InventoryShopView'
import './KitchenPage.css'

interface Kitchen {
  id: number
  name: string
  ownerId: number
}

interface KitchenPageProps {
  user: User
  kitchen: Kitchen
  onLogout: () => void
  onCreateRecipe: () => void
}

export default function KitchenPage({ user, kitchen, onLogout, onCreateRecipe }: KitchenPageProps) {
  const [activeView, setActiveView] = useState<'inventory' | 'shop'>('inventory')
  const [inventoryFilter, setInventoryFilter] = useState<'ingredients' | 'equipment'>('ingredients')

  return (
    <div className="kitchen-page">
      <KitchenNavbar
        user={user}
        kitchen={kitchen}
        activeView={activeView}
        onViewChange={setActiveView}
        onLogout={onLogout}
        onCreateRecipe={onCreateRecipe}
      />

      <div className="kitchen-body">
        {activeView === 'inventory' ? (
          <InventoryView
            kitchenId={kitchen.id}
            filter={inventoryFilter}
            onFilterChange={setInventoryFilter}
          />
        ) : (
          <InventoryShopView />
        )}
      </div>

      <footer className="kitchen-footer">
        <div className="footer-content">
          <p>&copy; 2024 Virtual Kitchen. All rights reserved.</p>
          <div className="footer-links">
            <a href="#privacy">Privacy Policy</a>
            <span>•</span>
            <a href="#terms">Terms of Service</a>
            <span>•</span>
            <a href="#contact">Contact Us</a>
          </div>
        </div>
      </footer>
    </div>
  )
}
