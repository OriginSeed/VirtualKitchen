import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  clearAllDrafts,
  clearDraft,
  clearRecipeDrafts,
  DRAFT_TTL_MS,
  draftKeys,
  readDraft,
  writeDraft,
  type DraftStorage,
} from '../src/shared/drafts/draftStore.ts'

/** An in-memory Storage with the same surface localStorage has. */
class MemoryStorage implements DraftStorage {
  private items = new Map<string, string>()
  failWrites = false
  get length() { return this.items.size }
  key(index: number) { return Array.from(this.items.keys())[index] ?? null }
  getItem(key: string) { return this.items.get(key) ?? null }
  setItem(key: string, value: string) {
    if (this.failWrites) throw new Error('QuotaExceededError')
    this.items.set(key, value)
  }
  removeItem(key: string) { this.items.delete(key) }
  keys() { return Array.from(this.items.keys()) }
}

const NOW = Date.parse('2026-10-06T12:00:00Z')

describe('form drafts', () => {
  it('keeps a draft until it is cleared', () => {
    const storage = new MemoryStorage()
    writeDraft(draftKeys.aiRecipeCreation(30), { recipeText: 'Boil 2 eggs' }, storage, NOW)
    assert.deepEqual(readDraft(draftKeys.aiRecipeCreation(30), storage, NOW + 1000), { recipeText: 'Boil 2 eggs' })

    clearDraft(draftKeys.aiRecipeCreation(30), storage)
    assert.equal(readDraft(draftKeys.aiRecipeCreation(30), storage, NOW), undefined)
  })

  it('keeps drafts of different recipes and forms apart', () => {
    const storage = new MemoryStorage()
    writeDraft(draftKeys.aiEdit(1), 'shorten step 3', storage, NOW)
    writeDraft(draftKeys.aiEdit(2), 'add salt', storage, NOW)
    writeDraft(draftKeys.nutrition(1), { servings: 2 }, storage, NOW)
    assert.equal(readDraft(draftKeys.aiEdit(1), storage, NOW), 'shorten step 3')
    assert.equal(readDraft(draftKeys.aiEdit(2), storage, NOW), 'add salt')
    assert.deepEqual(readDraft(draftKeys.nutrition(1), storage, NOW), { servings: 2 })
  })

  it('drops drafts nobody came back to', () => {
    const storage = new MemoryStorage()
    writeDraft(draftKeys.newRecipe(), { title: 'Curry' }, storage, NOW)
    assert.equal(readDraft(draftKeys.newRecipe(), storage, NOW + DRAFT_TTL_MS + 1), undefined)
    assert.equal(storage.length, 0, 'the expired draft is removed, not just ignored')
  })

  it('tolerates corrupt entries, a full store and no store at all', () => {
    const storage = new MemoryStorage()
    storage.setItem('vk.draft.v1.newRecipe', '{not json')
    assert.equal(readDraft(draftKeys.newRecipe(), storage, NOW), undefined)

    storage.failWrites = true
    assert.doesNotThrow(() => writeDraft(draftKeys.newRecipe(), { title: 'x' }, storage, NOW))

    assert.equal(readDraft(draftKeys.newRecipe(), null, NOW), undefined)
    assert.doesNotThrow(() => writeDraft(draftKeys.newRecipe(), 'x', null))
    assert.doesNotThrow(() => clearAllDrafts(null))
  })

  it('removes a deleted recipe\'s drafts only', () => {
    const storage = new MemoryStorage()
    writeDraft(draftKeys.nutrition(3), { calories: '200' }, storage, NOW)
    writeDraft(draftKeys.aiEdit(3), 'x', storage, NOW)
    writeDraft(draftKeys.aiEdit(30), 'keep me', storage, NOW)
    clearRecipeDrafts(3, storage)
    assert.deepEqual(storage.keys(), ['vk.draft.v1.recipe.30.aiEdit'], 'recipe 30 is not caught by the recipe 3 prefix')
  })

  it('clears every draft on logout but leaves other app data alone', () => {
    const storage = new MemoryStorage()
    storage.setItem('virtual-kitchen.session.token', 'token')
    writeDraft(draftKeys.newRecipe(), { title: 'Curry' }, storage, NOW)
    writeDraft(draftKeys.stepIngredient('n1'), { notes: 'finely' }, storage, NOW)
    clearAllDrafts(storage)
    assert.deepEqual(storage.keys(), ['virtual-kitchen.session.token'])
  })

  it('can prune only the expired drafts', () => {
    const storage = new MemoryStorage()
    writeDraft(draftKeys.aiEdit(1), 'old', storage, NOW - DRAFT_TTL_MS - 1)
    writeDraft(draftKeys.aiEdit(2), 'fresh', storage, NOW)
    clearAllDrafts(storage, { onlyExpired: true, now: NOW })
    assert.deepEqual(storage.keys(), ['vk.draft.v1.recipe.2.aiEdit'])
  })
})
