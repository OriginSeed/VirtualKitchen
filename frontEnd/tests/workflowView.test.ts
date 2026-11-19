import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  describeWorkflowTask,
  formatCreditRange,
  formatProcessSummary,
  secondsUntil,
  summarizeProcesses,
  workflowBadge,
  workflowHeadline,
  workflowTaskRows,
} from '../src/features/recipe-tool/workflow/model/workflowView.ts'
import type { Process } from '../src/types/process.ts'
import type { RecipeAiTaskType, RecipeAiWorkflowResponse, RecipeAiWorkflowTask } from '../src/types/recipeAiWorkflow.ts'

const task = (type: RecipeAiTaskType, patch: Partial<RecipeAiWorkflowTask> = {}): RecipeAiWorkflowTask => ({
  type, status: 'PENDING', weight: 1, progressPercent: 0, completed: 0, failed: 0, total: 0, retryable: false, ...patch,
})

const workflow = (patch: Partial<RecipeAiWorkflowResponse> = {}): RecipeAiWorkflowResponse => ({
  workflowId: 'wf', recipeId: 1, status: 'RUNNING_DOWNSTREAM_TASKS', selectedTasks: ['PROCESS', 'VISUALS', 'NARRATION'],
  tasks: [task('PROCESS', { status: 'COMPLETED', progressPercent: 100 }), task('VISUALS'), task('NARRATION')],
  progressPercent: 0, cancellable: false, generationApplied: true, visualizationJobs: [], reused: false, ...patch,
})

const summary = { steps: 12, subprocesses: 3, conditions: 2 }

describe('AI Recipe Creation status rows', () => {
  it('summarises the process from the editor session', () => {
    const processes = [
      { id: 1, type: 'MAIN', nodes: [{ kind: 'STEP' }, { kind: 'STEP' }, { kind: 'CONDITION' }] },
      { id: 2, type: 'SUBPROCESS', nodes: [{ kind: 'STEP' }] },
    ] as unknown as Process[]
    assert.deepEqual(summarizeProcesses(processes), { steps: 3, subprocesses: 1, conditions: 1 })
    assert.equal(formatProcessSummary(summary), '12 steps · 3 subprocesses · 2 conditions')
    assert.equal(formatProcessSummary({ steps: 1, subprocesses: 0, conditions: 0 }), '1 step')
  })

  it('shows a running task with its counted progress', () => {
    const row = describeWorkflowTask(task('VISUALS', { status: 'RUNNING', completed: 4, total: 12, progressPercent: 33 }), summary)
    assert.equal(row.tone, 'running')
    assert.equal(row.status, '4 / 12')
    assert.equal(row.detail, '4 / 12 images')
    assert.equal(row.progressPercent, 33)
    assert.equal(row.retry, null)
  })

  it('shows the generation stage for the process and the process summary once done', () => {
    assert.equal(describeWorkflowTask(task('PROCESS', { status: 'RUNNING', stage: 'CALLING_MODEL' }), null).detail, 'Generating recipe process…')
    const done = describeWorkflowTask(task('PROCESS', { status: 'COMPLETED' }), summary)
    assert.equal(done.status, 'Complete')
    assert.equal(done.detail, '12 steps · 3 subprocesses · 2 conditions')
  })

  it('marks downstream tasks as waiting for approval', () => {
    const row = describeWorkflowTask(task('NARRATION', { status: 'WAITING_FOR_APPROVAL' }), summary)
    assert.equal(row.tone, 'waiting')
    assert.equal(row.status, 'Waiting for approval')
  })

  it('offers to retry only the failed part of a partly failed task', () => {
    const row = describeWorkflowTask(task('VISUALS', { status: 'FAILED', completed: 10, failed: 2, total: 12, retryable: true }), summary)
    assert.equal(row.tone, 'failed')
    assert.equal(row.detail, '10 / 12 images · 2 failed')
    assert.equal(row.retry?.label, 'Retry Failed Visuals (2)')

    const allFailed = describeWorkflowTask(task('NARRATION', { status: 'FAILED', failed: 3, total: 3, retryable: true, retryAfter: '2026-10-05T12:00:20Z' }), summary)
    assert.equal(allFailed.retry?.label, 'Retry Narration')
    assert.equal(allFailed.retry?.availableAt, '2026-10-05T12:00:20Z')
    assert.equal(secondsUntil(allFailed.retry?.availableAt, Date.parse('2026-10-05T12:00:05Z')), 15)
    assert.equal(secondsUntil(allFailed.retry?.availableAt, Date.parse('2026-10-05T12:01:00Z')), 0)

    const notRetryable = describeWorkflowTask(task('VISUALS', { status: 'FAILED', failed: 1, total: 1, retryable: false }), summary)
    assert.equal(notRetryable.retry, null)
  })

  it('lists only selected tasks, plus the reused existing process', () => {
    const rows = workflowTaskRows(workflow({
      selectedTasks: ['VISUALS'],
      tasks: [task('PROCESS', { status: 'SKIPPED' }), task('VISUALS', { status: 'WAITING_FOR_APPROVAL' }), task('NARRATION', { status: 'SKIPPED' })],
    }), summary)
    assert.deepEqual(rows.map((row) => row.type), ['PROCESS', 'VISUALS'])
    assert.equal(rows[0].detail, 'Uses the recipe process you already have')
  })
})

describe('AI Recipe Creation headline and credits', () => {
  it('describes each workflow state', () => {
    assert.equal(workflowHeadline(workflow({ status: 'WAITING_FOR_APPROVAL' })).title, 'Recipe Process Ready')
    assert.equal(workflowHeadline(workflow({ status: 'COMPLETED' })).title, 'Your recipe is ready')
    const partial = workflowHeadline(workflow({
      status: 'PARTIALLY_COMPLETED',
      tasks: [task('PROCESS', { status: 'COMPLETED' }), task('VISUALS', { status: 'FAILED' }), task('NARRATION', { status: 'COMPLETED' })],
    }))
    assert.equal(partial.title, 'Recipe created')
    assert.match(partial.subtitle ?? '', /Visualize did not finish\. You can retry just that;/)
    const bothFailed = workflowHeadline(workflow({
      status: 'PARTIALLY_COMPLETED',
      selectedTasks: ['VISUALS', 'NARRATION'],
      tasks: [task('PROCESS', { status: 'SKIPPED' }), task('VISUALS', { status: 'FAILED', completed: 5 }), task('NARRATION', { status: 'FAILED' })],
    }))
    assert.equal(bothFailed.title, 'Partly done', 'nothing was "created" when the process was not generated')
    assert.match(bothFailed.subtitle ?? '', /Visualize and Voice Narration did not finish\. You can retry just those;/)
  })

  it('badges the sidebar button with what needs attention', () => {
    assert.equal(workflowBadge(workflow({ status: 'WAITING_FOR_APPROVAL' })), '✨ Review')
    assert.equal(workflowBadge(workflow({ progressPercent: 62 })), '✨ 62%')
    assert.equal(workflowBadge(workflow({ status: 'PARTIALLY_COMPLETED' })), '⚠ AI')
  })

  it('formats credit ranges without pretending to be exact', () => {
    assert.equal(formatCreditRange(20, 30), '~20–30 credits')
    assert.equal(formatCreditRange(5, 5), '~5 credits')
    assert.equal(formatCreditRange(1, 1), '~1 credit')
    assert.equal(formatCreditRange(0, 0), 'No credits')
  })
})
