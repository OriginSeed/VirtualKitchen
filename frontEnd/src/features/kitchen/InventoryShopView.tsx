import { useEffect, useState, useCallback, useRef } from 'react'
import { ShopApi } from '../../api'
import './InventoryShopView.css'

interface ShopItem {
  id: number
  name: string
  itemType: string
  description?: string
  basePrice?: number
}

export default function InventoryShopView() {
  const [allItems, setAllItems] = useState<ShopItem[]>([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState<'all' | 'ingredients' | 'equipment'>('all')
  
  // Use ref to track if fetch has been called to prevent redundant calls
  const hasFetched = useRef(false)

  const fetchAllItems = useCallback(async () => {
    // Prevent redundant calls
    if (hasFetched.current) {
      return
    }
    hasFetched.current = true

    try {
      setLoading(true)
      setError('')

      // Fetch both ingredients and equipment in parallel
      const [ingredients, equipments] = await Promise.all([
        ShopApi.getIngredients(),
        ShopApi.getEquipment(),
      ])

      const items: ShopItem[] = []

      // Process ingredients
      if (ingredients && Array.isArray(ingredients)) {
        const processedIngredients = ingredients.map((item: any) => ({
          id: item.id,
          name: item.name,
          itemType: 'INGREDIENT',
          description: item.description || 'High quality ingredient',
          basePrice: item.basePrice || 5.0,
        }))
        items.push(...processedIngredients)
      }

      // Process equipment
      if (equipments && Array.isArray(equipments)) {
        const processedEquipments = equipments.map((item: any) => ({
          id: item.id,
          name: item.name,
          itemType: 'EQUIPMENT',
          description: item.description || 'Professional equipment',
          basePrice: item.basePrice || 50.0,
        }))
        items.push(...processedEquipments)
      }

      setAllItems(items)
    } catch (err) {
      console.error('Error fetching shop items:', err)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    fetchAllItems()
  }, [fetchAllItems])

  const filteredItems = allItems.filter((item) => {
    if (filter === 'all') return true
    if (filter === 'ingredients') return item.itemType === 'INGREDIENT'
    return item.itemType === 'EQUIPMENT'
  })

  const handleAddToKitchen = (item: ShopItem) => {
    console.log(`Adding ${item.name} to kitchen`)
    alert(`Added ${item.name} to your kitchen!`)
  }

  return (
    <div className="shop-view">
      <div className="shop-header">
        <h2>Shop & Inventory</h2>
        <p className="shop-subtitle">Browse and add items to your kitchen</p>
      </div>

      <div className="shop-filters">
        <button
          className={`shop-filter-btn ${filter === 'all' ? 'active' : ''}`}
          onClick={() => setFilter('all')}
        >
          All Items
        </button>
        <button
          className={`shop-filter-btn ${filter === 'ingredients' ? 'active' : ''}`}
          onClick={() => setFilter('ingredients')}
        >
          🥘 Ingredients
        </button>
        <button
          className={`shop-filter-btn ${filter === 'equipment' ? 'active' : ''}`}
          onClick={() => setFilter('equipment')}
        >
          ⚙️ Equipment
        </button>
      </div>

      {loading && allItems.length === 0 ? (
        <div className="shop-loading">Loading shop items...</div>
      ) : error ? (
        <div className="shop-error">{error}</div>
      ) : filteredItems.length === 0 ? (
        <div className="shop-empty">
          <p>No items available in the shop.</p>
        </div>
      ) : (
        <div className="shop-grid">
          {filteredItems.map((item) => (
            <div key={item.id} className="shop-card">
              <div className="shop-card-icon">
                {item.itemType === 'INGREDIENT' ? '🥘' : '⚙️'}
              </div>
              <h3 className="shop-card-title">{item.name}</h3>
              <p className="shop-card-description">
                {item.description || 'No description available'}
              </p>
              {item.basePrice && (
                <div className="shop-card-price">${item.basePrice.toFixed(2)}</div>
              )}
              <button
                className="shop-add-button"
                onClick={() => handleAddToKitchen(item)}
              >
                + Add to Kitchen
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
