/**
 * Which inventory rows the pantry shows, and how their amounts read.
 *
 * The backend keeps a row once its stock is used up (recipe orders consume quantities in place), so a row can sit
 * at 0 — or at float dust such as 1e-12 after unit conversions. Those rows are not "in" the kitchen any more and
 * are hidden, unless part of them is still reserved for an order.
 */

/** Amounts at or below this are treated as nothing left. */
export const STOCK_EPSILON = 1e-9

export type StockFields = {
  quantity?: number | null
  reservedQuantity?: number | null
  availableQuantity?: number | null
}

/** A finite, non-negative amount with float dust snapped to 0. */
export const normalizeAmount = (value: number | null | undefined): number => {
  if (value == null || !Number.isFinite(value) || value <= STOCK_EPSILON) return 0
  return value
}

export type StockLevels = {
  /** Everything held in the kitchen, reserved part included. */
  inStock: number
  /** Held for recipe orders awaiting payment or in preparation. */
  reserved: number
  /** Free for new orders. */
  free: number
}

export const stockLevels = (item: StockFields): StockLevels => {
  const inStock = normalizeAmount(item.quantity)
  const reserved = normalizeAmount(item.reservedQuantity)
  const free = item.availableQuantity != null
    ? normalizeAmount(item.availableQuantity)
    : Math.max(0, inStock - reserved)
  return { inStock, reserved, free }
}

/** Whether the pantry lists this row: it still holds something, or something of it is reserved. */
export const isInKitchen = (item: StockFields): boolean => {
  const { inStock, reserved } = stockLevels(item)
  return inStock > 0 || reserved > 0
}
