import { createContext } from 'react'
import { RecipeJobsApi, RecipeProcessGenerationApi, RecipeProcessVisualizationApi } from '../../../api'
import { RecipeAiWorkflowApi } from '../../../api/recipeAiWorkflowApi'
import type {
  ProcessGenerationMode,
  RecipeProcessGenerationJobResponse,
  RecipeProcessGenerationRequest,
  RecipeProcessVisualizationJobResponse,
} from '../../../types/recipe'
import type { RecipeAiWorkflowCreateRequest, RecipeAiWorkflowResponse, RecipeAiWorkflowStatus } from '../../../types/recipeAiWorkflow'
import { errorMessage } from '../../../shared/errors'
import type { RunModels } from '../workflow/model/workflowSelection'

/**
 * App-wide tracker for the recipe tool's long-running backend jobs (AI process generation, Generate
 * Visuals, and the AI Recipe Creation workflow that orchestrates them). Owned by JobTrackerProvider above the routes, so a job keeps being polled while the
 * user moves between processes, tabs or pages; and re-attached from the backend (`discover`) when
 * the recipe tool opens, so a reload resumes showing progress instead of offering to start the same
 * work again.
 *
 * The backend is the source of truth: it allows one running job per recipe (generation) or process
 * (visuals) and answers a repeated start with the running job (`reused`), which is surfaced here
 * as "already in progress". Components read a job with `useTrackedJob(key)` (useJobTracker.ts) —
 * an external store, so only the components showing a given job re-render on each poll.
 */

const POLL_INTERVAL_MS = 2000
/** A workflow waiting for the user's approval changes only through the user's own actions: poll it lazily. */
const WAITING_POLL_INTERVAL_MS = 10000
const MAX_POLL_BACKOFF_MS = 30000
/** Consecutive failed polls before the UI says the connection was lost (polling continues). */
const CONNECTION_LOST_AFTER = 3
/** Consecutive failed polls (~10 minutes with backoff) before giving up on a job. */
const GIVE_UP_AFTER = 25

export type GenerationJobKey = `gen:${number}`
export type VisualsJobKey = `viz:${number}`
export type WorkflowJobKey = `wf:${number}`
export type JobKey = GenerationJobKey | VisualsJobKey | WorkflowJobKey

export const generationJobKey = (recipeId: number): GenerationJobKey => `gen:${recipeId}`
export const visualsJobKey = (processId: number): VisualsJobKey => `viz:${processId}`
export const workflowJobKey = (recipeId: number): WorkflowJobKey => `wf:${recipeId}`

type TrackedBase = {
  recipeId: number
  /** Several polls in a row failed; still retrying. */
  connectionLost: boolean
  /** Polling stopped without reaching a terminal status (job vanished, or the backend kept failing). */
  pollError: string | null
}

export type TrackedGenerationJob = TrackedBase & {
  kind: 'generation'
  job: RecipeProcessGenerationJobResponse
  /** What this page asked for (unknown for a job rediscovered on load) — lets the editor refuse a result of the wrong kind. */
  requestedMode?: ProcessGenerationMode
}
export type TrackedVisualsJob = TrackedBase & { kind: 'visuals'; processId: number; job: RecipeProcessVisualizationJobResponse }
/** An AI Recipe Creation workflow; `job` is the workflow itself (its id is `workflowId`). */
export type TrackedWorkflow = TrackedBase & { kind: 'workflow'; job: RecipeAiWorkflowResponse }
export type TrackedJob = TrackedGenerationJob | TrackedVisualsJob | TrackedWorkflow
export type TrackedJobFor<K extends JobKey> = K extends GenerationJobKey
  ? TrackedGenerationJob
  : K extends WorkflowJobKey ? TrackedWorkflow : TrackedVisualsJob

const isRunningStatus = (status: string | undefined) => status === 'QUEUED' || status === 'IN_PROGRESS'

const OPEN_WORKFLOW_STATUSES: ReadonlySet<RecipeAiWorkflowStatus> = new Set<RecipeAiWorkflowStatus>([
  'CREATED', 'GENERATING_PROCESS', 'WAITING_FOR_APPROVAL', 'RUNNING_DOWNSTREAM_TASKS',
])

/** True until the workflow reached a terminal state (completed, partially completed, failed, cancelled). */
export const isWorkflowOpen = (workflow: RecipeAiWorkflowResponse | undefined) =>
  workflow != null && OPEN_WORKFLOW_STATUSES.has(workflow.status)

/** True while the job is still running on the backend (and we're still following it). */
export const isTrackedJobActive = (tracked: TrackedJob | undefined) => {
  if (tracked == null || tracked.pollError != null) return false
  return tracked.kind === 'workflow' ? isWorkflowOpen(tracked.job) : isRunningStatus(tracked.job.status)
}

/** The backend id of whatever is tracked: a job id, or a workflow id. */
const trackedId = (tracked: TrackedJob) => (tracked.kind === 'workflow' ? tracked.job.workflowId : tracked.job.jobId)

const pollInterval = (tracked: TrackedJob) =>
  tracked.kind === 'workflow' && tracked.job.status === 'WAITING_FOR_APPROVAL' ? WAITING_POLL_INTERVAL_MS : POLL_INTERVAL_MS

export type JobNotifier = {
  success: (message: string) => void
  error: (message: string) => void
  info: (message: string) => void
}

const ALREADY_GENERATING = 'The AI is already working on this recipe — showing its progress.'
const ALREADY_VISUALIZING = 'Visuals are already being generated for this process — showing their progress.'

export class JobTracker {
  private jobs = new Map<JobKey, TrackedJob>()
  private listeners = new Set<() => void>()
  private timers = new Map<JobKey, number>()
  private aborters = new Map<JobKey, AbortController>()
  private failures = new Map<JobKey, number>()
  /** Keys whose start request is in flight — guards double clicks before the first response lands. */
  private starting = new Set<JobKey>()
  /** Keys `refresh` was asked for while their poll was in flight. */
  private refreshAfterPoll = new Set<JobKey>()
  private finishedCount = 0
  private notifier: JobNotifier = { success: () => {}, error: () => {}, info: () => {} }

  setNotifier(notifier: JobNotifier) {
    this.notifier = notifier
  }

  get(key: JobKey) {
    return this.jobs.get(key)
  }

  all() {
    return Array.from(this.jobs.values())
  }

  /** How many tracked jobs have finished so far — bumps e.g. the AI credit badge's refetch. */
  getFinishedCount = () => this.finishedCount

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  /**
   * Starts (or joins the already-running) AI job for a recipe: a new flow from recipe text (CREATE) or a change to
   * an existing process (EDIT). Both share the one-job-per-recipe slot. Resolves once the backend accepted it.
   */
  async startGeneration(
    recipeId: number, request: Omit<RecipeProcessGenerationRequest, 'clientRequestId'>,
  ): Promise<TrackedGenerationJob | undefined> {
    const key = generationJobKey(recipeId)
    if (this.starting.has(key)) return undefined
    const current = this.jobs.get(key)
    if (current?.kind === 'generation' && isTrackedJobActive(current)) {
      this.notifier.info(ALREADY_GENERATING)
      return current
    }

    this.starting.add(key)
    try {
      const job = await RecipeProcessGenerationApi.startJob(recipeId, { ...request, clientRequestId: crypto.randomUUID() })
      const tracked: TrackedGenerationJob = {
        kind: 'generation', recipeId, job, connectionLost: false, pollError: null, requestedMode: request.mode ?? 'CREATE',
      }
      this.track(key, tracked)
      this.notifier.info(job.reused
        ? ALREADY_GENERATING
        : request.mode === 'EDIT'
          ? 'The AI is working on your change. You can keep working; you will review it before anything changes.'
          : 'Generating your recipe process in the background. You can keep working or come back later.')
      return tracked
    } finally {
      this.starting.delete(key)
    }
  }

  /** Starts (or joins the already-running) Generate Visuals job for a process. Resolves once the backend accepted it. */
  async startVisuals(
    recipeId: number,
    processId: number,
    modelKeys?: RunModels,
    overwriteExisting = false,
  ): Promise<TrackedVisualsJob | undefined> {
    const key = visualsJobKey(processId)
    if (this.starting.has(key)) return undefined
    const current = this.jobs.get(key)
    if (current?.kind === 'visuals' && isTrackedJobActive(current)) {
      this.notifier.info(ALREADY_VISUALIZING)
      return current
    }

    this.starting.add(key)
    try {
      const job = await RecipeProcessVisualizationApi.startJob(recipeId, processId, modelKeys, overwriteExisting)
      const tracked: TrackedVisualsJob = { kind: 'visuals', recipeId, processId, job, connectionLost: false, pollError: null }
      this.track(key, tracked)
      this.notifier.info(job.reused
        ? ALREADY_VISUALIZING
        : `Generating visuals for ${job.totalSteps} step${job.totalSteps === 1 ? '' : 's'} in the background. You can keep working or come back later.`)
      return tracked
    } finally {
      this.starting.delete(key)
    }
  }

  /**
   * Starts AI Recipe Creation (or joins the recipe's open workflow). With the recipe process selected,
   * the backend also starts the generation job, which is tracked under its usual key so the editor
   * loads the result exactly as it does for a plain generation. Resolves once the backend accepted it.
   */
  async startWorkflow(recipeId: number, request: RecipeAiWorkflowCreateRequest): Promise<TrackedWorkflow | undefined> {
    const key = workflowJobKey(recipeId)
    if (this.starting.has(key)) return undefined
    const current = this.jobs.get(key)
    if (current?.kind === 'workflow' && isTrackedJobActive(current)) return current

    this.starting.add(key)
    try {
      const workflow = await RecipeAiWorkflowApi.create(recipeId, request)
      const tracked = this.applyWorkflow(recipeId, workflow)
      if (workflow.reused) this.notifier.info('AI Recipe Creation is already in progress for this recipe — showing it.')
      return tracked
    } finally {
      this.starting.delete(key)
    }
  }

  /**
   * Takes the workflow state a workflow action (create, approve, retry, discard) answered with, and
   * follows the jobs it runs: the generation job, and every running visualization job, so the canvas
   * shows images as they arrive, as it does for a standalone Generate Visuals.
   */
  applyWorkflow(recipeId: number, workflow: RecipeAiWorkflowResponse): TrackedWorkflow {
    const tracked: TrackedWorkflow = { kind: 'workflow', recipeId, job: workflow, connectionLost: false, pollError: null }
    this.track(workflowJobKey(recipeId), tracked)
    this.adoptWorkflowJobs(recipeId, workflow)
    return tracked
  }

  private adoptWorkflowJobs(recipeId: number, workflow: RecipeAiWorkflowResponse) {
    if (workflow.generation) {
      const key = generationJobKey(recipeId)
      const current = this.jobs.get(key)
      if (current?.kind !== 'generation' || current.job.jobId !== workflow.generation.jobId) {
        this.track(key, { kind: 'generation', recipeId, job: workflow.generation, connectionLost: false, pollError: null, requestedMode: 'CREATE' })
      }
    }
    for (const job of workflow.visualizationJobs ?? []) {
      const key = visualsJobKey(job.processId)
      const current = this.jobs.get(key)
      if (isRunningStatus(job.status) && (current?.kind !== 'visuals' || current.job.jobId !== job.jobId)) {
        this.track(key, { kind: 'visuals', recipeId, processId: job.processId, job, connectionLost: false, pollError: null })
      }
    }
  }

  /** Re-attaches to the caller's running jobs for a recipe — call when the recipe tool opens. */
  async discover(recipeId: number) {
    try {
      const active = await RecipeJobsApi.getActive(recipeId)
      if (active.generation) {
        this.track(generationJobKey(recipeId), { kind: 'generation', recipeId, job: active.generation, connectionLost: false, pollError: null })
      }
      for (const job of active.visualizations) {
        this.track(visualsJobKey(job.processId), { kind: 'visuals', recipeId, processId: job.processId, job, connectionLost: false, pollError: null })
      }
      if (active.workflow) this.applyWorkflow(recipeId, active.workflow)
    } catch (error) {
      // Not fatal: the tool still works; it just can't show jobs started before this page load.
      console.error('Unable to load this recipe\'s running jobs:', error)
    }
  }

  /** Polls a still-running tracked job now instead of at its next interval (e.g. right after a related change). */
  refresh(key: JobKey) {
    const tracked = this.jobs.get(key)
    if (!isTrackedJobActive(tracked)) return
    // A poll already in flight may have been answered before the change: poll again right after it.
    if (this.aborters.has(key)) this.refreshAfterPoll.add(key)
    else this.schedulePoll(key, 0)
  }

  /** Stops showing a finished job (e.g. once its generation result was applied). */
  dismiss(key: JobKey) {
    this.stopPolling(key)
    if (this.jobs.delete(key)) this.emit()
  }

  /** Stops all polling (provider unmount); `resume` picks it back up. Tracked state is kept. */
  pause() {
    Array.from(this.timers.keys()).forEach((key) => this.stopPolling(key))
    Array.from(this.aborters.keys()).forEach((key) => this.stopPolling(key))
  }

  resume() {
    for (const [key, tracked] of this.jobs) {
      if (isTrackedJobActive(tracked) && !this.timers.has(key) && !this.aborters.has(key)) this.schedulePoll(key, 0)
    }
  }

  /** Starts following a job the backend returned, replacing whatever was tracked under its key. */
  private track(key: JobKey, tracked: TrackedJob) {
    const current = this.jobs.get(key)
    if (current && trackedId(current) !== trackedId(tracked)) this.stopPolling(key)
    this.set(key, tracked)
    if (isTrackedJobActive(tracked)) {
      if (!this.timers.has(key) && !this.aborters.has(key)) this.schedulePoll(key, pollInterval(tracked))
    } else {
      this.stopPolling(key)
    }
  }

  private schedulePoll(key: JobKey, delayMs: number) {
    const existing = this.timers.get(key)
    if (existing != null) window.clearTimeout(existing)
    this.timers.set(key, window.setTimeout(() => {
      this.timers.delete(key)
      void this.poll(key)
    }, delayMs))
  }

  private async poll(key: JobKey) {
    const tracked = this.jobs.get(key)
    if (!tracked) return

    const controller = new AbortController()
    this.aborters.set(key, controller)
    try {
      let next: TrackedJob
      if (tracked.kind === 'generation') {
        next = { ...tracked, connectionLost: false, job: await RecipeProcessGenerationApi.getJobStatus(tracked.recipeId, tracked.job.jobId, controller.signal) }
      } else if (tracked.kind === 'visuals') {
        next = { ...tracked, connectionLost: false, job: await RecipeProcessVisualizationApi.getJobStatus(tracked.recipeId, tracked.processId, tracked.job.jobId, controller.signal) }
      } else {
        next = { ...tracked, connectionLost: false, job: await RecipeAiWorkflowApi.get(tracked.recipeId, tracked.job.workflowId, controller.signal) }
      }
      const latest = this.jobs.get(key)
      if (controller.signal.aborted || !latest || trackedId(latest) !== trackedId(tracked)) return

      this.failures.delete(key)
      if (next.kind === 'workflow') this.adoptWorkflowJobs(next.recipeId, next.job)
      if (isTrackedJobActive(next)) {
        this.set(key, next)
        this.schedulePoll(key, this.refreshAfterPoll.delete(key) ? 0 : pollInterval(next))
      } else {
        this.finishedCount += 1
        this.set(key, next)
        this.announceFinished(next)
      }
    } catch (error) {
      if (controller.signal.aborted) return
      const failures = (this.failures.get(key) ?? 0) + 1
      this.failures.set(key, failures)
      if (failures >= GIVE_UP_AFTER) {
        this.failures.delete(key)
        this.patch(key, { connectionLost: false, pollError: errorMessage(error, 'Lost track of this job.') })
        return
      }
      if (failures >= CONNECTION_LOST_AFTER) this.patch(key, { connectionLost: true })
      this.schedulePoll(key, Math.min(POLL_INTERVAL_MS * 2 ** failures, MAX_POLL_BACKOFF_MS))
    } finally {
      if (this.aborters.get(key) === controller) this.aborters.delete(key)
    }
  }

  /** Toasts for a job that finished while we were watching it live (not for one discovered already finished). */
  private announceFinished(tracked: TrackedJob) {
    if (tracked.kind === 'workflow') {
      // Before approval, a failure is the generation's own and is announced with it.
      const { job } = tracked
      if (!job.approvedAt) return
      if (job.status === 'COMPLETED') this.notifier.success('AI Recipe Creation finished — your recipe experience is ready.')
      else if (job.status === 'PARTIALLY_COMPLETED') this.notifier.error('Your recipe was created, but some AI tasks failed. Open AI Recipe Creation to retry them.')
      else if (job.status === 'FAILED') this.notifier.error('AI Recipe Creation could not create the visuals or narration. Open it to retry.')
    } else if (tracked.kind === 'visuals') {
      const { job } = tracked
      const succeeded = job.steps.filter((step) => step.success).length
      if (job.status === 'COMPLETED') this.notifier.success(`Visuals ready for all ${job.totalSteps} steps`)
      else if (job.status === 'COMPLETED_WITH_ERRORS') this.notifier.error(`Visuals generated for ${succeeded}/${job.totalSteps} steps — ${job.totalSteps - succeeded} failed`)
      else this.notifier.error(job.errorMessage || 'Unable to generate visuals right now.')
    } else if (tracked.job.status === 'FAILED') {
      // A successful generation is announced by the editor once it has loaded the result.
      this.notifier.error(tracked.job.errorMessage
        || (tracked.job.mode === 'EDIT' ? 'Unable to apply that change right now.' : 'Unable to generate this recipe right now.'))
    }
  }

  private stopPolling(key: JobKey) {
    const timer = this.timers.get(key)
    if (timer != null) window.clearTimeout(timer)
    this.timers.delete(key)
    this.aborters.get(key)?.abort()
    this.aborters.delete(key)
    this.failures.delete(key)
    this.refreshAfterPoll.delete(key)
  }

  private set(key: JobKey, tracked: TrackedJob) {
    this.jobs.set(key, tracked)
    this.emit()
  }

  private patch(key: JobKey, patch: Partial<TrackedBase>) {
    const current = this.jobs.get(key)
    if (!current) return
    this.jobs.set(key, { ...current, ...patch })
    this.emit()
  }

  private emit() {
    this.listeners.forEach((listener) => listener())
  }
}

export const JobTrackerContext = createContext<JobTracker | null>(null)
