/**
 * AI Recipe Creation (backend `RecipeAiWorkflow`): one user-controlled workflow over process
 * generation, step visuals and narration. Mirrors the backend's ai/workflow DTOs.
 */
import type { RecipeProcessGenerationJobResponse, RecipeProcessVisualizationJobResponse } from './recipe'

export type RecipeAiTaskType = 'PROCESS' | 'VISUALS' | 'NARRATION'

export type RecipeAiTaskStatus =
  | 'PENDING'
  | 'QUEUED'
  | 'RUNNING'
  | 'WAITING_FOR_APPROVAL'
  | 'COMPLETED'
  | 'FAILED'
  | 'SKIPPED'
  | 'CANCELLED'

export type RecipeAiWorkflowStatus =
  | 'CREATED'
  | 'GENERATING_PROCESS'
  | 'WAITING_FOR_APPROVAL'
  | 'RUNNING_DOWNSTREAM_TASKS'
  | 'PARTIALLY_COMPLETED'
  | 'COMPLETED'
  | 'FAILED'
  | 'CANCELLED'

/** What the user ticked; "Complete Recipe Experience" is a shortcut the backend expands to every task. */
export interface RecipeAiTaskSelection {
  tasks: RecipeAiTaskType[]
  completeExperience: boolean
  /** Models for this run only, by capability; a missing entry uses the user's account setting. */
  modelKeys?: Partial<Record<RunModelCapability, string>>
}

/** The capabilities a run can pick a model for. */
export type RunModelCapability = 'TEXT_TO_TEXT' | 'TEXT_TO_IMAGE'

export interface RecipeAiWorkflowCreateRequest {
  selection: RecipeAiTaskSelection
  /** Required when the selection includes PROCESS. */
  recipeText?: string
}

export interface RecipeAiTaskEstimate {
  task: RecipeAiTaskType
  minCredits: number
  maxCredits: number
  basis: string
}

export interface RecipeAiWorkflowEstimate {
  tasks: RecipeAiTaskEstimate[]
  minCredits: number
  maxCredits: number
  /** Always true: the figure is a range, never a quote. */
  approximate: boolean
  /** False while the number of steps is an assumed range (the process isn't generated yet). */
  stepCountKnown: boolean
}

export interface RecipeAiWorkflowTask {
  type: RecipeAiTaskType
  status: RecipeAiTaskStatus
  weight: number
  progressPercent: number
  completed: number
  failed: number
  total: number
  /** PROCESS only: the generation job's stage. */
  stage?: string | null
  errorMessage?: string | null
  retryable: boolean
  /** NARRATION: failed steps can't be retried before this instant. */
  retryAfter?: string | null
}

export interface RecipeAiWorkflowResponse {
  workflowId: string
  recipeId: number
  status: RecipeAiWorkflowStatus
  selectedTasks: RecipeAiTaskType[]
  tasks: RecipeAiWorkflowTask[]
  progressPercent: number
  /** True only while discarding really stops everything (waiting for approval). */
  cancellable: boolean
  generationJobId?: string | null
  generationApplied: boolean
  /** Only on create/retry: the generation job to start tracking. */
  generation?: RecipeProcessGenerationJobResponse | null
  visualizationJobs: RecipeProcessVisualizationJobResponse[]
  approvedRevision?: number | null
  approvedAt?: string | null
  createdAt?: string | null
  completedAt?: string | null
  reused: boolean
}
