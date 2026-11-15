import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import '../../../../styles/flow-ui.css'
import { ProcessApi, RecipeProcessGenerationApi, RecipeDetailApi } from '../../../../api'
import type { RecipeProcessBreadcrumbEntry, RecipeProcessEditResult } from '../../../../types/recipe'
import { useNotifications } from '../../../../shared/components/notifications/notificationContext'
import RecipeProcessEditor from './RecipeProcessEditor'
import RecipeProcessSidebar, { type ProcessIssueCounts } from './RecipeProcessSidebar'
import RecipeReadinessPanel from './RecipeReadinessPanel'
import RecipeProcessGenerationModal, { type PendingProcessEdit } from './RecipeProcessGenerationModal'
import { useRecipeSession } from '../../session/sessionContext'
import { useRecipeReadiness } from '../../session/useRecipeReadiness'
import type { ReadinessIssue } from '../../model/recipeReadiness'
import { convertGeneratedResultToProcesses } from '../adapters/recipeProcessGenerationConverter'
import { applyProcessEdit, describeProcessEdit, ProcessEditConflictError } from '../adapters/recipeProcessEditApplier'
import { serializeProcessForEdit } from '../adapters/recipeProcessEditSerializer'
import { normalizeConditionNodeData } from '../../model/recipeConditionData'
import { normalizeRecipeStepNodeData } from '../../model/recipeStepData'
import { mergePreparations, PREPARATION_NAME } from '../../model/mergePreparations'
import { getPreparationFlows, getPreparationProcess } from '../../model/preparationFlows'
import { generationJobKey, isTrackedJobActive, isWorkflowOpen, workflowJobKey } from '../../jobs/jobTracker'
import { useJobTracker, useTrackedJob } from '../../jobs/useJobTracker'
import RecipeAiCreationModal from '../../workflow/components/RecipeAiCreationModal'
import type { RunModels } from '../../workflow/model/workflowSelection'
import { clearDraft, draftKeys } from '../../../../shared/drafts/draftStore'
import { workflowBadge, workflowChipLabel, workflowHeadline } from '../../workflow/model/workflowView'
import type { RecipeAiTaskType } from '../../../../types/recipeAiWorkflow'
import type { RecipeAiActions } from './AiActionsMenu'
import { errorMessage } from '../../../../shared/errors'

const GENERATION_STAGE_LABELS: Record<string, string> = {
  QUEUED: 'Queued…',
  BUILDING_PROMPT: 'Reading your recipe…',
  CALLING_MODEL: 'Asking the AI to structure it…',
  VALIDATING_RESPONSE: 'Checking the result…',
  RETRYING: 'Retrying with feedback…',
  COMPLETED: 'Done',
}

const EDIT_STAGE_LABELS: Record<string, string> = {
  ...GENERATION_STAGE_LABELS,
  BUILDING_PROMPT: 'Reading your flow…',
  CALLING_MODEL: 'Asking the AI for the change…',
}

/** How long the nodes an applied AI edit touched stay highlighted on the canvas. */
const EDIT_HIGHLIGHT_MS = 4000


type RecipeEditorViewProps = {
  recipeId: number
  /** Shown as the root of the editor's breadcrumb (Recipe / Process / Node). */
  recipeName?: string
  isOwner: boolean
  /** Bumped whenever the MAIN process is created/changes here, so RecipeToolPage's own recipe state (mainProcessId) stays in sync without a second fetch. */
  onMainProcessChanged: (mainProcessId: number) => void
  /** The recipe's default preparation guidance (null = the standard text), shown from the Preparation heading. */
  preparationNotes?: string | null
  onSavePreparationNotes?: (notes: string | null) => Promise<void>
  /** An issue to show, asked for from outside the editor (a blocked order's "Show me"); a new token repeats it. */
  issueFocusRequest?: { issue: ReadinessIssue; token: number } | null
  /** A step to open (from the Recipe Process page): its process is shown with the step selected and centred. */
  stepFocusRequest?: { processId: number; nodeId: string; token: number } | null
  /** Open AI Recipe Creation right away (a recipe just created with "Describe it for AI"). */
  openAiCreationOnStart?: boolean
}

/**
 * The Recipe Tool's "Recipe Editor" tab: a process list (MAIN pinned above
 * SUBPROCESSes) alongside the actual Recipe Process canvas for whichever
 * process is selected — all inside one tab, no route change on selection or
 * on opening a subprocess. Every process's data comes from the shared
 * RecipeSessionContext (loaded once by RecipeToolPage) rather than a fetch
 * of its own — "selected process" here is purely a *view* concern (which
 * already-loaded process the canvas currently displays), not a data-loading
 * concern, which is why RecipeProcessEditor/RecipeProcessCanvas below is never
 * remounted (no `key`) when the selection changes: switching processes is
 * navigation within one recipe editing session, not opening a new flow.
 */
export default function RecipeEditorView({
  recipeId, recipeName, isOwner, onMainProcessChanged, preparationNotes = null, onSavePreparationNotes, issueFocusRequest = null, stepFocusRequest = null,
  openAiCreationOnStart = false,
}: RecipeEditorViewProps) {
  const { notifyError, notifySuccess } = useNotifications()
  const session = useRecipeSession()
  const processes = useMemo(() => session?.getProcesses() ?? [], [session])
  const [selectedProcessId, setSelectedProcessId] = useState<number | null>(null)
  const [ancestorTrail, setAncestorTrail] = useState<RecipeProcessBreadcrumbEntry[]>([])
  const [creatingMainProcess, setCreatingMainProcess] = useState(false)
  const [showGenerationModal, setShowGenerationModal] = useState(false)
  // AI generation state lives in the app-level JobTracker (keyed by recipe), so it survives
  // leaving the recipe tool and reloads; only the start request itself is local.
  const jobTracker = useJobTracker()
  const generationJob = useTrackedJob(generationJobKey(recipeId))
  // AI Recipe Creation (process → review/approval → visuals + narration), followed by the same tracker.
  const workflow = useTrackedJob(workflowJobKey(recipeId))?.job ?? null
  const [showAiCreation, setShowAiCreation] = useState(openAiCreationOnStart && isOwner)
  // What the AI menu item that opened AI Recipe Creation pre-ticks (undefined: the user's last selection).
  const [aiCreationTasks, setAiCreationTasks] = useState<RecipeAiTaskType[] | undefined>(undefined)
  const openAiCreation = useCallback((tasks?: RecipeAiTaskType[]) => {
    setAiCreationTasks(tasks)
    setShowAiCreation(true)
  }, [])
  // A generated process waiting for review opens the approval card by itself — once per workflow,
  // also when the user comes back to a recipe whose workflow is still waiting.
  const awaitingReviewId = workflow?.status === 'WAITING_FOR_APPROVAL' && workflow.selectedTasks.includes('PROCESS') && workflow.generationApplied
    ? workflow.workflowId : null
  const [openedReviewFor, setOpenedReviewFor] = useState<string | null>(null)
  if (awaitingReviewId && awaitingReviewId !== openedReviewFor) {
    setOpenedReviewFor(awaitingReviewId)
    setShowAiCreation(true)
  }
  const [startingGeneration, setStartingGeneration] = useState<'CREATE' | 'EDIT' | null>(null)
  const generating = startingGeneration != null || isTrackedJobActive(generationJob)
  const runningMode = startingGeneration ?? (isTrackedJobActive(generationJob) ? (generationJob?.job.mode ?? 'CREATE') : null)
  // The canvas's selected node (so "this step" in an edit instruction can be resolved) and the nodes
  // a just-applied AI edit touched (highlighted briefly).
  const [canvasSelectedNodeId, setCanvasSelectedNodeId] = useState<string | null>(null)
  // A node the canvas should select (a readiness issue the user opened), and when the readiness list should open by itself.
  const [focusNodeRequest, setFocusNodeRequest] = useState<{ nodeId: string; token: number } | null>(null)
  const [readinessOpenToken, setReadinessOpenToken] = useState<number | undefined>(undefined)
  const readiness = useRecipeReadiness()
  const issueCounts = useMemo(() => {
    const counts = new Map<number, ProcessIssueCounts>()
    readiness?.issues.forEach((issue) => {
      if (issue.processId == null) return
      const entry = counts.get(issue.processId) ?? { blockers: 0, warnings: 0 }
      if (issue.severity === 'BLOCKER') entry.blockers += 1
      else entry.warnings += 1
      counts.set(issue.processId, entry)
    })
    return counts
  }, [readiness])
  const [highlightedNodeIds, setHighlightedNodeIds] = useState<ReadonlySet<string> | undefined>(undefined)
  useEffect(() => {
    if (!highlightedNodeIds) return undefined
    const timer = window.setTimeout(() => setHighlightedNodeIds(undefined), EDIT_HIGHLIGHT_MS)
    return () => window.clearTimeout(timer)
  }, [highlightedNodeIds])
  const generationProgress = useMemo(() => {
    if (startingGeneration && !isTrackedJobActive(generationJob)) return { percent: 0, stageLabel: GENERATION_STAGE_LABELS.QUEUED }
    if (!generationJob || !isTrackedJobActive(generationJob)) return null
    const { job } = generationJob
    const stageLabel = (job.mode === 'EDIT' ? EDIT_STAGE_LABELS : GENERATION_STAGE_LABELS)[job.stage] ?? job.stage
    return { percent: job.progressPercent, stageLabel: generationJob.connectionLost ? `${stageLabel} (connection lost, retrying…)` : stageLabel }
  }, [startingGeneration, generationJob])
  // A finished AI edit is never applied unseen: it stays pending (the tracked job is kept) until the
  // user applies or discards it in the modal, which opens by itself when one arrives — also for an
  // edit that finished while the user was away (rediscovered on load).
  const pendingEdit: { jobId: string; edit: RecipeProcessEditResult } | null = useMemo(() => {
    const job = generationJob?.job
    if (!job || job.status !== 'COMPLETED' || !job.result?.edit) return null
    return job.result.mode === 'EDIT' || job.mode === 'EDIT' ? { jobId: job.jobId, edit: job.result.edit } : null
  }, [generationJob])
  const [openedForEditJobId, setOpenedForEditJobId] = useState<string | null>(null)
  if (pendingEdit && pendingEdit.jobId !== openedForEditJobId) {
    setOpenedForEditJobId(pendingEdit.jobId)
    setShowGenerationModal(true)
  }

  const generationError = generationJob && !isTrackedJobActive(generationJob)
    ? (generationJob.pollError ?? (generationJob.job.status === 'FAILED'
      ? (generationJob.job.errorMessage || (generationJob.job.mode === 'EDIT' ? 'Unable to apply that change right now.' : 'Unable to generate this recipe right now.'))
      : null))
    : null

  // Defaults the selection to MAIN (or the first process) once the session has loaded, and keeps
  // it pinned to whatever's currently selected otherwise — including across every later session
  // mutation (edits, saves), since this only actually changes state when the current selection no
  // longer resolves: a save gave a pending process its real id (follow it), or it's gone (an undo
  // removed it — fall back to MAIN). Adjusted while rendering, like the focus request below.
  if (session && !session.loading) {
    const resolveSelection = (current: number | null) => {
      if (current != null && session.getProcess(current)) return current
      if (current != null && session.getProcess(session.resolveProcessId(current))) return session.resolveProcessId(current)
      const list = session.getProcesses()
      const main = list.find((process) => process.type === 'MAIN')
      return main ? main.id : (list[0]?.id ?? null)
    }
    const resolvedSelection = resolveSelection(selectedProcessId)
    if (resolvedSelection !== selectedProcessId) setSelectedProcessId(resolvedSelection)
  }

  // Undo/redo of a change made in another process brings that process into view — adjusted while
  // rendering (not in an effect), so the canvas never renders a frame for a process the undo removed.
  const focusRequest = session?.focusRequest ?? null
  const [handledFocusToken, setHandledFocusToken] = useState<number | null>(null)
  if (focusRequest && focusRequest.token !== handledFocusToken) {
    setHandledFocusToken(focusRequest.token)
    if (focusRequest.processId !== selectedProcessId && session?.getProcess(focusRequest.processId)) {
      setAncestorTrail([])
      setSelectedProcessId(focusRequest.processId)
    }
  }

  const handleCreateMainProcess = useCallback(async () => {
    if (!session) return
    setCreatingMainProcess(true)
    try {
      const created = await RecipeDetailApi.createMainProcess(recipeId)
      session.recordHistory({ focusProcessId: selectedProcessId })
      session.addProcess(created, { persisted: true })
      setSelectedProcessId(created.id)
      onMainProcessChanged(created.id)
      notifySuccess('Main process created')
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to create the main process'))
    } finally {
      setCreatingMainProcess(false)
    }
  }, [recipeId, session, selectedProcessId, onMainProcessChanged, notifySuccess, notifyError])

  /** Opens the recipe's one Preparation, creating it first when the recipe has none yet. */
  const [creatingPreparation, setCreatingPreparation] = useState(false)
  const handleOpenPreparation = useCallback(async () => {
    if (!session) return
    const existing = getPreparationProcess(session.getProcesses())
    if (existing) {
      setAncestorTrail([])
      setSelectedProcessId(existing.id)
      return
    }
    setCreatingPreparation(true)
    try {
      const created = await ProcessApi.create(recipeId, { type: 'SUBPROCESS', name: PREPARATION_NAME })
      // Undoable like any edit: undo removes it from the session and autosave deletes it again.
      session.recordHistory({ focusProcessId: selectedProcessId })
      session.addProcess(created, { persisted: true })
      setAncestorTrail([])
      setSelectedProcessId(created.id)
      notifySuccess('Preparation added. Each flow you build there makes one named component for the main process.')
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to add the Preparation'))
    } finally {
      setCreatingPreparation(false)
    }
  }, [recipeId, session, selectedProcessId, notifySuccess, notifyError])

  /**
   * Recipes saved before the single Preparation may have several preparation processes: the owner's editor folds
   * them into one as soon as the recipe opens — one undoable edit, saved by autosave (mergePreparations.ts).
   */
  const mergeCheckedRef = useRef(false)
  const sessionLoading = session?.loading ?? true
  useEffect(() => {
    if (!session || sessionLoading || mergeCheckedRef.current) return
    mergeCheckedRef.current = true
    if (!isOwner || !session.canEdit) return
    const merge = mergePreparations(session.getProcesses())
    if (!merge) return
    session.recordHistory({ focusProcessId: merge.preparation.id })
    session.addProcess(merge.preparation)
    merge.changed.forEach((process) => session.addProcess(process))
    merge.removedIds.forEach((id) => session.removeProcess(id))
    notifySuccess(`Combined ${merge.removedIds.length + 1} preparations into one Preparation, each kept as its own flow. Use Undo to restore them.`
      + (merge.droppedReferences > 0 ? ` ${merge.droppedReferences} use${merge.droppedReferences === 1 ? '' : 's'} between preparations were removed.` : ''))
  }, [session, sessionLoading, isOwner, notifySuccess])

  /** Renames the main process as one undoable edit (a Preparation flow is named by its last step instead). */
  const handleRenameProcess = useCallback((processId: number, name: string) => {
    if (!session) return
    session.recordHistory({ focusProcessId: processId })
    session.updateProcess(processId, { name })
  }, [session])

  /** Opens the process an issue is about and selects its step (when it names one). */
  /** Shows a process with one of its nodes selected and centred (a readiness issue, a step picked elsewhere). */
  const focusNode = useCallback((requestedProcessId: number, nodeId: string | null) => {
    if (!session) return
    const processId = session.getProcess(requestedProcessId) ? requestedProcessId : session.resolveProcessId(requestedProcessId)
    if (!session.getProcess(processId)) return
    setAncestorTrail([])
    setSelectedProcessId(processId)
    if (nodeId) {
      setFocusNodeRequest({ nodeId, token: Date.now() })
      setHighlightedNodeIds(new Set([nodeId]))
    }
  }, [session])

  const handleNavigateToIssue = useCallback((issue: ReadinessIssue) => {
    if (issue.processId != null) focusNode(issue.processId, issue.nodeId)
  }, [focusNode])

  const existingMain = processes.find((process) => process.type === 'MAIN') ?? null

  /**
   * Starts (or joins the already-running) AI generation of a MAIN process (+ subprocesses where
   * meaningful) from free-form recipe text. The job runs in the background and is followed by the
   * app-level JobTracker, so the user can close the modal, switch processes or leave the tool; the
   * effect below loads the result once it's ready, whenever that is.
   */
  const runGeneration = useCallback(async (recipeText: string, modelKeys: RunModels) => {
    if (generating) return
    setStartingGeneration('CREATE')
    try {
      await jobTracker.startGeneration(recipeId, { mode: 'CREATE', recipeText, modelKeys })
    } finally {
      setStartingGeneration(null)
    }
  }, [generating, jobTracker, recipeId])

  // The process an AI edit would change: the one open on the canvas, once it has something to edit.
  const editTarget = selectedProcessId != null ? (session?.getProcess(selectedProcessId) ?? null) : null
  const editableTarget = editTarget && editTarget.nodes.length > 0 ? editTarget : null

  /**
   * Asks the AI to change the open process. Only the instruction, the process (as it is right now)
   * and the selected node are sent; the AI answers with the smallest set of changes, which the
   * effect below offers for review once the background job finishes.
   */
  const runEdit = useCallback(async (instruction: string, modelKeys: RunModels) => {
    if (generating || !session || !editableTarget) return
    setStartingGeneration('EDIT')
    try {
      await jobTracker.startGeneration(recipeId, {
        mode: 'EDIT',
        recipeText: instruction,
        modelKeys,
        targetProcess: serializeProcessForEdit(editableTarget, session.getProcesses()),
        selectedNodeId: canvasSelectedNodeId,
      })
    } finally {
      setStartingGeneration(null)
    }
  }, [generating, session, editableTarget, jobTracker, recipeId, canvasSelectedNodeId])

  /**
   * Loads a completed generation's result into the current in-memory Recipe session as one
   * undoable editor operation: the recipe as it was is recorded first, so Undo brings it back
   * (including removing the generated subprocesses). Autosave then persists the result like any
   * other edit (RecipeSessionContext creates the still-pending generated processes for real).
   * Replaces the recipe's existing MAIN content in place (same real id — the canvas re-seeds from
   * the session) rather than adding a second MAIN; every generated subprocess is new and simply
   * added alongside whatever subprocesses already existed. Also covers a job that finished while
   * the user was away (rediscovered on load).
   */
  const appliedGenerationJobIdsRef = useRef(new Set<string>())
  useEffect(() => {
    if (!session || session.loading || !generationJob) return
    const { job } = generationJob
    // An edit result waits for the user's review instead (see pendingEdit / handleApplyEdit).
    if (job.status !== 'COMPLETED' || !job.result || job.result.mode === 'EDIT' || job.mode === 'EDIT') return
    // An edit request answered with a whole new flow is refused by the effect below, never applied.
    if (generationJob.requestedMode === 'EDIT' || appliedGenerationJobIdsRef.current.has(job.jobId)) return
    appliedGenerationJobIdsRef.current.add(job.jobId)

    const { processes: generated, mainProcessId, problems } = convertGeneratedResultToProcesses(
      recipeId, job.result, existingMain, session.getProcesses().map((process) => process.id),
      getPreparationProcess(session.getProcesses()))
    // Never load a result whose references don't resolve: dropping them would look like a success
    // while silently losing which prepared component a step uses.
    if (problems.length > 0) {
      console.error('Generated recipe has unresolved references:', problems)
      jobTracker.dismiss(generationJobKey(recipeId))
      notifyError(`The generated recipe is inconsistent (${problems[0]}${problems.length > 1 ? ` and ${problems.length - 1} more` : ''}), so nothing was changed. Please try again.`)
      return
    }

    session.recordHistory({ focusProcessId: mainProcessId })
    generated.forEach((process) => session.addProcess(process))
    setAncestorTrail([])
    setSelectedProcessId(mainProcessId)
    if (!existingMain) onMainProcessChanged(mainProcessId)

    setShowGenerationModal(false)
    jobTracker.dismiss(generationJobKey(recipeId))
    // Part of AI Recipe Creation: the process now waits for the user's review and approval (being
    // in the editor — and autosaved — is not approval). The workflow is re-read once the backend
    // knows the result was loaded, which is what enables "Approve & Continue".
    const forWorkflow = workflow?.generationJobId === job.jobId
    RecipeProcessGenerationApi.markApplied(recipeId, job.jobId)
      .then(() => { if (forWorkflow) jobTracker.refresh(workflowJobKey(recipeId)) })
      .catch((error) => console.error('Unable to mark the generated recipe as applied:', error))
    const generatedPreparation = generated.find((process) => process.type === 'SUBPROCESS')
    const flowCount = generatedPreparation ? getPreparationFlows(generatedPreparation).length : 0
    const summary = flowCount > 0 ? ` Preparation with ${flowCount} flow${flowCount === 1 ? '' : 's'} + main process.` : ''
    if (forWorkflow) {
      // The recipe text did its job: the process exists now (in the editor, autosaved, undoable).
      clearDraft(draftKeys.aiRecipeCreation(recipeId))
      notifySuccess(`Recipe process ready.${summary} Review and edit it, then approve to continue. Use Undo to go back to your previous version.`)
    } else {
      notifySuccess(`AI recipe process ready.${summary} It's saved automatically. Use Undo to go back to your previous version.`)
    }
    // Accepted, but worth a look: e.g. a prepared component the main process never uses. (Repairs are
    // the backend's own corrections and are already reflected in the result.)
    const warnings = job.result.warnings ?? []
    if (warnings.length > 0) notifyError(warnings.join(' · '))
  }, [session, generationJob, recipeId, existingMain, onMainProcessChanged, jobTracker, notifySuccess, notifyError, workflow?.generationJobId])

  // A backend without edit support ignores the mode and returns a whole new flow for an edit request:
  // that must never replace the user's process.
  useEffect(() => {
    const job = generationJob?.job
    if (generationJob?.requestedMode !== 'EDIT' || !job || job.status !== 'COMPLETED' || !job.result) return
    if (job.result.mode === 'EDIT' || job.mode === 'EDIT') return
    jobTracker.dismiss(generationJobKey(recipeId))
    RecipeProcessGenerationApi.markApplied(recipeId, job.jobId).catch(() => {})
    notifyError('The server does not support AI editing yet, so nothing was changed.')
  }, [generationJob, jobTracker, recipeId, notifyError])

  /** Ends the review of an AI edit (applied or not), so the finished job isn't offered again. */
  const finishPendingEdit = useCallback((jobId: string) => {
    jobTracker.dismiss(generationJobKey(recipeId))
    RecipeProcessGenerationApi.markApplied(recipeId, jobId)
      .catch((error) => console.error('Unable to mark the AI edit as handled:', error))
  }, [jobTracker, recipeId])

  /**
   * Applies the reviewed AI edit to its process as one undoable step: only the nodes the edit names
   * change; everything else keeps its id, position and data. If the process changed in a way the
   * edit can't follow (e.g. a step it changes was deleted meanwhile), nothing is applied.
   */
  const handleApplyEdit = useCallback(() => {
    if (!session || !pendingEdit) return
    const { jobId, edit } = pendingEdit
    const processId = session.resolveProcessId(edit.targetProcessId)
    const target = session.getProcess(processId)
    if (!target) {
      notifyError('The process this change was for no longer exists.')
      finishPendingEdit(jobId)
      return
    }
    try {
      const applied = applyProcessEdit(target, edit.operations)
      session.recordHistory({ focusProcessId: processId })
      session.addProcess(applied.process)
      setAncestorTrail([])
      setSelectedProcessId(processId)
      setHighlightedNodeIds(new Set(applied.changedNodeIds))
      setShowGenerationModal(false)
      clearDraft(draftKeys.aiEdit(recipeId))
      notifySuccess(`${edit.summary} Use Undo to revert it.`)
    } catch (error) {
      notifyError(error instanceof ProcessEditConflictError ? error.message : 'Unable to apply this change.')
      if (!(error instanceof ProcessEditConflictError)) console.error('Unable to apply the AI edit:', error)
    }
    finishPendingEdit(jobId)
  }, [session, pendingEdit, finishPendingEdit, notifySuccess, notifyError, recipeId])

  const handleDiscardEdit = useCallback(() => {
    if (pendingEdit) finishPendingEdit(pendingEdit.jobId)
  }, [pendingEdit, finishPendingEdit])

  const pendingEditReview = useMemo<PendingProcessEdit | null>(() => {
    if (!pendingEdit || !session) return null
    const target = session.getProcess(session.resolveProcessId(pendingEdit.edit.targetProcessId))
    return {
      summary: pendingEdit.edit.summary,
      clarification: pendingEdit.edit.clarification ?? null,
      changes: target ? describeProcessEdit(target, pendingEdit.edit.operations ?? []) : [],
    }
  }, [pendingEdit, session])

  const selectedStepLabel = useMemo(() => {
    const node = editableTarget?.nodes.find((candidate) => candidate.id === canvasSelectedNodeId)
    if (!node) return null
    if (node.kind === 'CONDITION') return `Check “${normalizeConditionNodeData(node.data).title}”`
    const stepNumber = editableTarget!.nodes.filter((candidate) => candidate.kind === 'STEP').indexOf(node) + 1
    return `Step ${stepNumber} · ${normalizeRecipeStepNodeData(node.data).title}`
  }, [editableTarget, canvasSelectedNodeId])

  const handleSelectFromSidebar = useCallback((processId: number) => {
    setAncestorTrail([])
    setSelectedProcessId(processId)
  }, [])

  const handleNavigateToList = useCallback(() => {
    setAncestorTrail([])
    setSelectedProcessId((current) => {
      const main = processes.find((process) => process.type === 'MAIN')
      return main ? main.id : current
    })
  }, [processes])

  const handleNavigateToAncestor = useCallback((index: number) => {
    setAncestorTrail((trail) => {
      const target = trail[index]
      if (target) setSelectedProcessId(session ? session.resolveProcessId(target.processId) : target.processId)
      return trail.slice(0, index)
    })
  }, [session])

  /** Opens a used prepared component: the Preparation, on the step that makes it when there is one. */
  const handleOpenSubprocess = useCallback((subprocessId: number, currentProcessName: string, stepId?: string) => {
    setSelectedProcessId((current) => {
      if (current != null) {
        setAncestorTrail((trail) => [...trail, { processId: current, name: currentProcessName }])
      }
      return subprocessId
    })
    if (stepId) {
      setFocusNodeRequest({ nodeId: stepId, token: Date.now() })
      setHighlightedNodeIds(new Set([stepId]))
    }
  }, [])

  // An issue asked for from outside (a blocked order's "Show me"): open it, and the readiness list with it.
  const [handledIssueToken, setHandledIssueToken] = useState<number | null>(null)
  if (issueFocusRequest && issueFocusRequest.token !== handledIssueToken && session && !session.loading) {
    setHandledIssueToken(issueFocusRequest.token)
    setReadinessOpenToken(issueFocusRequest.token)
    handleNavigateToIssue(issueFocusRequest.issue)
  }
  const [handledStepToken, setHandledStepToken] = useState<number | null>(null)
  if (stepFocusRequest && stepFocusRequest.token !== handledStepToken && session && !session.loading) {
    setHandledStepToken(stepFocusRequest.token)
    focusNode(stepFocusRequest.processId, stepFocusRequest.nodeId)
  }
  // The canvas has selected the requested node: nothing left to ask for (and switching back later won't reselect it).
  if (focusNodeRequest && canvasSelectedNodeId === focusNodeRequest.nodeId) setFocusNodeRequest(null)

  if (!session || session.loading) {
    return (
      <div className="flex h-full w-full items-center justify-center" style={{ color: 'var(--flow-text-muted)' }}>
        Loading processes…
      </div>
    )
  }

  if (session.loadError) {
    return (
      <div className="flex h-full w-full flex-col items-center justify-center gap-3 p-4 text-center" role="alert" style={{ color: 'var(--flow-danger)' }}>
        <span>{session.loadError}</span>
        {/* The session loads once per page; reloading the page is the reliable retry. */}
        <button type="button" className="vk-btn vk-btn-secondary" onClick={() => window.location.reload()}>Try again</button>
      </div>
    )
  }

  // Shown while AI Recipe Creation is in progress, waiting for approval, or has failed tasks to retry.
  const aiStatus = workflow && (isWorkflowOpen(workflow) || workflow.status === 'PARTIALLY_COMPLETED' || workflow.status === 'FAILED')
    ? { label: workflowChipLabel(workflow), badge: workflowBadge(workflow), title: workflowHeadline(workflow).title }
    : null

  // Inside the canvas, the sidebar also gets the canvas's "+ Add" actions and collapse (ProcessSidebarActionsContext).
  const processListSidebar = (
    <RecipeProcessSidebar
      processes={processes}
      selectedProcessId={selectedProcessId}
      isOwner={isOwner}
      onSelect={handleSelectFromSidebar}
      onCreateMainProcess={() => void handleCreateMainProcess()}
      creatingMainProcess={creatingMainProcess}
      onOpenPreparation={isOwner && session.canEdit ? () => void handleOpenPreparation() : undefined}
      creatingPreparation={creatingPreparation}
      onRenameProcess={isOwner && session.canEdit ? handleRenameProcess : undefined}
      issueCounts={issueCounts}
      preparationNotes={preparationNotes}
      onSavePreparationNotes={isOwner ? onSavePreparationNotes : undefined}
      readinessPanel={readiness && <RecipeReadinessPanel readiness={readiness} onNavigate={handleNavigateToIssue} openToken={readinessOpenToken} />}
    />
  )

  // Every AI action lives in the top bar's "✨ AI" menu (AiActionsMenu); the canvas adds its own
  // per-process "Generate Step Visuals". Readers get none of the recipe-level ones, as before.
  const aiActions: RecipeAiActions | undefined = isOwner
    ? {
      onOpenAiCreation: openAiCreation,
      onOpenAiEdit: editableTarget ? () => setShowGenerationModal(true) : undefined,
      status: aiStatus && { badge: aiStatus.badge, title: aiStatus.title },
      generationProgress,
    }
    : undefined

  const aiCreationModal = showAiCreation && (
    <RecipeAiCreationModal
      recipeId={recipeId}
      onClose={() => setShowAiCreation(false)}
      willReplaceMain={existingMain != null && existingMain.nodes.length > 0}
      initialTasks={aiCreationTasks}
    />
  )

  const generationModal = showGenerationModal && (
    <RecipeProcessGenerationModal
      onClose={() => setShowGenerationModal(false)}
      onGenerate={runGeneration}
      onEdit={runEdit}
      isGenerating={generating}
      runningMode={runningMode}
      progress={generationProgress}
      jobError={generationError}
      willReplaceMain={existingMain != null}
      editTargetName={editableTarget?.name ?? null}
      selectedStepLabel={selectedStepLabel}
      pendingEdit={pendingEditReview}
      onApplyEdit={handleApplyEdit}
      onDiscardEdit={handleDiscardEdit}
      allowCreate={false}
      draftKey={draftKeys.aiEdit(recipeId)}
      onRequestCreate={() => {
        setShowGenerationModal(false)
        openAiCreation()
      }}
    />
  )

  // Normally RecipeProcessEditor/RecipeProcessCanvas hosts the process list itself (as `sidebarHeader`, split
  // 50/50 with Tool Options in one combined column — see RecipeProcessCanvas.tsx). Without a process to
  // open yet, there's no RecipeProcessCanvas to host it in, so this is the one place the list still needs
  // its own column.
  if (selectedProcessId == null) {
    return (
      <div className="flex h-full w-full">
        <div style={{ width: 260, flexShrink: 0, borderRight: '1px solid var(--flow-border)', overflow: 'hidden' }}>
          {processListSidebar}
        </div>
        <div className="flex min-w-0 flex-1 flex-col items-center justify-center gap-3" style={{ color: 'var(--flow-text-muted)', fontSize: 13 }}>
          <div>{isOwner ? 'Create a main process to get started.' : 'This recipe has no processes yet.'}</div>
          {isOwner && (
            <button
              type="button"
              onClick={() => openAiCreation()}
              style={{ padding: '8px 16px', borderRadius: 8, border: '1px solid var(--flow-accent)', background: 'var(--flow-accent)', color: 'white', fontSize: 12.5, fontWeight: 700, cursor: 'pointer' }}
            >
              {aiStatus ? aiStatus.label : '✨ AI Recipe Creation'}
            </button>
          )}
        </div>
        {generationModal}
        {aiCreationModal}
      </div>
    )
  }

  return (
    <>
      {/* Not keyed by the selection: switching processes is navigation within one editing session —
          the canvas content itself remounts per process, the panel layout around it stays. */}
      <RecipeProcessEditor
        recipeId={recipeId}
        processId={selectedProcessId}
        breadcrumbAncestors={ancestorTrail}
        onNavigateToList={handleNavigateToList}
        onNavigateToAncestor={handleNavigateToAncestor}
        onOpenSubprocess={handleOpenSubprocess}
        recipeName={recipeName}
        aiActions={aiActions}
        sidebarHeader={processListSidebar}
        onSelectedNodeChange={setCanvasSelectedNodeId}
        highlightedNodeIds={highlightedNodeIds}
        focusNodeRequest={focusNodeRequest}
      />
      {generationModal}
      {aiCreationModal}
    </>
  )
}
