import { useEffect, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom'
import { RecipeOrderApi } from '../../api'
import ShopView from '../../features/kitchen/shop/ShopView'
import { recipeOrderPath } from '../../features/recipe-order'
import type { ShopResumeState } from '../../shared/cart/cartStorage'
import { useSignedIn } from '../session/signedIn'

/** The shop; with `?resumeRecipeOrder=<id>` it is shopping for a recipe order's missing ingredients. */
export default function ShopRoute() {
  const { user } = useSignedIn()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()

  const resumeParam = Number(searchParams.get('resumeRecipeOrder'))
  const resumeOrderId = Number.isInteger(resumeParam) && resumeParam > 0 ? resumeParam : null
  const stateCode = (location.state as ShopResumeState | null)?.recipeOrderCode
  const [fetchedCode, setFetchedCode] = useState<{ orderId: number; code: string } | null>(null)

  useEffect(() => {
    // The code normally comes with the navigation; after a reload it's looked up.
    if (resumeOrderId == null || stateCode) return
    let cancelled = false
    RecipeOrderApi.get(resumeOrderId)
      .then((order) => {
        if (!cancelled) setFetchedCode({ orderId: resumeOrderId, code: order.orderCode })
      })
      .catch(() => {
        // The banner falls back to the order id.
      })
    return () => {
      cancelled = true
    }
  }, [resumeOrderId, stateCode])

  const resumeOrderCode = stateCode ?? (fetchedCode?.orderId === resumeOrderId ? fetchedCode.code : null)

  const typeParam = searchParams.get('type')
  const initialFilter = typeParam === 'ingredients' || typeParam === 'equipment' ? typeParam : 'all'
  // Every visit to `?cart=open` (the navbar cart) opens the drawer, even when the shop is already showing.
  const cartRequest = searchParams.get('cart') === 'open' ? location.key : null

  return (
    <ShopView
      // Remount when the resume target changes, so the cart is re-read from storage.
      key={resumeOrderId ?? 'shop'}
      userId={user.id}
      initialFilter={initialFilter}
      cartRequest={cartRequest}
      resumeRecipeOrder={resumeOrderId != null ? { orderId: resumeOrderId, orderCode: resumeOrderCode } : undefined}
      // Back to the order's ingredient check (which re-checks stock) instead of the order list.
      onOrderPlaced={() => navigate(resumeOrderId != null ? recipeOrderPath(resumeOrderId, 'ingredients') : '/kitchen/orders')}
    />
  )
}
