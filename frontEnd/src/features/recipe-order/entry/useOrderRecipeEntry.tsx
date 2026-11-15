import { useCallback, useState } from 'react'
import { useStartRecipeOrder } from '../useStartRecipeOrder'
import OrderEntryDialog from './OrderEntryDialog'

export type OrderRequest = {
  recipeId: number
  recipeName: string
  /** Only the owner can order (the demo included); others are told to add the recipe first. */
  isOwner: boolean
  /** The recipe's serving count, when the caller already knows it. */
  servings?: number | null
  /**
   * Runs before the demo order starts — the Recipe Tool saves and checks readiness here. Resolves false to stop
   * (it explains why itself).
   */
  ensureReady?: () => Promise<boolean>
}

/**
 * "Order" everywhere: opens the Coming-soon dialog, whose "Try the demo" starts the existing simulated order.
 * Render `dialog` once; call `requestOrder` from any Order button.
 */
export function useOrderRecipeEntry() {
  const { startOrder, orderingRecipeId } = useStartRecipeOrder()
  const [request, setRequest] = useState<OrderRequest | null>(null)

  const requestOrder = useCallback((next: OrderRequest) => setRequest(next), [])
  const close = useCallback(() => setRequest(null), [])

  const tryDemo = async () => {
    if (!request) return
    const current = request
    // The readiness check may open its own dialog; this one steps aside first.
    setRequest(null)
    if (current.ensureReady && !(await current.ensureReady())) return
    await startOrder(current.recipeId, current.servings)
  }

  const dialog = request ? (
    <OrderEntryDialog
      recipeName={request.recipeName}
      canOrder={request.isOwner}
      starting={orderingRecipeId === request.recipeId}
      onTryDemo={() => void tryDemo()}
      onClose={close}
    />
  ) : null

  return { requestOrder, dialog, orderingRecipeId }
}
