import { useEffect, useState } from 'react'
import { CART_CHANGED_EVENT, cartLineCount, getCartStorageKey, loadCart } from './cartStorage'

/** How many different items are in the user's cart, kept current as the cart changes in this or another tab. */
export function useCartCount(userId: number): number {
  const [count, setCount] = useState(() => cartLineCount(loadCart(userId)))

  useEffect(() => {
    const refresh = () => setCount(cartLineCount(loadCart(userId)))
    refresh()
    const onChange = (event: Event) => {
      if ((event as CustomEvent<number>).detail === userId) refresh()
    }
    const onStorage = (event: StorageEvent) => {
      if (event.key === null || event.key === getCartStorageKey(userId)) refresh()
    }
    window.addEventListener(CART_CHANGED_EVENT, onChange)
    window.addEventListener('storage', onStorage)
    return () => {
      window.removeEventListener(CART_CHANGED_EVENT, onChange)
      window.removeEventListener('storage', onStorage)
    }
  }, [userId])

  return count
}
