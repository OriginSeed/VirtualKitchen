import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { InventoryApi, OrderApi, RecipeApi, RecipeOrderApi, type Recipe } from '../../../api'
import { useCartCount } from '../../../shared/cart/useCartCount'
import { ErrorState, PageHeader } from '../../../shared/components/page'
import { formatDateTime } from '../../../shared/format/date'
import { formatMoney } from '../../../shared/format/money'
import { useApiData } from '../../../shared/hooks/useApiData'
import { OrderStatusBadge, OrderThumb, recipeOrderPath } from '../../recipe-order'
import { ImageZoomButton } from '../../../shared/components/image-viewer/ZoomableImage'
import CatalogItemImage from '../components/CatalogItemImage'
import { firstName, greetingFor, pantrySummary, recentOrders, recentRecipes } from './overviewSummary'
import './OverviewPage.css'

interface OverviewPageProps {
  userId: number
  userName: string
  kitchenId: number
  /** Opens a recipe (the app routes it to the Recipe Process). */
  onOpenRecipe: (recipeId: number) => void
}

/** One dashboard card: a title row with a "view all" link, then its body (which loads on its own). */
function OverviewCard({ title, icon, link, className = '', children }: {
  title: string
  icon: string
  link?: { to: string; label: string }
  className?: string
  children: ReactNode
}) {
  return (
    <section className={`overview-card ${className}`} aria-label={title}>
      <header className="overview-card-header">
        <h2><span aria-hidden="true">{icon}</span> {title}</h2>
        {link && <Link className="overview-card-link" to={link.to}>{link.label} →</Link>}
      </header>
      <div className="overview-card-body">{children}</div>
    </section>
  )
}

function CardLoading({ rows = 3 }: { rows?: number }) {
  return (
    <div className="overview-loading" role="status">
      <span className="vk-sr-only">Loading…</span>
      {Array.from({ length: rows }, (_, index) => <div key={index} className="overview-loading-row" aria-hidden="true" />)}
    </div>
  )
}

/** The first-visit welcome: what Virtual Kitchen does, in the order the user will do it. */
function FirstRecipeHero() {
  const steps = [
    { icon: '🧩', title: 'Create', text: 'Describe a dish and let AI draft the flow, or build it step by step.' },
    { icon: '▶', title: 'Visualize', text: 'Read it as a recipe, or play it as a narrated cook mode.' },
    { icon: '🌍', title: 'Share', text: 'Publish it so anyone can follow it — and add theirs to your kitchen.' },
    { icon: '🧾', title: 'Order', text: 'Chef-prepared delivery is coming soon; try the demo today.' },
  ]
  return (
    <section className="overview-hero" aria-label="Get started">
      <div className="overview-hero-copy">
        <h2>Create your first recipe</h2>
        <p>Your recipes become step-by-step flows that anyone can understand and cook.</p>
        <div className="overview-hero-actions">
          <Link className="vk-btn vk-btn-primary" to="/kitchen/recipes?create=1">+ New recipe</Link>
          <Link className="vk-btn vk-btn-secondary" to="/kitchen/discover">Browse public recipes</Link>
        </div>
      </div>
      <ol className="overview-hero-steps">
        {steps.map((step) => (
          <li key={step.title}>
            <span className="overview-hero-step-icon" aria-hidden="true">{step.icon}</span>
            <span><strong>{step.title}</strong>{step.text}</span>
          </li>
        ))}
      </ol>
    </section>
  )
}

function RecentRecipesCard({ recipes, onOpenRecipe }: { recipes: Recipe[]; onOpenRecipe: (id: number) => void }) {
  return (
    <ul className="overview-recipe-list">
      {recentRecipes(recipes).map((recipe) => (
        <li key={recipe.id} className="overview-recipe-item">
          <button type="button" className="overview-recipe" onClick={() => onOpenRecipe(recipe.id)}>
            <span className="overview-recipe-thumb">
              <CatalogItemImage src={recipe.thumbnailUrl} alt="" fallbackIcon="🍳" zoomable={false} />
            </span>
            <span className="overview-recipe-text">
              <strong>{recipe.name}</strong>
              <small>{recipe.description || 'No description yet'}</small>
            </span>
            <span className={`vk-chip ${recipe.visibility === 'PUBLIC' ? 'vk-chip-success' : ''}`}>
              {recipe.visibility === 'PUBLIC' ? 'Public' : 'Private'}
            </span>
          </button>
          {recipe.thumbnailUrl && (
            <ImageZoomButton className="overview-recipe-zoom" src={recipe.thumbnailUrl} alt={recipe.name} caption={recipe.name} />
          )}
        </li>
      ))}
    </ul>
  )
}

/**
 * My Kitchen's front page: a welcome, quick actions, and a glance at recipes, pantry, orders and cart. Each card
 * loads separately, so one failing request only affects its own card.
 */
export default function OverviewPage({ userId, userName, kitchenId, onOpenRecipe }: OverviewPageProps) {
  const recipes = useApiData(`overview-recipes-${userId}`, async () => (await RecipeApi.getRecipesByUserId(userId)) ?? [])
  const pantry = useApiData(`overview-pantry-${kitchenId}`, () => InventoryApi.getInventoryByKitchenId(kitchenId))
  const orders = useApiData(`overview-orders-${userId}`, async () => {
    const [recipe, shop] = await Promise.allSettled([RecipeOrderApi.list(), OrderApi.getOrdersByUser(userId)])
    if (recipe.status === 'rejected' && shop.status === 'rejected') throw recipe.reason
    return recentOrders(
      recipe.status === 'fulfilled' && Array.isArray(recipe.value) ? recipe.value : [],
      shop.status === 'fulfilled' && Array.isArray(shop.value) ? shop.value : [],
    )
  })
  const cartCount = useCartCount(userId)

  const name = firstName(userName)
  const noRecipesYet = recipes.data != null && recipes.data.length === 0
  const summary = pantry.data ? pantrySummary(pantry.data) : null

  return (
    <div className="vk-page overview-page">
      <PageHeader
        eyebrow="My Kitchen"
        title={`${greetingFor(new Date().getHours())}${name ? `, ${name}` : ''}`}
        subtitle="Pick up where you left off, or start something new."
        actions={
          <>
            <Link className="vk-btn vk-btn-primary" to="/kitchen/recipes?create=1">+ New recipe</Link>
            <Link className="vk-btn vk-btn-secondary" to="/kitchen/discover">Discover recipes</Link>
          </>
        }
      />

      {noRecipesYet && <FirstRecipeHero />}

      <div className="overview-grid">
        {!noRecipesYet && (
          <OverviewCard title="Recent recipes" icon="📖" className="overview-card-wide" link={{ to: '/kitchen/recipes', label: 'All recipes' }}>
            {recipes.loading && !recipes.data ? <CardLoading rows={4} />
              : recipes.error ? <ErrorState compact title="Couldn’t load your recipes" message={recipes.error} onRetry={recipes.reload} />
                : <RecentRecipesCard recipes={recipes.data ?? []} onOpenRecipe={onOpenRecipe} />}
          </OverviewCard>
        )}

        <OverviewCard title="Pantry" icon="🥕" link={{ to: '/kitchen/ingredients', label: 'Open pantry' }}>
          {pantry.loading && !pantry.data ? <CardLoading rows={2} />
            : pantry.error || !summary ? <ErrorState compact title="Couldn’t load your pantry" message={pantry.error ?? undefined} onRetry={pantry.reload} />
              : (
                <>
                  <div className="overview-stats">
                    <Link to="/kitchen/ingredients" className="overview-stat">
                      <strong>{summary.ingredients}</strong><span>ingredients</span>
                    </Link>
                    <Link to="/kitchen/equipment" className="overview-stat">
                      <strong>{summary.equipment}</strong><span>equipment</span>
                    </Link>
                  </div>
                  <p className="overview-note">
                    {summary.reserved > 0
                      ? `${summary.reserved} ${summary.reserved === 1 ? 'item is' : 'items are'} partly reserved for an order.`
                      : summary.ingredients + summary.equipment === 0
                        ? 'Your pantry is empty — stock it from the shop.'
                        : 'Nothing is reserved for orders right now.'}
                  </p>
                </>
              )}
        </OverviewCard>

        <OverviewCard title="Cart" icon="🛒" link={{ to: '/kitchen/shop', label: 'Shop' }}>
          <div className="overview-stats">
            <div className="overview-stat is-static">
              <strong>{cartCount}</strong><span>{cartCount === 1 ? 'item in cart' : 'items in cart'}</span>
            </div>
          </div>
          <p className="overview-note">
            {cartCount > 0
              ? <Link to="/kitchen/shop?cart=open">Review your cart and check out →</Link>
              : 'Buy ingredients and equipment to stock your pantry.'}
          </p>
        </OverviewCard>

        <OverviewCard title="Recent orders" icon="🧾" className="overview-card-wide" link={{ to: '/kitchen/orders', label: 'All orders' }}>
          {orders.loading && !orders.data ? <CardLoading rows={3} />
            : orders.error ? <ErrorState compact title="Couldn’t load your orders" message={orders.error} onRetry={orders.reload} />
              : orders.data && orders.data.length > 0 ? (
                <ul className="overview-order-list">
                  {orders.data.map((entry) => entry.kind === 'recipe' ? (
                    <li key={entry.key}>
                      <Link className="overview-order" to={recipeOrderPath(entry.order.id)}>
                        <OrderThumb src={entry.order.recipe?.thumbnailUrl} alt="" icon={entry.order.recipe?.fallbackIcon} size="sm" zoomable={false} />
                        <span className="overview-order-text">
                          <strong>{entry.order.recipe?.name ?? 'Recipe'} <span className="vk-chip vk-chip-demo">Demo</span></strong>
                          <small>{entry.order.orderCode} · {formatDateTime(entry.order.timestamps?.createdAt, '')}</small>
                        </span>
                        <OrderStatusBadge status={entry.order.status} />
                      </Link>
                    </li>
                  ) : (
                    <li key={entry.key}>
                      <Link className="overview-order" to="/kitchen/orders">
                        <span className="overview-order-icon" aria-hidden="true">🛒</span>
                        <span className="overview-order-text">
                          <strong>Shop order {entry.order.orderCode ?? `#${entry.order.orderId}`}</strong>
                          <small>{entry.order.items.length} {entry.order.items.length === 1 ? 'item' : 'items'} · {formatDateTime(entry.order.createdAt, '')}</small>
                        </span>
                        <span className="overview-order-amount">{formatMoney(entry.order.totalAmount)}</span>
                      </Link>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="overview-note">No orders yet. Your shop purchases and recipe orders will show up here.</p>
              )}
        </OverviewCard>
      </div>
    </div>
  )
}
