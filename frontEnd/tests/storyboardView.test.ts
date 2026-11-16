import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  isStoryboardRunning,
  storyboardFailureText,
  storyboardLayoutText,
  storyboardPanelMeta,
  storyboardPanelTitle,
  storyboardProgress,
  storyboardProgressBadge,
  storyboardStepChanges,
  storyboardStepImages,
  storyboardStepsCleared,
  storyboardStepSummary,
} from '../src/features/recipe-tool/model/storyboardView.ts'
import { activeJobsKey, parseActiveJobsKey, summarizeActiveJobs } from '../src/features/recipe-tool/jobs/activeJobsSummary.ts'
import type { RecipeStoryboardStep } from '../src/types/recipe.ts'

const step = (stepId: string, attached: boolean, imageUrl: string | null = `https://store/${stepId}.png`): RecipeStoryboardStep => ({
  processId: 1, stepId, panelId: 'P1', attached, imageUrl: attached ? imageUrl : null,
})

describe('recipe storyboard wording', () => {
  it('knows when a generation is still running', () => {
    assert.equal(isStoryboardRunning({ status: 'QUEUED' }), true)
    assert.equal(isStoryboardRunning({ status: 'IN_PROGRESS' }), true)
    assert.equal(isStoryboardRunning({ status: 'COMPLETED' }), false)
    assert.equal(isStoryboardRunning({ status: 'FAILED' }), false)
    assert.equal(isStoryboardRunning(null), false)
  })

  it('walks through the four stages, never at 0 and never at 100 before it is done', () => {
    assert.deepEqual(storyboardProgress({ status: 'QUEUED', stage: 'PLAN' }),
      { percent: 5, label: 'Waiting to start…', stageNumber: 1, stageCount: 4 })
    assert.deepEqual(storyboardProgress({ status: 'IN_PROGRESS', stage: 'PLAN' }),
      { percent: 13, label: 'Planning the panels…', stageNumber: 1, stageCount: 4 })
    assert.equal(storyboardProgress({ status: 'IN_PROGRESS', stage: 'PROMPT' }).percent, 38)
    assert.equal(storyboardProgress({ status: 'IN_PROGRESS', stage: 'IMAGE' }).label, 'Drawing the storyboard…')
    assert.equal(storyboardProgress({ status: 'IN_PROGRESS', stage: 'CROP' }).percent, 88)
    assert.equal(storyboardProgress({ status: 'COMPLETED', stage: 'CROP' }).percent, 100)
    assert.equal(storyboardProgressBadge({ status: 'IN_PROGRESS', stage: 'IMAGE' }), '3/4')
  })

  it('says what failed, with the reason when there is one', () => {
    assert.equal(storyboardFailureText({ failedStage: 'IMAGE', errorMessage: 'image provider down' }),
      'The storyboard image could not be drawn: image provider down')
    assert.equal(storyboardFailureText({ failedStage: 'PLAN', errorMessage: ' ' }),
      'The panels could not be planned. Please try again.')
    assert.equal(storyboardFailureText({ failedStage: null, errorMessage: null }),
      'The storyboard could not be generated. Please try again.')
  })

  it('titles a panel by its place and says how many steps it covers', () => {
    assert.equal(storyboardPanelTitle({ sequence: 3, description: ' Marinate chicken ' }), '3. Marinate chicken')
    assert.equal(storyboardPanelTitle({ sequence: 1, description: '' }), '1. Panel')
    assert.equal(storyboardPanelMeta({ group: 'PREPARATION', sourceStepIds: ['a', 'b', 'c'] }), 'Preparation · covers 3 steps')
    assert.equal(storyboardPanelMeta({ group: 'MAIN', sourceStepIds: ['a'] }), 'Cooking · covers 1 step')
  })

  it('describes the grid', () => {
    const panels = Array.from({ length: 7 }, (_, index) => ({ panelId: `P${index + 1}` })) as never[]
    assert.equal(storyboardLayoutText({ panels, rows: 3, columns: 3 }), '7 panels in a 3 × 3 grid')
    assert.equal(storyboardLayoutText({ panels: panels.slice(0, 1), rows: null, columns: null }), '1 panel')
  })

  it('only steps that were given a panel image are applied to the editor', () => {
    const steps = [step('a', true), step('b', false), step('c', true, null)]
    assert.deepEqual(storyboardStepImages({ steps }).map((entry) => entry.stepId), ['a'])
  })

  it('sums up what happened to the steps', () => {
    assert.equal(storyboardStepSummary({ steps: [] }), '')
    assert.equal(storyboardStepSummary({ steps: [step('a', true), step('b', true)] }),
      "Each panel's image was added to the last step it shows: 2 steps.")
    assert.equal(storyboardStepSummary({ steps: [step('a', true), step('b', false), step('c', false)] }),
      "Each panel's image was added to the last step it shows: 1 step. 2 steps kept the image they already had.")
    assert.equal(storyboardStepSummary({ steps: [step('a', false)] }), '1 step kept the image it already had.')
    assert.equal(storyboardStepSummary({ steps: [step('a', true), { ...step('b', false), cleared: true }] }),
      "Each panel's image was added to the last step it shows: 1 step. An earlier storyboard's image was removed from 1 step.")
  })

  it('the editor applies new panel images and removed ones, not steps that kept their own', () => {
    const steps = [step('a', true), step('b', false), { ...step('c', false), cleared: true }]
    assert.deepEqual(storyboardStepsCleared({ steps }).map((entry) => entry.stepId), ['c'])
    assert.deepEqual(storyboardStepChanges({ steps }).map((entry) => entry.stepId), ['a', 'c'])
  })
})

describe('a running storyboard in the navbar summary', () => {
  it('counts as AI work on its recipe and survives the key round trip', () => {
    const jobs = [{ kind: 'storyboard' as const, recipeId: 62, waitingForApproval: false }]
    assert.deepEqual(parseActiveJobsKey(activeJobsKey(jobs)), jobs)
    assert.deepEqual(summarizeActiveJobs(jobs), { count: 1, recipeId: 62, needsReview: false, label: 'AI working…' })
  })
})
