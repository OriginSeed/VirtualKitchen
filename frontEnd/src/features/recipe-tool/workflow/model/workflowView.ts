import type { Process } from '../../../../types/process'
import type {
  RecipeAiTaskType,
  RecipeAiWorkflowResponse,
  RecipeAiWorkflowTask,
} from '../../../../types/recipeAiWorkflow'
import { friendlyJobError } from '../../../../shared/errors.ts'

/**
 * Turns AI Recipe Creation's backend state into what the status UI shows: one row per task (icon,
 * status and progress text, retry button) and a headline for the workflow. Pure, so the wording and
 * the rules about what is retryable are tested without React.
 */

export const WORKFLOW_TASK_LABELS: Record<RecipeAiTaskType, string> = {
  PROCESS: 'Recipe Process',
  VISUALS: 'Visualize',
  NARRATION: 'Voice Narration',
}

export const WORKFLOW_TASK_DESCRIPTIONS: Record<RecipeAiTaskType, string> = {
  PROCESS: 'Build the recipe steps, conditions and subprocesses',
  VISUALS: 'Draw the whole recipe as one image of panels and put them on its steps',
  NARRATION: 'Generate narration for each step',
}

/** Same wording as the plain generation's progress (RecipeEditorView), for the PROCESS row. */
const PROCESS_STAGE_LABELS: Record<string, string> = {
  QUEUED: 'Queued…',
  BUILDING_PROMPT: 'Reading your recipe…',
  CALLING_MODEL: 'Generating recipe process…',
  VALIDATING_RESPONSE: 'Checking the result…',
  RETRYING: 'Retrying with feedback…',
  COMPLETED: 'Done',
}

export type TaskTone = 'done' | 'running' | 'waiting' | 'failed' | 'skipped' | 'pending'

export type WorkflowTaskRow = {
  type: RecipeAiTaskType
  label: string
  icon: string
  tone: TaskTone
  /** Short status, e.g. "Complete", "7 / 12", "Waiting for approval". */
  status: string
  /** One line under the label. */
  detail: string
  /** 0–100 for a running task with countable progress; null otherwise. */
  progressPercent: number | null
  retry: { label: string; availableAt: string | null } | null
}

export type ProcessSummary = { steps: number; subprocesses: number; conditions: number }

/** Counts what the recipe currently holds — from the editor session, so it reflects the user's edits. */
export const summarizeProcesses = (processes: readonly Process[]): ProcessSummary => ({
  steps: processes.reduce((sum, process) => sum + process.nodes.filter((node) => node.kind === 'STEP').length, 0),
  subprocesses: processes.filter((process) => process.type === 'SUBPROCESS').length,
  conditions: processes.reduce((sum, process) => sum + process.nodes.filter((node) => node.kind === 'CONDITION').length, 0),
})

const plural = (count: number, one: string, many: string) => `${count} ${count === 1 ? one : many}`

/** "12 steps · 3 subprocesses · 2 conditions" (empty kinds other than steps are left out). */
export const formatProcessSummary = (summary: ProcessSummary) =>
  [
    plural(summary.steps, 'step', 'steps'),
    summary.subprocesses > 0 ? plural(summary.subprocesses, 'subprocess', 'subprocesses') : null,
    summary.conditions > 0 ? plural(summary.conditions, 'condition', 'conditions') : null,
  ].filter(Boolean).join(' · ')

/** "~20–30 credits", "~5 credits", or "No credits" when every selected model is free. */
export const formatCreditRange = (min: number, max: number) => {
  if (max <= 0) return 'No credits'
  if (min === max) return `~${max} credit${max === 1 ? '' : 's'}`
  return `~${min}–${max} credits`
}

const unitFor = (type: RecipeAiTaskType) => (type === 'VISUALS' ? 'images' : 'steps')

/** What Visualize (one storyboard) is doing, by its stage; mirrors model/storyboardView.ts. */
const VISUALIZE_STAGE_LABELS: Record<string, string> = {
  PLAN: 'Planning the panels…',
  PROMPT: 'Writing the image prompt…',
  IMAGE: 'Drawing the image…',
  CROP: 'Cutting it into panels…',
}

/** A visuals task that runs as Visualize reports its stage instead of per-image counts. */
const isVisualize = (task: RecipeAiWorkflowTask) => task.type === 'VISUALS' && Boolean(task.stage)

const countedDetail = (task: RecipeAiWorkflowTask) => {
  const base = `${task.completed} / ${task.total} ${unitFor(task.type)}`
  return task.failed > 0 ? `${base} · ${task.failed} failed` : base
}

/**
 * The row for one task. `processSummary` describes a finished process (from the session); the
 * PROCESS row falls back to plain "Complete" without it.
 */
export const describeWorkflowTask = (task: RecipeAiWorkflowTask, processSummary: ProcessSummary | null): WorkflowTaskRow => {
  const label = WORKFLOW_TASK_LABELS[task.type]
  const row = (tone: TaskTone, icon: string, status: string, detail: string, progressPercent: number | null = null): WorkflowTaskRow => ({
    type: task.type, label, tone, icon, status, detail, progressPercent, retry: null,
  })

  switch (task.status) {
    case 'COMPLETED':
      if (task.type === 'PROCESS') return row('done', '✓', 'Complete', processSummary ? formatProcessSummary(processSummary) : 'Recipe process created')
      if (isVisualize(task)) return row('done', '✓', 'Complete', 'Panels drawn and put on the steps')
      return row('done', '✓', 'Complete', `${task.completed} ${unitFor(task.type)}`)
    case 'RUNNING':
    case 'QUEUED':
      if (task.type === 'PROCESS') {
        const stage = PROCESS_STAGE_LABELS[task.stage ?? ''] ?? 'Generating recipe process…'
        return row('running', '⟳', task.status === 'QUEUED' ? 'Queued' : 'Running', stage, task.progressPercent)
      }
      if (isVisualize(task)) {
        return row('running', '⟳', task.status === 'QUEUED' ? 'Queued' : 'Running',
          VISUALIZE_STAGE_LABELS[task.stage ?? ''] ?? 'Visualizing…', task.progressPercent)
      }
      return row('running', '⟳', task.total > 0 ? `${task.completed} / ${task.total}` : 'Starting', task.total > 0 ? countedDetail(task) : 'Starting…', task.progressPercent)
    case 'WAITING_FOR_APPROVAL':
      return row('waiting', '⏸', 'Waiting for approval', 'Starts after you approve the recipe process')
    case 'PENDING':
      return row('pending', '○', 'Waiting', 'Starts once the recipe process is ready')
    case 'FAILED': {
      const failed = task.type === 'PROCESS'
        ? row('failed', '⚠', 'Failed', friendlyJobError(task.errorMessage, 'Unable to generate the recipe process.'))
        : isVisualize(task)
          ? row('failed', '⚠', 'Failed', friendlyJobError(task.errorMessage, 'Visualize did not finish.'))
          : row('failed', '⚠', task.completed > 0 ? `${task.completed} / ${task.total}` : 'Failed',
          task.total > 0 ? countedDetail(task) : friendlyJobError(task.errorMessage, 'Failed'))
      if (task.retryable) {
        failed.retry = {
          label: task.type === 'PROCESS'
            ? 'Retry Recipe Process'
            : task.completed > 0
              ? `Retry Failed ${task.type === 'VISUALS' ? 'Visuals' : 'Narration'} (${task.failed})`
              : `Retry ${task.type === 'VISUALS' ? 'Visuals' : 'Narration'}`,
          availableAt: task.retryAfter ?? null,
        }
      }
      return failed
    }
    case 'CANCELLED':
      return row('skipped', '–', 'Cancelled', 'Not started')
    case 'SKIPPED':
    default:
      return task.type === 'PROCESS'
        ? row('skipped', '–', 'Existing', 'Uses the recipe process you already have')
        : row('skipped', '–', 'Not started', 'Needs a recipe process first')
  }
}

/** Rows for the tasks the user selected (plus the PROCESS row when the existing process is reused). */
export const workflowTaskRows = (workflow: RecipeAiWorkflowResponse, processSummary: ProcessSummary | null): WorkflowTaskRow[] =>
  workflow.tasks
    .filter((task) => workflow.selectedTasks.includes(task.type) || task.type === 'PROCESS')
    .map((task) => describeWorkflowTask(task, processSummary))

export type WorkflowHeadline = { title: string; subtitle: string | null }

export const workflowHeadline = (workflow: RecipeAiWorkflowResponse): WorkflowHeadline => {
  const failedTasks = workflow.tasks.filter((task) => task.status === 'FAILED').map((task) => WORKFLOW_TASK_LABELS[task.type])
  switch (workflow.status) {
    case 'CREATED':
    case 'GENERATING_PROCESS':
      return { title: 'Creating your recipe', subtitle: 'AI is building the recipe process. You will review it before anything else is created.' }
    case 'WAITING_FOR_APPROVAL':
      return workflow.selectedTasks.includes('PROCESS')
        ? { title: 'Recipe Process Ready', subtitle: 'Review the generated recipe process before generating visuals and narration.' }
        : { title: 'Review your recipe process', subtitle: 'AI will create the selected items from your recipe process exactly as it is when you approve.' }
    case 'RUNNING_DOWNSTREAM_TASKS':
      return { title: 'Creating your recipe', subtitle: 'You can close this window and keep working; progress is saved.' }
    case 'COMPLETED':
      return { title: 'Your recipe is ready', subtitle: null }
    case 'PARTIALLY_COMPLETED':
      return {
        title: workflow.selectedTasks.includes('PROCESS') ? 'Recipe created' : 'Partly done',
        subtitle: `${failedTasks.join(' and ')} did not finish. You can retry just ${failedTasks.length > 1 ? 'those' : 'that'}; everything already created is kept.`,
      }
    case 'FAILED':
      return { title: 'AI Recipe Creation failed', subtitle: failedTasks.length ? `${failedTasks.join(' and ')} failed.` : null }
    case 'CANCELLED':
      return { title: 'AI Recipe Creation discarded', subtitle: null }
  }
}

/** Short text for the sidebar/top-bar chip. */
export const workflowChipLabel = (workflow: RecipeAiWorkflowResponse) => {
  switch (workflow.status) {
    case 'WAITING_FOR_APPROVAL':
      return '✨ Waiting for your approval'
    case 'CREATED':
    case 'GENERATING_PROCESS':
    case 'RUNNING_DOWNSTREAM_TASKS':
      return `✨ AI Recipe Creation · ${workflow.progressPercent}%`
    case 'PARTIALLY_COMPLETED':
    case 'FAILED':
      return '⚠ AI Recipe Creation needs attention'
    default:
      return '✨ AI Recipe Creation'
  }
}

/** The compact form of {@link workflowChipLabel}, for the sidebar's small AI button. */
export const workflowBadge = (workflow: RecipeAiWorkflowResponse) => {
  switch (workflow.status) {
    case 'WAITING_FOR_APPROVAL':
      return '✨ Review'
    case 'CREATED':
    case 'GENERATING_PROCESS':
    case 'RUNNING_DOWNSTREAM_TASKS':
      return `✨ ${workflow.progressPercent}%`
    case 'PARTIALLY_COMPLETED':
    case 'FAILED':
      return '⚠ AI'
    default:
      return '✨ AI'
  }
}

/** Seconds until a retry is allowed (0 when it already is). */
export const secondsUntil = (availableAt: string | null | undefined, now: number) => {
  if (!availableAt) return 0
  const at = Date.parse(availableAt)
  return Number.isNaN(at) ? 0 : Math.max(0, Math.ceil((at - now) / 1000))
}
