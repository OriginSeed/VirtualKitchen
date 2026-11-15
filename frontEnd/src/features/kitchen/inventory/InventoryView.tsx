import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { InventoryApi, type InventoryItem } from '../../../api'
import { EmptyState, ErrorState, PageHeader } from '../../../shared/components/page'
import { formatDate } from '../../../shared/format/date'
import { formatNumber } from '../../../shared/format/number'
import { useApiData } from '../../../shared/hooks/useApiData'
import CatalogItemImage from '../components/CatalogItemImage'
import { isInKitchen, stockLevels } from './inventoryVisibility'
import './InventoryView.css'

export type InventoryFilter = 'ingredients' | 'equipment'

interface InventoryViewProps {
  kitchenId: number
  /** Which half of the pantry this page shows (its own route: /kitchen/ingredients or /kitchen/equipment). */
  filter: InventoryFilter
}

const COPY: Record<InventoryFilter, { title: string; subtitle: string; search: string; icon: string }> = {
  ingredients: {
    title: 'Ingredients',
    subtitle: 'What is in your pantry right now. Buy more in the shop; recipe orders reserve and use what they need.',
    search: 'Search ingredients...',
    icon: '🥕',
  },
  equipment: {
    title: 'Equipment',
    subtitle: 'The tools in your kitchen. Buy more in the shop.',
    search: 'Search equipment...',
    icon: '🍳',
  },
}

function InventorySearch({
  search,
  placeholder,
  onSearchChange,
}: {
  search: string
  placeholder: string
  onSearchChange: (value: string) => void
}) {
  return (
    <div className="inventory-search-wrapper">
      <span
        className="inventory-search-icon"
        aria-hidden="true"
      >
        🔍
      </span>

      <input
        className="inventory-search-input"
        value={search}
        onChange={event =>
          onSearchChange(event.target.value)
        }
        placeholder={placeholder}
        aria-label={placeholder.replace(/\.+$/, '')}
      />

      {search && (
        <button
          className="inventory-search-clear"
          onClick={() => onSearchChange('')}
          aria-label="Clear search"
        >
          ✕
        </button>
      )}
    </div>
  )
}

function InventoryCard({
  item,
}: {
  item: InventoryItem
}) {
  const isIngredient =
    item.itemType === 'INGREDIENT'

  // Part of the stock can be held for recipe orders awaiting payment or in preparation.
  const { inStock, reserved, free } = stockLevels(item)
  return (
    <article className="inventory-card">
      <div className="inventory-card-image">
        <CatalogItemImage
          src={item.imageUrl}
          alt={item.itemName || ''}
          fallbackIcon={isIngredient ? '🥘' : '⚙️'}
        />
      </div>

      <div className="inventory-card-body">
        <div className="inventory-card-type">
          {isIngredient
            ? 'Ingredient'
            : 'Equipment'}
        </div>

        <h3 className="inventory-card-title">
          {item.itemName || 'Unnamed item'}
        </h3>

        <div className="inventory-quantity-box">
          <span className="inventory-quantity-label">
            In stock
          </span>

          <span className="inventory-quantity-value">
            {formatNumber(inStock)}
            {item.unit && (
              <small>{item.unit}</small>
            )}
          </span>
        </div>

        {reserved > 0 && (
          <div className="inventory-reserved-note">
            <span className="inventory-reserved-chip">
              {formatNumber(reserved)} reserved
            </span>

            <span>
              {formatNumber(free)} free for new
              orders
            </span>
          </div>
        )}

        <div className="inventory-card-footer">
          <span>Last updated</span>

          <strong>
            {formatDate(item.lastUpdated, 'N/A')}
          </strong>
        </div>
      </div>
    </article>
  )
}

function InventoryEmptyState({
  searching,
  filter,
  onClearSearch,
}: {
  searching: boolean
  filter: InventoryFilter
  onClearSearch: () => void
}) {
  if (searching) {
    return (
      <EmptyState
        icon="🔍"
        title="No matching items"
        text={`Try a different ${filter === 'ingredients' ? 'ingredient' : 'equipment'} name.`}
        actions={<button type="button" className="vk-btn vk-btn-secondary" onClick={onClearSearch}>Clear search</button>}
      />
    )
  }

  return (
    <EmptyState
      icon={COPY[filter].icon}
      title={`No ${filter} in your kitchen`}
      text={`Your pantry fills up as you buy ${filter} in the shop.`}
      actions={<Link className="vk-btn vk-btn-primary" to={`/kitchen/shop?type=${filter}`}>Shop {filter}</Link>}
    />
  )
}

function InventoryLoading() {
  return (
    <div className="inventory-grid">
      {Array.from({ length: 6 }).map((_, index) => (
        <div
          key={index}
          className="inventory-card inventory-card-skeleton"
        >
          <div className="inventory-skeleton-image" />

          <div className="inventory-skeleton-body">
            <div className="inventory-skeleton-line short" />
            <div className="inventory-skeleton-line title" />
            <div className="inventory-skeleton-box" />
            <div className="inventory-skeleton-line footer" />
          </div>
        </div>
      ))}
    </div>
  )
}

const NO_ITEMS: InventoryItem[] = []

export default function InventoryView({
  kitchenId,
  filter,
}: InventoryViewProps) {
  const [search, setSearch] = useState('')
  const inventory = useApiData(`kitchen-${kitchenId}`, async () => {
    const fetched = await InventoryApi.getInventoryByKitchenId(kitchenId)
    if (!Array.isArray(fetched)) throw new Error('Unexpected response format from server')
    return fetched
  })
  const items = inventory.data ?? NO_ITEMS
  const loading = inventory.loading
  const error = inventory.error ?? ''

  const filteredItems = useMemo(() => {
    const query = search.trim().toLowerCase()

    return items.filter(item => {
      // Used-up rows stay in the backend at 0; they are no longer in the kitchen.
      if (!isInKitchen(item)) {
        return false
      }

      const matchesFilter =
        filter === 'ingredients'
          ? item.itemType === 'INGREDIENT'
          : item.itemType === 'EQUIPMENT'

      if (!matchesFilter) {
        return false
      }

      if (!query) {
        return true
      }

      return (
        item.itemName
          ?.toLowerCase()
          .includes(query) ?? false
      )
    })
  }, [items, filter, search])

  return (
    <div className="inventory-view">
      <PageHeader
        eyebrow="Pantry"
        title={COPY[filter].title}
        subtitle={COPY[filter].subtitle}
        actions={
          <Link className="vk-btn vk-btn-secondary" to={`/kitchen/shop?type=${filter}`}>
            <span aria-hidden="true">🛒</span> Buy more
          </Link>
        }
      />

      <div className="inventory-toolbar">
        <InventorySearch
          search={search}
          placeholder={COPY[filter].search}
          onSearchChange={setSearch}
        />

        {!loading && !error && (
          <div className="inventory-results-count">
            {filteredItems.length}{' '}
            {filteredItems.length === 1
              ? 'item'
              : 'items'}
          </div>
        )}
      </div>

      {loading && items.length === 0 ? (
        <InventoryLoading />
      ) : error ? (
        <ErrorState title="Unable to load your pantry" message={error} onRetry={inventory.reload} />
      ) : filteredItems.length === 0 ? (
        <InventoryEmptyState
          searching={Boolean(search.trim())}
          filter={filter}
          onClearSearch={() => setSearch('')}
        />
      ) : (
        <div className="inventory-grid">
          {filteredItems.map(item => (
            <InventoryCard
              key={`${item.itemType}-${item.id}`}
              item={item}
            />
          ))}
        </div>
      )}
    </div>
  )
}
