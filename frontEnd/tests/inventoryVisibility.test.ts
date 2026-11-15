import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { isInKitchen, normalizeAmount, stockLevels } from '../src/features/kitchen/inventory/inventoryVisibility.ts'

describe('isInKitchen', () => {
  it('hides used-up rows', () => {
    assert.equal(isInKitchen({ quantity: 0 }), false)
    assert.equal(isInKitchen({ quantity: 0, reservedQuantity: 0 }), false)
    assert.equal(isInKitchen({}), false)
  })

  it('hides float dust left by unit conversions', () => {
    assert.equal(isInKitchen({ quantity: 1e-12 }), false)
    assert.equal(isInKitchen({ quantity: -0.0000001 }), false)
  })

  it('keeps rows with stock, or with a reservation', () => {
    assert.equal(isInKitchen({ quantity: 0.25 }), true)
    assert.equal(isInKitchen({ quantity: 0, reservedQuantity: 2 }), true)
  })
})

describe('stockLevels', () => {
  it('derives the free amount when the backend leaves it out', () => {
    assert.deepEqual(stockLevels({ quantity: 5, reservedQuantity: 2 }), { inStock: 5, reserved: 2, free: 3 })
  })

  it('prefers the backend free amount', () => {
    assert.deepEqual(stockLevels({ quantity: 5, reservedQuantity: 2, availableQuantity: 2.5 }),
      { inStock: 5, reserved: 2, free: 2.5 })
  })

  it('never reports a negative free amount', () => {
    assert.equal(stockLevels({ quantity: 1, reservedQuantity: 3 }).free, 0)
  })
})

describe('normalizeAmount', () => {
  it('snaps missing and non-finite values to 0', () => {
    assert.equal(normalizeAmount(null), 0)
    assert.equal(normalizeAmount(Number.NaN), 0)
    assert.equal(normalizeAmount(Infinity), 0)
    assert.equal(normalizeAmount(3), 3)
  })
})
