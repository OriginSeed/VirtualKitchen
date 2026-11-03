import { useEffect, useMemo, useState } from 'react'
import { AiApi } from '../../../../api/aiApi'
import { RecipeAiWorkflowApi } from '../../../../api/recipeAiWorkflowApi'
import type { RecipeAiTaskType, RecipeAiWorkflowEstimate, RecipeAiWorkflowResponse, RunModelCapability } from '../../../../types/recipeAiWorkflow'
import { isWorkflowOpen } from '../../jobs/jobTracker'
import { useRecipeSession } from '../../session/sessionContext'
import {
  createWorkflowTaskSelection,
  isCompleteExperience,
  runModelCapabilities,
  selectedWorkflowTasks,
  setCompleteExperience,
  toggleWorkflowTask,
  toTaskSelectionRequest,
  WORKFLOW_TASK_ORDER,
  workflowSelectionProblem,
  workflowTaskSelectionFrom,
  type RunModels,
  type WorkflowTaskSelection,
} from '../model/workflowSelection'
import {
  formatCreditRange,
  formatProcessSummary,
  secondsUntil,
  summarizeProcesses,
  WORKFLOW_TASK_DESCRIPTIONS,
  WORKFLOW_TASK_LABELS,
  workflowHeadline,
  workflowTaskRows,
  type WorkflowTaskRow,
} from '../model/workflowView'
import { useRecipeAiWorkflow } from '../useRecipeAiWorkflow'
import { draftKeys } from '../../../../shared/drafts/draftStore'
import { useDraft } from '../../../../shared/drafts/useDraft'
import DraftRestoredNote from '../../../../shared/drafts/DraftRestoredNote'
import { useConfirm } from '../../../../shared/components/confirm/confirmContext'
import { useConfirmDiscard } from '../../../../shared/components/confirm/useConfirmDiscard'
import { runModelOptions } from '../../../../shared/format/modelOptions'
import { useApiData } from '../../../../shared/hooks/useApiData'
import '../../styles/modals.css'

type AiRecipeCreationDraft = { selection: WorkflowTaskSelection; recipeText: string }

type RecipeAiCreationModalProps = {
  recipeId: number
  onClose: () => void
  /** True when generating a process will replace the recipe's current MAIN content. */
  willReplaceMain: boolean
  /** Pre-ticks exactly these tasks (the editor's AI menu items); omitted keeps the user's last selection. */
  initialTasks?: RecipeAiTaskType[]
}

const CREATE_PLACEHOLDER = `Describe your recipe here...

Example:

Make chicken curry. Marinate the chicken with curd and spices. Cut onions and
tomatoes separately. Fry the onions, add tomatoes, then add the marinated
chicken and cook.`

const primaryButton = (busy: boolean, disabled = false) => ({
  padding: '8px 16px', borderRadius: 8, border: '1px solid var(--flow-accent)', background: 'var(--flow-accent)',
  color: 'white', fontSize: 12.5, fontWeight: 700, cursor: busy ? 'wait' : disabled ? 'not-allowed' : 'pointer',
  opacity: disabled ? 0.55 : 1,
})
const secondaryButton = {
  padding: '8px 16px', borderRadius: 8, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)',
  color: 'var(--flow-text-muted)', fontSize: 12.5, fontWeight: 700, cursor: 'pointer',
}
const subtleText = { fontSize: 11.5, color: 'var(--flow-text-subtle)' }

const RUN_MODEL_LABELS: Record<RunModelCapability, string> = {
  TEXT_TO_TEXT: 'Text model',
  TEXT_TO_IMAGE: 'Image model',
  TEXT_TO_SPEECH: 'Narration model',
}

/** What the run model picker offers: the models the user may choose, and what their requests run on now. */
async function loadRunModelChoices() {
  const [preferences, text, image, speech] = await Promise.all([
    AiApi.getMyPreferences(),
    AiApi.getSelectableModels('TEXT_TO_TEXT'),
    AiApi.getSelectableModels('TEXT_TO_IMAGE'),
    AiApi.getSelectableModels('TEXT_TO_SPEECH'),
  ])
  return { effective: preferences.effective, models: { TEXT_TO_TEXT: text, TEXT_TO_IMAGE: image, TEXT_TO_SPEECH: speech } }
}

/**
 * AI Recipe Creation: the user picks what AI should create, AI builds the recipe process first, the
 * user reviews and edits it in the normal editor, and only after "Approve & Continue" does AI create
 * the step visuals and narration — from the process exactly as approved. Everything after the start
 * runs in the background (followed by the JobTracker), so this modal can be closed and reopened at
 * any point; it always shows the workflow's current state.
 *
 * Uses the same modal chrome and progress styling as RecipeProcessGenerationModal.
 */
export default function RecipeAiCreationModal({ recipeId, onClose, willReplaceMain, initialTasks }: RecipeAiCreationModalProps) {
  const ai = useRecipeAiWorkflow(recipeId)
  const { workflow } = ai
  // A finished workflow keeps showing its summary until the user moves on.
  const [startingOver, setStartingOver] = useState(false)
  const showSelection = !workflow || (startingOver && !isWorkflowOpen(workflow))

  return (
    <div className="flow-canvas-export-modal-overlay" onClick={onClose}>
      <div className="flow-canvas-export-modal" onClick={(event) => event.stopPropagation()} style={{ width: 620 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 18px', borderBottom: '1px solid var(--flow-border)' }}>
          <div>
            <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--flow-text)' }}>✨ AI Recipe Creation</div>
            <div style={{ fontSize: 11, color: 'var(--flow-text-subtle)', marginTop: 2 }}>
              AI creates the process first. You stay the chef: review and edit it, and only after you approve does AI create the rest.
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            title={workflow && isWorkflowOpen(workflow) ? 'Close — AI Recipe Creation keeps its progress' : 'Close'}
            style={{ width: 30, height: 30, borderRadius: 7, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)', color: 'var(--flow-text-subtle)', cursor: 'pointer', fontSize: 15 }}
          >
            ✕
          </button>
        </div>

        <div style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12, overflowY: 'auto' }}>
          {showSelection ? (
            <TaskSelectionStep
              recipeId={recipeId}
              willReplaceMain={willReplaceMain}
              initialTasks={initialTasks}
              busy={ai.busy === 'start'}
              error={ai.error}
              onCreate={async (request) => {
                if (workflow && !isWorkflowOpen(workflow)) await ai.dismiss()
                const started = await ai.start(request)
                if (started) setStartingOver(false)
                return started
              }}
              onCancel={onClose}
            />
          ) : (
            <WorkflowStatusStep
              recipeId={recipeId}
              workflow={workflow}
              ai={ai}
              onClose={onClose}
              onStartOver={() => setStartingOver(true)}
            />
          )}
        </div>
      </div>
    </div>
  )
}

// --- step 1: what should AI create? ------------------------------------------------------------

function TaskSelectionStep({ recipeId, willReplaceMain, initialTasks, busy, error, onCreate, onCancel }: {
  recipeId: number
  willReplaceMain: boolean
  initialTasks?: RecipeAiTaskType[]
  busy: boolean
  error: string | null
  onCreate: (request: { selection: ReturnType<typeof toTaskSelectionRequest>; recipeText?: string }) => Promise<boolean>
  onCancel: () => void
}) {
  const session = useRecipeSession()
  // Kept as a draft until it has done its job: the recipe text until its process has been generated
  // and loaded (RecipeEditorView clears it then), so closing, navigating away or a failed run never
  // loses it.
  const draft = useDraft<AiRecipeCreationDraft>(draftKeys.aiRecipeCreation(recipeId), {
    selection: createWorkflowTaskSelection(true),
    recipeText: '',
  })
  const { selection, recipeText } = draft.value
  const setSelection = (update: (current: WorkflowTaskSelection) => WorkflowTaskSelection) =>
    draft.setValue((current) => ({ ...current, selection: update(current.selection) }))
  const setRecipeText = (text: string) => draft.setValue((current) => ({ ...current, recipeText: text }))
  // Opened from a specific AI menu item: tick exactly its tasks, once (the recipe text draft is kept).
  const [presetApplied, setPresetApplied] = useState(false)
  if (initialTasks && !presetApplied) {
    setPresetApplied(true)
    setSelection(() => workflowTaskSelectionFrom(initialTasks))
  }
  const [estimate, setEstimate] = useState<RecipeAiWorkflowEstimate | null>(null)
  const [estimateError, setEstimateError] = useState(false)
  const [touched, setTouched] = useState(false)
  const confirmDiscard = useConfirmDiscard()
  // Models for this run only; '' keeps the account setting. Priced in the estimate, charged when the run goes.
  const [runModels, setRunModels] = useState<RunModels>({})
  const modelChoices = useApiData('run-model-choices', loadRunModelChoices)

  const existingStepCount = useMemo(
    () => summarizeProcesses(session?.getProcesses() ?? []).steps,
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-count whenever the session changes
    [session, session?.version],
  )
  const problem = workflowSelectionProblem(selection, { recipeText, existingStepCount })
  const selectionKey = selectedWorkflowTasks(selection).join(',')
  const runCapabilities = runModelCapabilities(selection)
  const runModelsKey = runCapabilities.map((capability) => runModels[capability] ?? '').join(',')
  const choices = modelChoices.data

  // The estimate follows the selection (debounced, so ticking several boxes asks once).
  useEffect(() => {
    if (!selectionKey) return undefined // nothing selected: no estimate is shown
    const controller = new AbortController()
    const timer = window.setTimeout(() => {
      RecipeAiWorkflowApi.estimate(recipeId, toTaskSelectionRequest(selection, runModels), controller.signal)
        .then((result) => {
          setEstimate(result)
          setEstimateError(false)
        })
        .catch(() => {
          if (!controller.signal.aborted) setEstimateError(true)
        })
    }, 250)
    return () => {
      window.clearTimeout(timer)
      controller.abort()
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- selectionKey and runModelsKey capture the request
  }, [recipeId, selectionKey, runModelsKey])

  const handleCreate = async () => {
    setTouched(true)
    if (problem) return
    const started = await onCreate({
      selection: toTaskSelectionRequest(selection, runModels),
      recipeText: selection.PROCESS ? recipeText.trim() : undefined,
    })
    // Without a process to generate there is no text to keep: the selection has done its job.
    if (started && !selection.PROCESS) draft.clear()
  }

  const complete = isCompleteExperience(selection)

  return (
    <>
      <div style={{ fontSize: 13, fontWeight: 700, color: 'var(--flow-text)' }}>What would you like AI to create?</div>
      <div className="recipe-ai-task-options" role="group" aria-label="What would you like AI to create?">
        {WORKFLOW_TASK_ORDER.map((task) => (
          <label key={task} className={`recipe-ai-task-option${selection[task] ? ' is-checked' : ''}`}>
            <input type="checkbox" checked={selection[task]} disabled={busy} onChange={() => setSelection((current) => toggleWorkflowTask(current, task))} />
            <span>
              <div className="recipe-ai-task-option-label">{WORKFLOW_TASK_LABELS[task]}</div>
              <div className="recipe-ai-task-option-description">{WORKFLOW_TASK_DESCRIPTIONS[task]}</div>
            </span>
          </label>
        ))}
        <label className={`recipe-ai-task-option is-complete${complete ? ' is-checked' : ''}`}>
          <input type="checkbox" checked={complete} disabled={busy} onChange={(event) => setSelection((current) => setCompleteExperience(current, event.target.checked))} />
          <span>
            <div className="recipe-ai-task-option-label">Complete Recipe Experience</div>
            <div className="recipe-ai-task-option-description">All of the above</div>
          </span>
        </label>
      </div>

      {selection.PROCESS ? (
        <>
          {willReplaceMain && (
            <div style={{ fontSize: 11.5, color: 'var(--flow-warning)', background: 'var(--flow-warning-soft)', border: '1px solid var(--flow-warning-border)', borderRadius: 8, padding: '8px 10px' }}>
              ⚠ This recipe already has a main process. Creating a new one will replace it, and the Preparation too when the new recipe has preparation steps. You can Undo it afterwards.
            </div>
          )}
          {draft.restored && recipeText.trim() && <DraftRestoredNote onDiscard={() => void confirmDiscard('recipe text and task selection', draft.clear)} />}
          <textarea
            value={recipeText}
            onChange={(event) => setRecipeText(event.target.value)}
            className="recipe-builder-textarea"
            placeholder={CREATE_PLACEHOLDER}
            rows={8}
            disabled={busy}
            style={{ minHeight: 150 }}
          />
        </>
      ) : selectionKey ? (
        <div style={subtleText}>
          AI will use your current recipe process ({existingStepCount} step{existingStepCount === 1 ? '' : 's'}). You will confirm it before anything is created.
        </div>
      ) : null}

      {choices && runCapabilities.length > 0 && (
        <div className="recipe-ai-run-models" role="group" aria-label="AI models for this run">
          {runCapabilities.map((capability) => (
            <label key={capability} className="recipe-ai-run-model">
              <span>{RUN_MODEL_LABELS[capability]}</span>
              <select
                value={runModels[capability] ?? ''}
                disabled={busy}
                onChange={(event) => setRunModels((current) => ({ ...current, [capability]: event.target.value }))}
              >
                {runModelOptions(choices.models[capability], choices.effective[capability]).map((option) => (
                  <option key={option.value} value={option.value}>{option.label}</option>
                ))}
              </select>
            </label>
          ))}
          <div style={subtleText}>For this run only. Change the account setting in Account Settings.</div>
        </div>
      )}

      {selectionKey && <EstimatePanel estimate={estimate} failed={estimateError} />}

      {(error || (touched && problem)) && (
        <div className="recipe-builder-status recipe-builder-status-error" role="alert">{error || problem}</div>
      )}

      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" onClick={() => void handleCreate()} disabled={busy} style={primaryButton(busy, touched && problem != null)}>
          {busy ? 'Starting…' : 'Create Recipe'}
        </button>
        <button type="button" onClick={onCancel} style={secondaryButton}>Cancel</button>
      </div>
    </>
  )
}

function EstimatePanel({ estimate, failed, title = 'Estimated AI usage' }: {
  estimate: RecipeAiWorkflowEstimate | null
  failed: boolean
  title?: string
}) {
  if (failed && !estimate) return <div className="recipe-ai-estimate">The AI usage estimate isn't available right now.</div>
  if (!estimate) return <div className="recipe-ai-estimate">Estimating AI usage…</div>
  return (
    <div className="recipe-ai-estimate" aria-label={title}>
      {estimate.tasks.map((task) => (
        <div key={task.task} className="recipe-ai-estimate-row" title={task.basis}>
          <span>{WORKFLOW_TASK_LABELS[task.task]} <span style={{ color: 'var(--flow-text-subtle)' }}>· {task.basis}</span></span>
          <span>{formatCreditRange(task.minCredits, task.maxCredits)}</span>
        </div>
      ))}
      <div className="recipe-ai-estimate-row recipe-ai-estimate-total">
        <span>{title}</span>
        <span>{formatCreditRange(estimate.minCredits, estimate.maxCredits)}</span>
      </div>
      <div style={{ marginTop: 4, color: 'var(--flow-text-subtle)' }}>
        {estimate.stepCountKnown
          ? 'Approximate: steps that already have images or narration are reused at no cost.'
          : 'Approximate: the real number of steps is known once the recipe process is created.'}
      </div>
    </div>
  )
}

// --- steps 2+: progress, approval, outcome -------------------------------------------------------

function WorkflowStatusStep({ recipeId, workflow, ai, onClose, onStartOver }: {
  recipeId: number
  workflow: RecipeAiWorkflowResponse
  ai: ReturnType<typeof useRecipeAiWorkflow>
  onClose: () => void
  onStartOver: () => void
}) {
  const session = useRecipeSession()
  const processSummary = useMemo(
    () => summarizeProcesses(session?.getProcesses() ?? []),
    // eslint-disable-next-line react-hooks/exhaustive-deps -- re-count whenever the session changes
    [session, session?.version],
  )
  const rows = workflowTaskRows(workflow, processSummary)
  const headline = workflowHeadline(workflow)
  const waiting = workflow.status === 'WAITING_FOR_APPROVAL'
  const running = workflow.status === 'GENERATING_PROCESS' || workflow.status === 'RUNNING_DOWNSTREAM_TASKS' || workflow.status === 'CREATED'
  const finished = !isWorkflowOpen(workflow)

  // A ticking clock only while a retry is held back (narration's short back-off after a failure).
  const [now, setNow] = useState(() => Date.now())
  const nextRetryAt = rows.map((row) => row.retry?.availableAt).find((at) => secondsUntil(at, now) > 0)
  useEffect(() => {
    if (!nextRetryAt) return undefined
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [nextRetryAt])

  return (
    <>
      <div>
        <div style={{ fontSize: 14, fontWeight: 700, color: 'var(--flow-text)' }}>{headline.title}</div>
        {headline.subtitle && <div style={{ ...subtleText, marginTop: 2 }}>{headline.subtitle}</div>}
      </div>

      <div className="recipe-ai-task-rows">
        {rows.map((row) => (
          <TaskRow key={row.type} row={row} now={now} retrying={ai.busy === 'retry'} onRetry={() => void ai.retry(row.type)} />
        ))}
      </div>

      {/* Only while work runs: a finished workflow's rows say what happened better than "100%" would. */}
      {running && (
        <div className="recipe-builder-progress" role="progressbar" aria-valuenow={workflow.progressPercent} aria-valuemin={0} aria-valuemax={100}
          title="Weighted by task: recipe process, visuals and narration each count for their share of the work.">
          <div className="recipe-builder-progress-track">
            <div className="recipe-builder-progress-fill" style={{ width: `${workflow.progressPercent}%` }} />
          </div>
          <span className="recipe-builder-progress-label">{workflow.progressPercent}%</span>
        </div>
      )}

      {ai.connectionLost && <div style={subtleText}>Connection lost — retrying…</div>}
      {ai.pollError && <div className="recipe-builder-status recipe-builder-status-error" role="alert">Lost track of AI Recipe Creation: {ai.pollError}</div>}

      {waiting && <ApprovalCard recipeId={recipeId} workflow={workflow} processSummary={formatProcessSummary(processSummary)} ai={ai} onReview={onClose} />}

      {ai.error && !waiting && <div className="recipe-builder-status recipe-builder-status-error" role="alert">{ai.error}</div>}

      {running && (
        <div style={subtleText}>
          {workflow.status === 'RUNNING_DOWNSTREAM_TASKS'
            ? 'You can close this window, keep editing or leave the page — progress is saved and shown again when you return. Running AI tasks can\'t be stopped; they finish on their own.'
            : 'The recipe process is being generated in the background. It opens in the editor for your review when it\'s ready. Generation can\'t be stopped once started.'}
        </div>
      )}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        {finished ? (
          <>
            <button type="button" onClick={() => { void ai.dismiss(); onClose() }} style={primaryButton(false)}>Done</button>
            <button type="button" onClick={onStartOver} style={secondaryButton}>Create something else</button>
          </>
        ) : !waiting && (
          <button type="button" onClick={onClose} style={secondaryButton}>Run in background</button>
        )}
      </div>
    </>
  )
}

function TaskRow({ row, now, retrying, onRetry }: { row: WorkflowTaskRow; now: number; retrying: boolean; onRetry: () => void }) {
  const wait = secondsUntil(row.retry?.availableAt, now)
  return (
    <div className={`recipe-ai-task-row tone-${row.tone}`}>
      <span className="recipe-ai-task-icon" aria-hidden>{row.icon}</span>
      <div style={{ minWidth: 0 }}>
        <div className="recipe-ai-task-label">{row.label}</div>
        <div className="recipe-ai-task-detail">{row.detail}</div>
      </div>
      <span className="recipe-ai-task-status">{row.status}</span>
      {row.retry && (
        <button type="button" onClick={onRetry} disabled={retrying || wait > 0} style={{ ...secondaryButton, padding: '5px 10px', fontSize: 11.5, color: 'var(--flow-accent-strong)' }}>
          {wait > 0 ? `${row.retry.label} (${wait}s)` : row.retry.label}
        </button>
      )}
    </div>
  )
}

function ApprovalCard({ recipeId, workflow, processSummary, ai, onReview }: {
  recipeId: number
  workflow: RecipeAiWorkflowResponse
  processSummary: string
  ai: ReturnType<typeof useRecipeAiWorkflow>
  onReview: () => void
}) {
  const downstream = workflow.selectedTasks.filter((task) => task !== 'PROCESS')
  const generated = workflow.selectedTasks.includes('PROCESS')
  // The generated process must be in the editor (and so in what gets saved) before it can be approved.
  const ready = !generated || workflow.generationApplied
  const [estimate, setEstimate] = useState<RecipeAiWorkflowEstimate | null>(null)
  const [estimateFailed, setEstimateFailed] = useState(false)

  useEffect(() => {
    if (!ready || downstream.length === 0) return undefined
    const controller = new AbortController()
    RecipeAiWorkflowApi.estimateApproval(recipeId, workflow.workflowId, controller.signal)
      .then(setEstimate)
      .catch(() => {
        if (!controller.signal.aborted) setEstimateFailed(true)
      })
    return () => controller.abort()
  }, [recipeId, workflow.workflowId, ready, downstream.length])

  const busy = ai.busy === 'approve'
  const confirm = useConfirm()
  const handleDiscard = async () => {
    const skipped = downstream.map((task) => WORKFLOW_TASK_LABELS[task].toLowerCase()).join(' and ')
    if (await confirm({
      eyebrow: 'Discard AI Recipe Creation',
      title: 'Stop AI Recipe Creation here?',
      message: `This AI Recipe Creation run is discarded${skipped ? ` — no ${skipped} will be created` : ''}. The recipe process stays as it is. This can't be undone; you'd have to start AI Recipe Creation again.`,
      confirmLabel: 'Discard',
    })) void ai.discard()
  }
  return (
    <>
      <div className="recipe-ai-approval">
        <div style={{ fontWeight: 700 }}>{generated ? '✓ ' : ''}{processSummary}</div>
        <div style={{ marginTop: 4 }}>
          {downstream.length === 0
            ? 'Review the generated recipe process. Approving finishes AI Recipe Creation; no further AI work runs.'
            : `Review${generated ? ' the generated' : ' your'} recipe process before AI creates ${downstream.map((task) => WORKFLOW_TASK_LABELS[task].toLowerCase()).join(' and ')}. Edit anything you like — AI uses the process exactly as it is when you approve.`}
        </div>
      </div>

      {downstream.length > 0 && ready && <EstimatePanel estimate={estimate} failed={estimateFailed} title="Approving will use about" />}

      {!ready && <div style={subtleText}>Loading the generated recipe process into the editor…</div>}
      {ai.error && <div className="recipe-builder-status recipe-builder-status-error" role="alert">{ai.error}</div>}

      <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
        <button type="button" onClick={onReview} style={secondaryButton}>Review &amp; Edit</button>
        <button type="button" onClick={() => void ai.approve()} disabled={busy || !ready} style={primaryButton(busy, !ready)}>
          {busy ? 'Saving & approving…' : downstream.length === 0 ? 'Approve' : 'Approve & Continue'}
        </button>
        {workflow.cancellable && (
          <button type="button" onClick={() => void handleDiscard()} disabled={ai.busy === 'discard'} style={{ ...secondaryButton, marginLeft: 'auto' }}
            title="Stop here: no visuals or narration are created. The recipe process stays as it is.">
            Discard
          </button>
        )}
      </div>
    </>
  )
}
