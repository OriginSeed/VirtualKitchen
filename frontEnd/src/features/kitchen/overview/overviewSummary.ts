/**
 * What the My Kitchen overview shows, worked out from the lists the existing APIs return. Pure (no React, no
 * fetching) so it is unit-tested.
 */
import { isInKitchen, stockLevels, type StockFields } from '../inventory/inventoryVisibility.ts'
import { compareByTime, recipeUpdatedTime } from '../../recipes/recipeListing.ts'

const time = (value: string | null | undefined): number => {
  const parsed = value ? Date.parse(value) : Number.NaN
  return Number.isFinite(parsed) ? parsed : 0
}

/**
 * The `limit` most recently updated recipes (last change, else creation; recipes saved before dates were recorded
 * come after every dated one, highest id first).
 */
export const recentRecipes = <T extends { id: number; createdAt?: string | null; updatedAt?: string | null }>(
  recipes: T[],
  limit = 4,
): T[] =>
  recipes
    .map((recipe) => ({ recipe, id: recipe.id, time: recipeUpdatedTime(recipe) }))
    .sort((a, b) => compareByTime(b, a))
    .slice(0, limit)
    .map((entry) => entry.recipe)

export type PantrySummary = {
  ingredients: number
  equipment: number
  /** Rows with part of their stock held for a recipe order. */
  reserved: number
}

export const pantrySummary = (items: Array<StockFields & { itemType?: string }>): PantrySummary => {
  const summary: PantrySummary = { ingredients: 0, equipment: 0, reserved: 0 }
  for (const item of items) {
    if (!isInKitchen(item)) continue
    if (item.itemType === 'EQUIPMENT') summary.equipment += 1
    else if (item.itemType === 'INGREDIENT') summary.ingredients += 1
    if (stockLevels(item).reserved > 0) summary.reserved += 1
  }
  return summary
}

export type RecentOrder<R, S> =
  | { kind: 'recipe'; key: string; time: number; order: R }
  | { kind: 'shop'; key: string; time: number; order: S }

/** Recipe and shop orders together, newest first. */
export const recentOrders = <
  R extends { id: number; timestamps?: { createdAt?: string | null } | null },
  S extends { orderId: number; createdAt?: string | null },
>(recipeOrders: R[], shopOrders: S[], limit = 3): RecentOrder<R, S>[] => {
  const entries: RecentOrder<R, S>[] = [
    ...recipeOrders.map((order) => ({ kind: 'recipe' as const, key: `recipe-${order.id}`, time: time(order.timestamps?.createdAt), order })),
    ...shopOrders.map((order) => ({ kind: 'shop' as const, key: `shop-${order.orderId}`, time: time(order.createdAt), order })),
  ]
  return entries.sort((a, b) => b.time - a.time).slice(0, limit)
}

/** "Good morning" etc. for the welcome line, from the local hour. */
export const greetingFor = (hour: number): string => {
  if (hour < 5) return 'Welcome back'
  if (hour < 12) return 'Good morning'
  if (hour < 18) return 'Good afternoon'
  return 'Good evening'
}

export const firstName = (name: string | null | undefined): string => name?.trim().split(/\s+/)[0] ?? ''
