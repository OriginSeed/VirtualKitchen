import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { isRecipeSort, searchRecipes, sortRecipes } from '../src/features/recipes/recipeListing.ts'

const recipes = [
  { id: 1, name: 'banana bread', description: 'Sweet loaf', createdAt: '2026-02-01T00:00:00Z' },
  { id: 2, name: 'Aglio e olio', description: 'Garlic pasta', createdAt: '2026-03-01T00:00:00Z' },
  { id: 3, name: 'Chai', description: null },
  { id: 4, name: 'Dal tadka', description: 'Lentils with garlic tempering', createdAt: '2026-01-01T00:00:00Z' },
]

describe('searchRecipes', () => {
  it('matches every word in the name or description, ignoring case', () => {
    assert.deepEqual(searchRecipes(recipes, 'GARLIC').map((r) => r.id), [2, 4])
    assert.deepEqual(searchRecipes(recipes, 'garlic lentils').map((r) => r.id), [4])
    assert.deepEqual(searchRecipes(recipes, '   ').map((r) => r.id), [1, 2, 3, 4])
  })
})

describe('sortRecipes', () => {
  it('sorts by creation date, counting undated recipes as the oldest', () => {
    assert.deepEqual(sortRecipes(recipes, 'newest').map((r) => r.id), [2, 1, 4, 3])
    assert.deepEqual(sortRecipes(recipes, 'oldest').map((r) => r.id), [3, 4, 1, 2])
  })

  it('orders undated recipes by id (creation order) between themselves', () => {
    const legacy = [
      { id: 7, name: 'b' },
      { id: 5, name: 'a' },
      { id: 9, name: 'c', createdAt: '2026-05-01T00:00:00' },
      { id: 6, name: 'd' },
    ]
    assert.deepEqual(sortRecipes(legacy, 'newest').map((r) => r.id), [9, 7, 6, 5])
    assert.deepEqual(sortRecipes(legacy, 'oldest').map((r) => r.id), [5, 6, 7, 9])
  })

  it('sorts by last update, falling back to the creation date', () => {
    const dated = [
      { id: 1, name: 'a', createdAt: '2026-01-01T00:00:00', updatedAt: '2026-06-01T00:00:00' },
      { id: 2, name: 'b', createdAt: '2026-04-01T00:00:00', updatedAt: '2026-04-02T00:00:00' },
      { id: 3, name: 'c', createdAt: '2026-05-01T00:00:00' },
      { id: 4, name: 'd' },
    ]
    assert.deepEqual(sortRecipes(dated, 'updated').map((r) => r.id), [1, 3, 2, 4])
    assert.deepEqual(sortRecipes(dated, 'newest').map((r) => r.id), [3, 2, 1, 4])
    assert.deepEqual(sortRecipes(dated, 'oldest').map((r) => r.id), [4, 1, 2, 3])
  })

  it('sorts by name regardless of case', () => {
    assert.deepEqual(sortRecipes(recipes, 'name').map((r) => r.id), [2, 1, 3, 4])
  })

  it('does not change the input', () => {
    const copy = [...recipes]
    sortRecipes(recipes, 'name')
    assert.deepEqual(recipes, copy)
  })
})

describe('isRecipeSort', () => {
  it('accepts only known sorts', () => {
    assert.equal(isRecipeSort('name'), true)
    assert.equal(isRecipeSort('updated'), true)
    assert.equal(isRecipeSort('rating'), false)
    assert.equal(isRecipeSort(null), false)
  })
})
