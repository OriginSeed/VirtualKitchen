import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { OrderApi, RecipeOrderApi, type OrderResponse } from '../../../api'
import type { RecipeOrder } from '../../../types/recipeOrder'
import { formatServings, isPaidStatus, OrderStatusBadge, OrderThumb, recipeOrderPath, timeValue } from '../../recipe-order'
import { formatDateTime } from '../../../shared/format/date'
import { formatMoney } from '../../../shared/format/money'
import { errorMessage } from '../../../shared/errors'
import { EmptyState, PageHeader } from '../../../shared/components/page'
import './OrderHistoryView.css'

interface OrderHistoryViewProps {
  userId: number
}

type OrderTab = 'all' | 'recipe' | 'shop'

type OrderEntry =
  | { kind: 'recipe'; key: string; time: number; order: RecipeOrder }
  | { kind: 'shop'; key: string; time: number; order: OrderResponse }

const TABS: { id: OrderTab; label: string }[] = [
  { id: 'all', label: 'All Orders' },
  { id: 'recipe', label: 'Recipe Orders (demo)' },
  { id: 'shop', label: 'Shop Orders' },
]

function RecipeOrderCard({ order }: { order: RecipeOrder }) {
  const name = order.recipe?.name ?? 'Recipe'
  const paid = isPaidStatus(order.status)

  return (
    <article className="order-card recipe-order-card">
      <OrderThumb src={order.recipe?.thumbnailUrl} alt={name} icon={order.recipe?.fallbackIcon} size="md" />

      <div className="recipe-order-main">
        <div className="recipe-order-top">
          <span className="order-kind order-kind-recipe">Recipe order</span>
          <span className="vk-chip vk-chip-demo" title="Demo: preparation and delivery are simulated">Demo</span>
          <span className="order-code">{order.orderCode}</span>
        </div>
        <h3>{name}</h3>
        <p>
          {formatServings(order.servings, order.baseServings)} · {formatDateTime(order.timestamps?.createdAt, 'N/A')}
        </p>
      </div>

      <div className="recipe-order-side">
        <OrderStatusBadge status={order.status} />
        <strong className="recipe-order-amount">{formatMoney(order.pricing?.total, order.pricing?.currency)}</strong>
        <div className="recipe-order-actions">
          {paid || order.status === 'CANCELLED' ? (
            <>
              <Link className="ro-btn ro-btn-ghost" to={recipeOrderPath(order.id, 'receipt')}>View receipt</Link>
              {paid && <Link className="ro-btn ro-btn-primary" to={recipeOrderPath(order.id, 'track')}>Track order</Link>}
            </>
          ) : (
            // Not paid yet: there's nothing to track — pick the order up where it was left.
            <Link className="ro-btn ro-btn-primary" to={recipeOrderPath(order.id)}>Continue order</Link>
          )}
        </div>
      </div>
    </article>
  )
}

function ShopOrderCard({ order }: { order: OrderResponse }) {
  return (
    <article className="order-card">
      <header className="order-card-header">
        <div>
          <div className="recipe-order-top">
            <span className="order-kind order-kind-shop">Shop order</span>
            {order.orderCode && <span className="order-code">{order.orderCode}</span>}
          </div>
          <h3>Order #{order.orderId}</h3>
          <p>{formatDateTime(order.createdAt, 'N/A')}</p>
        </div>
        <div className="order-status-block">
          <span className={`status-badge payment-${order.paymentStatus.toLowerCase()}`}>Payment: {order.paymentStatus}</span>
          <span className={`status-badge order-${order.orderStatus.toLowerCase()}`}>Order: {order.orderStatus}</span>
        </div>
      </header>

      <div className="order-items">
        {order.items.map((item) => (
          <div className="order-item-row" key={`${order.orderId}-${item.itemType}-${item.itemId}`}>
            <span>{item.itemName}</span>
            <span>
              {item.quantity} {item.unit}
            </span>
            <span>{formatMoney(item.price)}</span>
            <span>{formatMoney(item.subTotal)}</span>
          </div>
        ))}
      </div>

      <footer className="order-card-footer">Total: {formatMoney(order.totalAmount)}</footer>
    </article>
  )
}

/**
 * Order history: shop orders and recipe orders, together (newest first) or one kind at a time.
 * Both lists load in parallel; if one fails the other still shows, with the failure noted.
 */
export default function OrderHistoryView({ userId }: OrderHistoryViewProps) {
  const [tab, setTab] = useState<OrderTab>('all')
  const [shopOrders, setShopOrders] = useState<OrderResponse[] | null>(null)
  const [recipeOrders, setRecipeOrders] = useState<RecipeOrder[] | null>(null)
  const [shopError, setShopError] = useState('')
  const [recipeError, setRecipeError] = useState('')
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    void Promise.allSettled([OrderApi.getOrdersByUser(userId), RecipeOrderApi.list()]).then(([shop, recipe]) => {
      if (cancelled) return
      if (shop.status === 'fulfilled') {
        setShopOrders(Array.isArray(shop.value) ? shop.value : [])
        setShopError('')
      } else {
        console.error('Failed to fetch shop orders:', shop.reason)
        setShopOrders([])
        setShopError(errorMessage(shop.reason, 'Failed to load shop orders'))
      }
      if (recipe.status === 'fulfilled') {
        setRecipeOrders(Array.isArray(recipe.value) ? recipe.value : [])
        setRecipeError('')
      } else {
        console.error('Failed to fetch recipe orders:', recipe.reason)
        setRecipeOrders([])
        setRecipeError(errorMessage(recipe.reason, 'Failed to load recipe orders'))
      }
    })
    return () => {
      cancelled = true
    }
  }, [userId, reloadKey])

  const loading = shopOrders == null || recipeOrders == null

  const entries = useMemo<OrderEntry[]>(() => {
    const recipe: OrderEntry[] = (recipeOrders ?? []).map((order) => ({
      kind: 'recipe', key: `recipe-${order.id}`, time: timeValue(order.timestamps?.createdAt), order,
    }))
    const shop: OrderEntry[] = (shopOrders ?? []).map((order) => ({
      kind: 'shop', key: `shop-${order.orderId}`, time: timeValue(order.createdAt), order,
    }))
    const selected = tab === 'recipe' ? recipe : tab === 'shop' ? shop : [...recipe, ...shop]
    return [...selected].sort((a, b) => b.time - a.time)
  }, [recipeOrders, shopOrders, tab])

  const errors = [tab !== 'shop' && recipeError, tab !== 'recipe' && shopError].filter(Boolean) as string[]
  const counts: Record<OrderTab, number> = {
    all: (recipeOrders?.length ?? 0) + (shopOrders?.length ?? 0),
    recipe: recipeOrders?.length ?? 0,
    shop: shopOrders?.length ?? 0,
  }

  const retry = () => {
    setShopOrders(null)
    setRecipeOrders(null)
    setReloadKey((value) => value + 1)
  }

  return (
    <div className="order-history-view">
      <PageHeader
        eyebrow="My Kitchen"
        title="Orders"
        subtitle="Your recipe orders and shop orders, with their totals, payment status and progress."
      />

      <div className="order-tabs" role="tablist" aria-label="Order type">
        {TABS.map((item) => (
          <button
            key={item.id}
            type="button"
            role="tab"
            aria-selected={tab === item.id}
            className={`order-tab${tab === item.id ? ' active' : ''}`}
            onClick={() => setTab(item.id)}
          >
            {item.label}
            {!loading && <span className="order-tab-count">{counts[item.id]}</span>}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="vk-loading-text" role="status">Loading orders…</div>
      ) : (
        <>
          {errors.length > 0 && (
            <div className="order-state order-error" role="alert">
              {errors.join(' · ')}{' '}
              <button type="button" className="order-retry" onClick={retry}>Try again</button>
            </div>
          )}

          {entries.length === 0 ? (
            errors.length === 0 && (
              tab === 'recipe' ? (
                <EmptyState
                  icon="🧾"
                  title="No recipe orders yet"
                  text="Open one of your recipes and choose “Order” to try the demo ordering flow."
                  actions={<Link className="vk-btn vk-btn-primary" to="/kitchen/recipes">Go to My Recipes</Link>}
                />
              ) : (
                <EmptyState
                  icon="🛒"
                  title={tab === 'shop' ? 'No shop orders yet' : 'No orders yet'}
                  text="Ingredients and equipment you buy in the shop show up here."
                  actions={<Link className="vk-btn vk-btn-primary" to="/kitchen/shop">Visit the shop</Link>}
                />
              )
            )
          ) : (
            <div className="order-history-list">
              {entries.map((entry) =>
                entry.kind === 'recipe' ? <RecipeOrderCard key={entry.key} order={entry.order} /> : <ShopOrderCard key={entry.key} order={entry.order} />,
              )}
            </div>
          )}
        </>
      )}
    </div>
  )
}
