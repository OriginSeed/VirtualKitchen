import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { RecipeOrderApi, httpStatusOf } from '../../api'
import type { RecipeOrder } from '../../types/recipeOrder'
import { useNotifications } from '../../shared/components/notifications/notificationContext'
import '../../styles/flow-ui.css'
import './recipeOrder.css'
import OrderStatusBadge from './components/OrderStatusBadge'
import OrderStepper from './components/OrderStepper'
import DemoNotice from './components/DemoNotice'
import { recipeOrderPath, resolveOrderView, type RecipeOrderView } from './model/orderView'
import ConfirmStep from './steps/ConfirmStep'
import IngredientsStep from './steps/IngredientsStep'
import PaymentStep from './steps/PaymentStep'
import ReceiptView from './steps/ReceiptView'
import TrackingView from './steps/TrackingView'
import type { OrderStepProps } from './steps/stepProps'
import { errorMessage } from '../../shared/errors'

type RecipeOrderPageProps = {
  orderId: number
  /** The URL's view segment; validated against the order's status here. */
  view?: string
  userId: number
}

type LoadError = { message: string; notFound: boolean }

/**
 * Action responses carry the whole order, but the recipe's process snapshot is only guaranteed on
 * `GET /recipe-orders/{id}` — keep the one already loaded if a response leaves it out.
 */
const mergeOrder = (previous: RecipeOrder | null, next: RecipeOrder): RecipeOrder => {
  if (!previous?.recipe?.processes || next.recipe?.processes || !next.recipe) return next
  return { ...next, recipe: { ...next.recipe, processes: previous.recipe.processes } }
}

const STEP_VIEWS: Record<RecipeOrderView, (props: OrderStepProps) => ReactNode> = {
  confirm: (props) => <ConfirmStep {...props} />,
  ingredients: (props) => <IngredientsStep {...props} />,
  payment: (props) => <PaymentStep {...props} />,
  receipt: (props) => <ReceiptView {...props} />,
  track: (props) => <TrackingView {...props} />,
}

/**
 * One recipe order (`/kitchen/recipe-orders/:orderId/:view`): loads it, keeps the URL on a page the
 * order's status allows (see model/orderView.ts), and renders that page under a stepper header.
 * The order only ever changes by replacing it with a server response; a 409 (the order moved on
 * elsewhere, or stock changed) shows the server's message and re-fetches.
 */
export default function RecipeOrderPage({ orderId, view, userId }: RecipeOrderPageProps) {
  const navigate = useNavigate()
  const { notifyError } = useNotifications()
  const [order, setOrder] = useState<RecipeOrder | null>(null)
  const [loadError, setLoadError] = useState<LoadError | null>(null)
  const [reloadKey, setReloadKey] = useState(0)

  useEffect(() => {
    let cancelled = false
    RecipeOrderApi.get(orderId)
      .then((result) => {
        if (cancelled) return
        setOrder((previous) => mergeOrder(previous, result))
        setLoadError(null)
      })
      .catch((error: unknown) => {
        if (cancelled) return
        setLoadError({
          message: errorMessage(error, 'Unable to load this order'),
          notFound: httpStatusOf(error) === 404,
        })
      })
    return () => {
      cancelled = true
    }
  }, [orderId, reloadKey])

  const onOrderChange = useCallback((next: RecipeOrder) => {
    setOrder((previous) => mergeOrder(previous, next))
  }, [])

  const onRefetch = useCallback(async () => {
    try {
      const result = await RecipeOrderApi.get(orderId)
      setOrder((previous) => mergeOrder(previous, result))
    } catch (error) {
      console.error('Unable to refresh the order:', error)
    }
  }, [orderId])

  const onError = useCallback((error: unknown) => {
    notifyError(errorMessage(error, 'Something went wrong'))
    // A conflict means our copy is stale: show what the server has now.
    if (httpStatusOf(error) === 409) void onRefetch()
  }, [notifyError, onRefetch])

  const onNavigate = useCallback((next: RecipeOrderView) => navigate(recipeOrderPath(orderId, next)), [navigate, orderId])

  if (!order) {
    if (loadError) {
      return (
        <div className="ro-page">
          <div className="ro-shell">
            <div className="ro-card ro-state" role="alert">
              <div className="ro-empty-icon" aria-hidden>{loadError.notFound ? '🔍' : '⚠️'}</div>
              <strong>{loadError.notFound ? 'Order not found' : 'This order could not be loaded'}</strong>
              <p className="ro-muted">{loadError.notFound ? 'It may have been removed, or it belongs to another account.' : loadError.message}</p>
              <div className="ro-actions ro-actions-center">
                <Link to="/kitchen/orders" className="ro-btn ro-btn-ghost">View all orders</Link>
                {!loadError.notFound && (
                  <button
                    type="button"
                    className="ro-btn ro-btn-primary"
                    onClick={() => {
                      setLoadError(null)
                      setReloadKey((value) => value + 1)
                    }}
                  >
                    Try again
                  </button>
                )}
              </div>
            </div>
          </div>
        </div>
      )
    }
    return (
      <div className="ro-page">
        <div className="ro-shell">
          <div className="ro-card ro-state" role="status">Loading order…</div>
        </div>
      </div>
    )
  }

  const resolved = resolveOrderView(order.status, view)
  if (resolved.redirect) return <Navigate to={recipeOrderPath(order.id, resolved.view)} replace />

  const stepProps: OrderStepProps = { order, userId, onOrderChange, onNavigate, onError, onRefetch }

  return (
    <div className="ro-page">
      <div className="ro-shell">
        <header className="ro-header">
          <div>
            <Link to="/kitchen/orders" className="ro-back">← All orders</Link>
            <h1 className="ro-page-title">
              Recipe order <span className="ro-code">{order.orderCode}</span> <span className="vk-chip vk-chip-demo">Demo</span>
            </h1>
          </div>
          <OrderStatusBadge status={order.status} />
        </header>

        <DemoNotice variant="strong" icon="🧪">
          This is a demo order. Chef-prepared delivery is coming soon — payment, preparation and delivery here are
          simulated, nothing is cooked and nothing is charged.
        </DemoNotice>

        <OrderStepper orderId={order.id} status={order.status} view={resolved.view} />

        {/* Keyed by view: each page starts from the current order rather than a previous page's form state. */}
        <div key={resolved.view}>{STEP_VIEWS[resolved.view](stepProps)}</div>
      </div>
    </div>
  )
}
