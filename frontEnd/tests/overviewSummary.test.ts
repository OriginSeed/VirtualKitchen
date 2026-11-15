import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { firstName, greetingFor, pantrySummary, recentOrders, recentRecipes } from '../src/features/kitchen/overview/overviewSummary.ts'

describe('recentRecipes', () => {
  it('lists the newest first and keeps undated ones last', () => {
    const recipes = [
      { id: 1, createdAt: '2026-01-01T10:00:00Z' },
      { id: 2 },
      { id: 3, createdAt: '2026-03-01T10:00:00Z' },
      { id: 4, createdAt: '2026-02-01T10:00:00Z' },
    ]
    assert.deepEqual(recentRecipes(recipes, 3).map((recipe) => recipe.id), [3, 4, 1])
    assert.deepEqual(recentRecipes(recipes).map((recipe) => recipe.id), [3, 4, 1, 2])
  })

  it('lists the most recently updated first', () => {
    const recipes = [
      { id: 1, createdAt: '2026-01-01T10:00:00', updatedAt: '2026-07-01T10:00:00' },
      { id: 2, createdAt: '2026-05-01T10:00:00' },
      { id: 3, createdAt: '2026-03-01T10:00:00', updatedAt: '2026-03-02T10:00:00' },
      { id: 4 },
      { id: 5 },
    ]
    assert.deepEqual(recentRecipes(recipes).map((recipe) => recipe.id), [1, 2, 3, 5])
  })
})

describe('pantrySummary', () => {
  it('counts only what is still in the kitchen', () => {
    const summary = pantrySummary([
      { itemType: 'INGREDIENT', quantity: 2 },
      { itemType: 'INGREDIENT', quantity: 0 },
      { itemType: 'INGREDIENT', quantity: 1e-12 },
      { itemType: 'INGREDIENT', quantity: 3, reservedQuantity: 1 },
      { itemType: 'EQUIPMENT', quantity: 1 },
    ])
    assert.deepEqual(summary, { ingredients: 2, equipment: 1, reserved: 1 })
  })
})

describe('recentOrders', () => {
  it('merges both kinds, newest first', () => {
    const merged = recentOrders(
      [{ id: 7, timestamps: { createdAt: '2026-05-02T00:00:00Z' } }, { id: 8, timestamps: null }],
      [{ orderId: 3, createdAt: '2026-05-03T00:00:00Z' }, { orderId: 4, createdAt: '2026-05-01T00:00:00Z' }],
    )
    assert.deepEqual(merged.map((entry) => entry.key), ['shop-3', 'recipe-7', 'shop-4'])
  })
})

describe('greeting', () => {
  it('follows the time of day', () => {
    assert.equal(greetingFor(9), 'Good morning')
    assert.equal(greetingFor(14), 'Good afternoon')
    assert.equal(greetingFor(20), 'Good evening')
    assert.equal(greetingFor(2), 'Welcome back')
  })

  it('uses the first name', () => {
    assert.equal(firstName('  Ada  Lovelace '), 'Ada')
    assert.equal(firstName(undefined), '')
  })
})
