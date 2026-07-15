import { useEffect, useState, useCallback } from 'react'
import { InventoryApi } from '../../api'
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

export default function InventoryView({
  kitchenId,
  filter,
  onFilterChange,
}: InventoryViewProps) {
  const [items, setItems] = useState<InventoryItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')

  const fetchItems = useCallback(async () => {
    try {
      setLoading(true)
      setError('')
      
      console.log('Fetching inventory for kitchenId:', kitchenId)
      const fetchedItems = await InventoryApi.getInventoryByKitchenId(kitchenId)
      
      console.log('Fetched items:', fetchedItems)
      
      if (fetchedItems && Array.isArray(fetchedItems)) {
        setItems(fetchedItems)
        if (fetchedItems.length === 0) {
          console.warn('No items returned from API')
        }
      } else {
        console.warn('Unexpected response format:', fetchedItems)
        setError('Unexpected response format from server')
      }
    } catch (err) {
      console.error('Error fetching inventory:', err)
      setError(`Failed to load inventory: ${err instanceof Error ? err.message : 'Unknown error'}`)
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
            <div key={`${item.itemType}-${item.id}`} className="inventory-card">
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
