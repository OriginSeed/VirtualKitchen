import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { describeExistingAssets, existingAssetNoun, hasExistingAssets } from '../src/features/recipe-tool/workflow/model/existingAssets.ts'

describe('existing assets (keep or replace)', () => {
  it('only offers the choice when something exists', () => {
    assert.equal(hasExistingAssets({}), false)
    assert.equal(hasExistingAssets({ images: 0, narrations: 0 }), false)
    assert.equal(hasExistingAssets({ images: 2 }), true)
    assert.equal(hasExistingAssets({ narrations: 1 }), true)
  })

  it('says what already exists', () => {
    assert.equal(describeExistingAssets({ images: 1 }), '1 step already has an image.')
    assert.equal(describeExistingAssets({ images: 3 }), '3 steps already have an image.')
    assert.equal(describeExistingAssets({ narrations: 2 }), '2 steps already have narration.')
    assert.equal(describeExistingAssets({ images: 3, narrations: 1 }), '3 steps already have an image and 1 step has narration.')
    assert.equal(describeExistingAssets({}), '')
  })

  it('names what would be replaced', () => {
    assert.equal(existingAssetNoun({ images: 2 }), 'images')
    assert.equal(existingAssetNoun({ narrations: 2 }), 'narration')
    assert.equal(existingAssetNoun({ images: 1, narrations: 1 }), 'images and narration')
  })
})
