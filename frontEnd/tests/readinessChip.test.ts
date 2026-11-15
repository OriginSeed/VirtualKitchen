import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { readinessChip } from '../src/features/recipe-tool/page/readinessChip.ts'

describe('readinessChip', () => {
  it('is green when nothing is wrong', () => {
    assert.equal(readinessChip({ blockers: 0, warnings: 0 }).tone, 'ready')
    assert.equal(readinessChip({ blockers: 0, warnings: 0 }).label, 'Ready to cook')
  })

  it('is amber when only warnings remain', () => {
    const chip = readinessChip({ blockers: 0, warnings: 2 })
    assert.equal(chip.tone, 'warning')
    assert.equal(chip.label, 'Ready · 2 warnings')
  })

  it('is red while anything blocks, whatever the warnings', () => {
    const chip = readinessChip({ blockers: 1, warnings: 3 })
    assert.equal(chip.tone, 'blocked')
    assert.equal(chip.label, '1 to fix')
    assert.match(chip.title, /1 issue blocks/)
    assert.match(chip.title, /3 warnings/)
  })
})
