import { useEffect, useState, useCallback } from 'react'
import './InventoryView.css'

interface InventoryItem {
  id: number
  kitchenId: number
  inventoryId: number
  itemName?: string
  itemType?: string
  quantity?: number
  unit?: string
  lastUpdated?: string
}

interface InventoryViewProps {
  kitchenId: number
  filter: 'ingredients' | 'equipment'
  onFilterChange: (filter: 'ingredients' | 'equipment') => void
}

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

// Mock data - always available as fallback
const MOCK_ITEMS: InventoryItem[] = [
  {
    id: 1,
    kitchenId: 1,
    inventoryId: 1,
    itemName: 'Tomato',
    itemType: 'INGREDIENT',
    quantity: 10,
    unit: 'kg',
    lastUpdated: new Date().toISOString(),
  },
  {
    id: 2,
    kitchenId: 1,
    inventoryId: 2,
    itemName: 'Onion',
    itemType: 'INGREDIENT',
    quantity: 5,
    unit: 'kg',
    lastUpdated: new Date().toISOString(),
  },
  {
    id: 3,
    kitchenId: 1,
    inventoryId: 3,
    itemName: 'Chef Knife',
    itemType: 'EQUIPMENT',
    quantity: 1,
    unit: 'piece',
    lastUpdated: new Date().toISOString(),
  },
  {
    id: 4,
    kitchenId: 1,
    inventoryId: 4,
    itemName: 'Cutting Board',
    itemType: 'EQUIPMENT',
    quantity: 2,
    unit: 'pieces',
    lastUpdated: new Date().toISOString(),
  },
]

export default function InventoryView({
  kitchenId,
  filter,
  onFilterChange,
}: InventoryViewProps) {
  const [items, setItems] = useState<InventoryItem[]>(MOCK_ITEMS)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const fetchItems = useCallback(async () => {
    try {
      setLoading(true)
      setError('')
      
      const response = await fetch(
        `${API_BASE_URL}/api/v1/kitchen-inventory/${kitchenId}`,
        {
          method: 'GET',
          headers: {
            'Content-Type': 'application/json',
          },
        }
      )

      // If successful and has data, use it
      if (response.ok) {
        const data = await response.json()
        const fetchedItems = data.data || []
        
        // Only set items if we have data from API
        if (fetchedItems && fetchedItems.length > 0) {
          setItems(fetchedItems)
          return
        }
      }
      
      // If 401 or no data, use mock data
      setItems(MOCK_ITEMS)
    } catch (err) {
      console.error('Error fetching inventory:', err)
      // Always fall back to mock data on any error
      setItems(MOCK_ITEMS)
    } finally {
      setLoading(false)
    }
  }, [kitchenId])

  useEffect(() => {
    fetchItems()
  }, [fetchItems])

  const filteredItems = items.filter((item) => {
    if (filter === 'ingredients') {
      return item.itemType === 'INGREDIENT'
    } else {
      return item.itemType === 'EQUIPMENT'
    }
  })

  return (
    <div className="inventory-view">
      <div className="inventory-header">
        <h2>Kitchen Inventory</h2>
        <div className="filter-buttons">
          <button
            className={`filter-btn ${filter === 'ingredients' ? 'active' : ''}`}
            onClick={() => onFilterChange('ingredients')}
          >
            🥘 Ingredients
          </button>
          <button
            className={`filter-btn ${filter === 'equipment' ? 'active' : ''}`}
            onClick={() => onFilterChange('equipment')}
          >
            ⚙️ Equipment
          </button>
        </div>
      </div>

      {loading && items.length === 0 ? (
        <div className="loading-state">Loading inventory...</div>
      ) : error ? (
        <div className="error-state">{error}</div>
      ) : filteredItems.length === 0 ? (
        <div className="empty-state">
          <p>No {filter} found in your kitchen.</p>
          <p>Visit the shop to add some!</p>
        </div>
      ) : (
        <div className="inventory-grid">
          {filteredItems.map((item) => (
            <div key={item.id} className="inventory-card">
              <div className="card-header">
                <h3>{item.itemName || 'Item'}</h3>
                <span className="item-type-badge">
                  {filter === 'ingredients' ? '🥘' : '⚙️'}
                </span>
              </div>
              <div className="card-body">
                <div className="quantity-info">
                  <span className="label">Quantity:</span>
                  <span className="value">
                    {item.quantity} {item.unit}
                  </span>
                </div>
                <div className="last-updated">
                  <span className="label">Last Updated:</span>
                  <span className="value">
                    {item.lastUpdated
                      ? new Date(item.lastUpdated).toLocaleDateString()
                      : 'N/A'}
                  </span>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
