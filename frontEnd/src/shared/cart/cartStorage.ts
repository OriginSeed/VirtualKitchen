/**
 * The shop cart, persisted per user in localStorage (`virtual-kitchen.cart.<userId>`). Shared by the
 * shop (ShopView) and the recipe-order ingredient check, which adds missing ingredients to
 * the same cart before sending the user to the shop.
 *
 * Pure apart from the injectable storage, and free of runtime imports, so node:test can import it.
 */

import type { ItemType, UnitType } from '../../types/kitchen'


export interface CartItem {
  itemId: number
  itemType: ItemType
  itemName: string
  quantity: number
  unit: UnitType
  /** Price per unit (shop prices are frontend defaults — see defaultShopPrice). */
  price: number
}

/**
 * Router state sent with `/kitchen/shop?resumeRecipeOrder=<id>` (the recipe-order ingredient check's
 * "Buy missing ingredients"), so the shop's banner can name the order without fetching it.
 */
export type ShopResumeState = {
  recipeOrderCode?: string
}

/** The subset of the Web Storage API the cart needs (localStorage, or a fake in tests). */
export type CartStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

/** Demo prices in INR (the catalog has no prices yet). */
const DEFAULT_INGREDIENT_PRICE = 60
const DEFAULT_EQUIPMENT_PRICE = 999

/** The shop's price rule: the item's base price when it has one, else the default for its type. */
export const defaultShopPrice = (itemType: ItemType, basePrice?: number | null): number =>
  basePrice ?? (itemType === 'EQUIPMENT' ? DEFAULT_EQUIPMENT_PRICE : DEFAULT_INGREDIENT_PRICE)

export const getCartStorageKey = (userId: number) => `virtual-kitchen.cart.${userId}`

export const getCartItemKey = (itemType: ItemType, itemId: number) => `${itemType}-${itemId}`

export const toCartUnit = (value: unknown): UnitType => {
  const normalized = String(value ?? '').trim().toUpperCase()

  if (normalized === 'KG') return 'KG'
  if (normalized === 'GRAM') return 'GRAM'
  if (normalized === 'LITER') return 'LITER'
  if (normalized === 'ML') return 'ML'

  return 'COUNT'
}

/** Parsed cart contents with anything malformed dropped. */
export const normalizeCartItems = (value: unknown): CartItem[] => {
  if (!Array.isArray(value)) return []

  return value
    .map((entry) => {
      const raw = (entry && typeof entry === 'object' ? entry : {}) as Record<string, unknown>
      return {
        itemId: Number(raw.itemId),
        itemType: (raw.itemType === 'EQUIPMENT' ? 'EQUIPMENT' : 'INGREDIENT') as ItemType,
        itemName: String(raw.itemName ?? ''),
        quantity: Number(raw.quantity),
        unit: toCartUnit(raw.unit),
        price: Number(raw.price),
      }
    })
    .filter((entry) => Number.isFinite(entry.itemId) && entry.itemName && entry.quantity > 0 && entry.price >= 0)
}

const defaultStorage = (): CartStorage | null => {
  try {
    return typeof localStorage === 'undefined' ? null : localStorage
  } catch {
    // Storage can be blocked entirely (privacy settings) — the cart then just doesn't persist.
    return null
  }
}

export const loadCart = (userId: number, storage: CartStorage | null = defaultStorage()): CartItem[] => {
  try {
    const raw = storage?.getItem(getCartStorageKey(userId))
    return raw ? normalizeCartItems(JSON.parse(raw)) : []
  } catch {
    return []
  }
}

/**
 * Fired on `window` (detail: the user id) whenever this tab saves or clears a cart, so other parts of the page —
 * the navbar's cart badge — follow along. Other tabs hear about it through the `storage` event instead.
 */
export const CART_CHANGED_EVENT = 'virtual-kitchen:cart-changed'

const announceCartChange = (userId: number) => {
  if (typeof window === 'undefined' || typeof CustomEvent === 'undefined') return
  window.dispatchEvent(new CustomEvent(CART_CHANGED_EVENT, { detail: userId }))
}

export const saveCart = (userId: number, items: CartItem[], storage: CartStorage | null = defaultStorage()): void => {
  try {
    storage?.setItem(getCartStorageKey(userId), JSON.stringify(items))
  } catch {
    // Quota/blocked storage: the in-memory cart still works for this visit.
  }
  announceCartChange(userId)
}

export const clearCart = (userId: number, storage: CartStorage | null = defaultStorage()): void => {
  try {
    storage?.removeItem(getCartStorageKey(userId))
  } catch {
    // Nothing to clear.
  }
  announceCartChange(userId)
}

/** How many different things are in the cart (the badge counts lines, not 500 grams of flour). */
export const cartLineCount = (items: CartItem[]): number => items.length

/**
 * How a line is merged into a cart that already holds the same item:
 * - 'add': its quantity is added (the shop's "+ Add one more"), keeping the existing unit;
 * - 'atLeast': the cart ends up holding at least this quantity in this unit — re-adding the same
 *   shortage twice doesn't double it. A different unit can't be compared without a conversion, so
 *   the line replaces the existing quantity and unit.
 */
export type CartMergeMode = 'add' | 'atLeast'

export const mergeCartLine = (items: CartItem[], line: CartItem, mode: CartMergeMode = 'add'): CartItem[] => {
  const key = getCartItemKey(line.itemType, line.itemId)
  const existing = items.find((item) => getCartItemKey(item.itemType, item.itemId) === key)

  if (!existing) return [...items, line]

  return items.map((item) => {
    if (item !== existing) return item
    if (mode === 'add') return { ...item, quantity: item.quantity + line.quantity }
    if (item.unit === line.unit) return { ...item, quantity: Math.max(item.quantity, line.quantity) }
    return { ...item, quantity: line.quantity, unit: line.unit }
  })
}
