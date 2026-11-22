import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { HIDDEN_POLL_INTERVAL_MS, isDocumentHidden, pollDelay } from '../src/features/recipe-tool/jobs/pollTiming.ts'

describe('pollDelay', () => {
  it('keeps the normal interval while the page is visible', () => {
    assert.equal(pollDelay(2000, false), 2000)
    assert.equal(pollDelay(0, false), 0)
  })

  it('slows polling down while the page is hidden', () => {
    assert.equal(pollDelay(2000, true), HIDDEN_POLL_INTERVAL_MS)
  })

  it('never shortens an interval that is already longer', () => {
    assert.equal(pollDelay(30000, true), 30000)
  })
})

describe('isDocumentHidden', () => {
  it('is false outside a browser', () => {
    assert.equal(isDocumentHidden(), false)
  })
})
