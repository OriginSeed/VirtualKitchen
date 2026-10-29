import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import type { Rubric } from '../src/features/admin/ai-evaluation/api/aiEvaluationApi.ts'
import {
  EMPTY_IMAGE_FORM,
  defectRating,
  imageScore,
  isHardFail,
  sameImageForm,
  toggle,
} from '../src/features/admin/ai-evaluation/model/evaluationScoring.ts'
import {
  DEFAULT_FILTERS,
  costPerPoint,
  formatRating,
  formatScore,
  setRoute,
  toEvaluationParams,
} from '../src/features/admin/ai-evaluation/model/evaluationFormat.ts'

/** The backend's v1 rubric, as `GET /rubric` serves it. */
const RUBRIC: Rubric = {
  version: 'v1',
  dimensions: [
    { id: 'CONSISTENCY', label: 'Consistency', description: null, weight: 35, major: null },
    { id: 'STEP_CORRECTNESS', label: 'Step correctness', description: null, weight: 30, major: null },
    { id: 'STATE_CONTINUITY', label: 'State continuity', description: null, weight: 15, major: null },
    { id: 'DEFECTS', label: 'Defects', description: null, weight: 15, major: null },
    { id: 'REALISM', label: 'Realism', description: null, weight: 5, major: null },
  ],
  defectTags: [
    { id: 'ODD_CROP', label: 'Odd crop', description: null, weight: null, major: false },
    { id: 'WRONG_COOKWARE', label: 'Wrong cookware', description: null, weight: null, major: true },
  ],
  reasonTags: [],
  meanWeight: 0.7,
  worstWeight: 0.3,
  hardFailStepCorrectness: 2,
  lowSampleSets: 10,
}

describe('evaluation scoring preview', () => {
  it('rates defects from the tags', () => {
    assert.equal(defectRating([], RUBRIC), 5)
    assert.equal(defectRating(['ODD_CROP'], RUBRIC), 3)
    assert.equal(defectRating(['WRONG_COOKWARE'], RUBRIC), 1)
    assert.equal(defectRating(['ODD_CROP', 'ODD_CROP_2'], RUBRIC), 1)
  })

  it('matches the backend formula', () => {
    const form = { ...EMPTY_IMAGE_FORM, consistency: 3, stepCorrectness: 5, stateContinuity: 1, realism: 5 }
    assert.equal(imageScore(form, RUBRIC), (50 * 35 + 100 * 30 + 0 * 15 + 100 * 15 + 100 * 5) / 100)
    assert.equal(imageScore({ ...EMPTY_IMAGE_FORM, consistency: 5, stepCorrectness: 5, stateContinuity: 5, realism: 5 }, RUBRIC), 100)
  })

  it('skips unrated dimensions and is null with nothing rated', () => {
    assert.equal(imageScore({ ...EMPTY_IMAGE_FORM, stepCorrectness: 5, defectTags: ['ODD_CROP'] }, RUBRIC), (100 * 30 + 50 * 15) / 45)
    assert.equal(imageScore({ ...EMPTY_IMAGE_FORM, defectTags: ['ODD_CROP'] }, RUBRIC), null)
  })

  it('flags a hard fail on low step correctness', () => {
    assert.equal(isHardFail({ ...EMPTY_IMAGE_FORM, stepCorrectness: 2 }, RUBRIC), true)
    assert.equal(isHardFail({ ...EMPTY_IMAGE_FORM, stepCorrectness: 3 }, RUBRIC), false)
    assert.equal(isHardFail(EMPTY_IMAGE_FORM, RUBRIC), false)
  })

  it('compares forms ignoring tag order and note whitespace', () => {
    const a = { ...EMPTY_IMAGE_FORM, defectTags: ['A', 'B'], note: 'ok ' }
    assert.equal(sameImageForm(a, { ...a, defectTags: ['B', 'A'], note: 'ok' }), true)
    assert.equal(sameImageForm(a, { ...a, consistency: 4 }), false)
    assert.deepEqual(toggle(['A'], 'B'), ['A', 'B'])
    assert.deepEqual(toggle(['A', 'B'], 'A'), ['B'])
  })
})

describe('evaluation format', () => {
  it('builds query params from the filters', () => {
    const now = new Date('2026-10-29T12:34:56Z')
    assert.deepEqual(toEvaluationParams(DEFAULT_FILTERS, now), { from: '2026-09-29T12:34:00.000Z' })
    assert.deepEqual(
      toEvaluationParams({ range: 'all', models: ['a', 'b'], statuses: ['UNSCORED'], includeMixed: true }, now),
      { model: 'a,b', status: 'UNSCORED', includeMixed: true },
    )
  })

  it('formats scores and ratings, never showing missing as zero', () => {
    assert.equal(formatScore(78.75), '79')
    assert.equal(formatScore(null), '—')
    assert.equal(formatRating(3.25), '3.3')
    assert.equal(formatRating(null), '—')
  })

  it('works out the cost of one score point', () => {
    assert.equal(costPerPoint(0.1, 50), 0.002)
    assert.equal(costPerPoint(null, 50), null)
    assert.equal(costPerPoint(0.1, 0), null)
  })

  it('encodes the set key in its route', () => {
    assert.equal(setRoute('7::wf1'), '/kitchen/admin/ai-evaluation/sets/7%3A%3Awf1')
  })
})
