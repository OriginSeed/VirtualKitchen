import { useEffect, useId, useRef } from 'react'
import '../../../shared/components/page/page.css'
import './orderEntry.css'

export const DEMO_DESCRIPTION =
  'Walk through the ordering flow with simulated payment, preparation and delivery. No real food is cooked and nothing is charged.'

type OrderEntryDialogProps = {
  recipeName: string
  /** Only the recipe's owner can order it (the backend orders the user's own recipes). */
  canOrder: boolean
  /** The demo order is being created. */
  starting: boolean
  onTryDemo: () => void
  onClose: () => void
}

/**
 * What "Order" shows today: chef-prepared delivery is coming soon. A small "Try the demo" option walks through the
 * existing simulated flow, with a description on hover / focus so nobody mistakes it for a real order.
 */
export default function OrderEntryDialog({ recipeName, canOrder, starting, onTryDemo, onClose }: OrderEntryDialogProps) {
  const id = useId()
  const closeRef = useRef<HTMLButtonElement>(null)

  useEffect(() => {
    closeRef.current?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !starting) onClose()
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [onClose, starting])

  return (
    <div
      className="order-entry-backdrop"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget && !starting) onClose()
      }}
    >
      <div className="order-entry-dialog" role="dialog" aria-modal="true" aria-labelledby={`${id}-title`} aria-describedby={`${id}-text`}>
        <div className="order-entry-icon" aria-hidden="true">🧑‍🍳</div>
        <span className="vk-chip vk-chip-demo order-entry-tag">Coming soon</span>
        <h2 id={`${id}-title`} className="order-entry-title">Chef-prepared delivery is on its way</h2>
        <p id={`${id}-text`} className="order-entry-text">
          Soon our chefs will cook <strong>{recipeName}</strong> exactly as your recipe describes and deliver it to your door.
        </p>
        <ul className="order-entry-points">
          <li><span aria-hidden="true">📋</span> Your recipe flow is the chef’s instructions</li>
          <li><span aria-hidden="true">🧺</span> Ingredients come from your pantry, the rest from the shop</li>
          <li><span aria-hidden="true">🚚</span> Track preparation and delivery live</li>
        </ul>

        <div className="order-entry-actions">
          <button ref={closeRef} type="button" className="vk-btn vk-btn-primary" onClick={onClose} disabled={starting}>
            Got it
          </button>
        </div>

        <div className="order-entry-demo">
          {canOrder ? (
            <>
              <button
                type="button"
                className="order-entry-demo-button"
                onClick={onTryDemo}
                disabled={starting}
                aria-describedby={`${id}-demo`}
              >
                {starting ? 'Starting demo…' : 'Try the demo →'}
              </button>
              <span className="order-entry-demo-info" tabIndex={0} aria-describedby={`${id}-demo`}>
                <span aria-hidden="true">ⓘ</span>
                <span className="vk-sr-only">What is the demo?</span>
                <span id={`${id}-demo`} role="tooltip" className="order-entry-tooltip">{DEMO_DESCRIPTION}</span>
              </span>
            </>
          ) : (
            <span className="order-entry-demo-note">
              To try the demo, add this recipe to My Recipes first — you can only order your own recipes.
            </span>
          )}
        </div>
      </div>
    </div>
  )
}
