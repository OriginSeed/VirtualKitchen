import { useState } from 'react'
import type { ProcessGenerationMode } from '../../../../types/recipe'
import '../../styles/modals.css'
import { useDraft } from '../../../../shared/drafts/useDraft'
import DraftRestoredNote from '../../../../shared/drafts/DraftRestoredNote'
import { useConfirm } from '../../../../shared/components/confirm/confirmContext'
import { useConfirmDiscard } from '../../../../shared/components/confirm/useConfirmDiscard'
import { errorMessage as describeError } from '../../../../shared/errors'
import type { RunModelCapability } from '../../../../types/recipeAiWorkflow'
import { pickRunModels, useRunModelChoices } from '../../workflow/model/runModels'
import type { RunModels } from '../../workflow/model/workflowSelection'
import { RunModelSelects } from '../../workflow/components/RunModelPicker'

/** A finished AI edit waiting for the user's review — either a list of changes or a question back. */
export type PendingProcessEdit = {
  summary: string
  /** One human-readable line per change (empty when the AI asked a question instead). */
  changes: string[]
  clarification: string | null
}

type RecipeProcessGenerationModalProps = {
  onClose: () => void
  /** `modelKeys`: the text model picked in this dialog for the run (none: the account setting). */
  onGenerate: (recipeText: string, modelKeys: RunModels) => Promise<void>
  /** Asks the AI to change `editTargetName`'s process. */
  onEdit: (instruction: string, modelKeys: RunModels) => Promise<void>
  isGenerating: boolean
  /** Which kind of job is running, so the modal shows (and locks to) that mode while it works. */
  runningMode: ProcessGenerationMode | null
  progress: { percent: number; stageLabel: string } | null
  /** Why the most recent background generation failed, if it did. */
  jobError?: string | null
  /** True when generating will replace the recipe's current MAIN process content — shown as an inline warning, not a separate confirm dialog, so the user sees it right where they're about to act. */
  willReplaceMain: boolean
  /** Name of the open process an edit would change, or null when there is nothing to edit yet (Edit mode unavailable). */
  editTargetName: string | null
  /** Title of the selected step, if any, so the user knows "this step" will refer to it. */
  selectedStepLabel: string | null
  pendingEdit: PendingProcessEdit | null
  onApplyEdit: () => void
  onDiscardEdit: () => void
  /**
   * False when creating a new flow lives elsewhere (AI Recipe Creation): the modal is then the
   * "Edit with AI" dialog only, and "Generate a new flow instead" hands over via `onRequestCreate`.
   */
  allowCreate?: boolean
  onRequestCreate?: () => void
  /**
   * Where the typed instruction is kept as a draft, so closing the dialog or navigating away doesn't
   * lose it; the host clears it once the change was applied.
   */
  draftKey?: string | null
}

const CREATE_PLACEHOLDER = `Describe your recipe here...

Example:

Make chicken curry. Marinate the chicken with curd and spices. Cut onions and
tomatoes separately. Fry the onions, add tomatoes, then add the marinated
chicken and cook.`

const EDIT_PLACEHOLDER = `Describe the change you want...

Examples:
• Add a step to season the pasta with salt before draining.
• Change the cooking time from 10 minutes to 8 minutes.
• Replace olive oil with butter.
• Remove the last step.`

/** Generating or editing a process is one text-model call. */
const TEXT_MODEL: RunModelCapability[] = ['TEXT_TO_TEXT']

const primaryButton = (busy: boolean) => ({
  padding: '8px 16px', borderRadius: 8, border: '1px solid var(--flow-accent)', background: 'var(--flow-accent)',
  color: 'white', fontSize: 12.5, fontWeight: 700, cursor: busy ? 'wait' : 'pointer',
})
const secondaryButton = {
  padding: '8px 16px', borderRadius: 8, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)',
  color: 'var(--flow-text-muted)', fontSize: 12.5, fontWeight: 700, cursor: 'pointer',
}

/**
 * "AI" entry point of the Recipe Process — either generates a whole new flow from recipe text, or
 * changes the open process from a natural-language instruction (the AI returns only the changes,
 * which the user reviews before they're applied). Reuses the same modal chrome as
 * RecipeProcessCanvas's Export dialog (`.flow-canvas-export-modal*`) and the progress-bar styling
 * (`.recipe-builder-progress*`), both from styles/modals.css, rather than introducing a new
 * visual pattern. It's a modal because generation is an occasional action, not a constant
 * companion to editing.
 */
export default function RecipeProcessGenerationModal({
  onClose, onGenerate, onEdit, isGenerating, runningMode, progress, jobError, willReplaceMain,
  editTargetName, selectedStepLabel, pendingEdit, onApplyEdit, onDiscardEdit, allowCreate = true, onRequestCreate, draftKey = null,
}: RecipeProcessGenerationModalProps) {
  const [chosenMode, setChosenMode] = useState<ProcessGenerationMode>(editTargetName ? 'EDIT' : 'CREATE')
  const { value: text, setValue: setText, clear: clearText, restored } = useDraft(draftKey, '')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const confirmDiscard = useConfirmDiscard()
  // The text model for this run, picked before anything is sent ('' = the account setting).
  const modelChoices = useRunModelChoices()
  const [runModels, setRunModels] = useState<RunModels>({})

  const mode: ProcessGenerationMode = !allowCreate || pendingEdit
    ? 'EDIT'
    : (isGenerating && runningMode) ? runningMode : (editTargetName ? chosenMode : 'CREATE')
  const editing = mode === 'EDIT'

  const handleSubmit = async () => {
    const trimmed = text.trim()
    if (!trimmed) {
      setErrorMessage(editing ? 'Describe the change before sending it.' : 'Enter a recipe before generating.')
      return
    }
    setErrorMessage(null)
    try {
      const modelKeys = pickRunModels(runModels, TEXT_MODEL)
      await (editing ? onEdit(trimmed, modelKeys) : onGenerate(trimmed, modelKeys))
    } catch (error) {
      setErrorMessage(describeError(error, 'Unable to reach the AI right now.'))
    }
  }

  const switchToCreate = () => {
    onDiscardEdit()
    if (allowCreate) setChosenMode('CREATE')
    else onRequestCreate?.()
  }

  return (
    <div className="flow-canvas-export-modal-overlay" onClick={onClose}>
      <div className="flow-canvas-export-modal" onClick={(event) => event.stopPropagation()}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '14px 18px', borderBottom: '1px solid var(--flow-border)' }}>
          <div>
            <div style={{ fontWeight: 700, fontSize: 14, color: 'var(--flow-text)' }}>
              {editing ? '✨ Edit Flow with AI' : '✨ Generate Recipe with AI'}
            </div>
            <div style={{ fontSize: 11, color: 'var(--flow-text-subtle)', marginTop: 2 }}>
              {editing
                ? `Changes only what you ask for in “${editTargetName ?? 'this process'}”. You review the changes before they're applied, and Undo reverts them.`
                : 'Creates a MAIN process (and subprocesses where meaningful) from your description. The result is saved automatically, and Undo brings back your previous version.'}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            title={isGenerating ? 'Close — the AI keeps working in the background' : 'Close'}
            style={{ width: 30, height: 30, borderRadius: 7, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)', color: 'var(--flow-text-subtle)', cursor: 'pointer', fontSize: 15 }}
          >
            ✕
          </button>
        </div>

        <div style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          {allowCreate && editTargetName && !pendingEdit && (
            <div className="recipe-ai-mode-toggle" role="radiogroup" aria-label="What should the AI do?">
              {(['EDIT', 'CREATE'] as const).map((option) => (
                <button
                  key={option}
                  type="button"
                  role="radio"
                  aria-checked={mode === option}
                  disabled={isGenerating}
                  className={`recipe-ai-mode-option${mode === option ? ' is-active' : ''}`}
                  onClick={() => setChosenMode(option)}
                >
                  {option === 'EDIT' ? 'Edit current flow' : 'Generate new flow'}
                </button>
              ))}
            </div>
          )}

          {pendingEdit ? (
            <PendingEditReview pendingEdit={pendingEdit} onApply={onApplyEdit} onDiscard={onDiscardEdit} onSwitchToCreate={switchToCreate} />
          ) : (
            <>
              {!editing && willReplaceMain && (
                <div style={{ fontSize: 11.5, color: 'var(--flow-warning)', background: 'var(--flow-warning-soft)', border: '1px solid var(--flow-warning-border)', borderRadius: 8, padding: '8px 10px' }}>
                  ⚠ This recipe already has a main process. Generating will replace it, and the Preparation too when the new recipe has preparation steps. You can Undo it afterwards.
                  {editTargetName && allowCreate ? ' To change only part of it, use “Edit current flow”.' : ''}
                </div>
              )}

              {editing && selectedStepLabel && (
                <div style={{ fontSize: 11.5, color: 'var(--flow-text-subtle)' }}>
                  Selected: <strong>{selectedStepLabel}</strong> — “this step” refers to it.
                </div>
              )}

              {restored && text.trim() && !isGenerating && <DraftRestoredNote onDiscard={() => void confirmDiscard(editing ? 'change description' : 'recipe text', clearText)} />}
              <textarea
                value={text}
                onChange={(event) => setText(event.target.value)}
                className="recipe-builder-textarea"
                placeholder={editing ? EDIT_PLACEHOLDER : CREATE_PLACEHOLDER}
                rows={editing ? 5 : 10}
                disabled={isGenerating}
                style={{ minHeight: editing ? 110 : 180 }}
              />

              <RunModelSelects
                choices={modelChoices.data}
                capabilities={TEXT_MODEL}
                value={runModels}
                onChange={setRunModels}
                disabled={isGenerating}
              />

              {(errorMessage || (!isGenerating && jobError)) && (
                <div className="recipe-builder-status recipe-builder-status-error" role="alert">{errorMessage || jobError}</div>
              )}

              {isGenerating && (
                <div style={{ fontSize: 11.5, color: 'var(--flow-text-subtle)' }}>
                  {editing
                    ? 'The AI is working on your change in the background — you can close this and keep editing. You\'ll review the changes here before anything is applied.'
                    : 'Generation is running in the background — you can close this, keep editing, or leave the page. The result will load here when it\'s ready.'}
                </div>
              )}

              {isGenerating && progress && (
                <div className="recipe-builder-progress" role="progressbar" aria-valuenow={progress.percent} aria-valuemin={0} aria-valuemax={100} title={progress.stageLabel}>
                  <div className="recipe-builder-progress-track">
                    <div className="recipe-builder-progress-fill" style={{ width: `${progress.percent}%` }} />
                  </div>
                  <span className="recipe-builder-progress-label">{progress.percent}%</span>
                </div>
              )}

              <div style={{ display: 'flex', gap: 8 }}>
                <button type="button" onClick={() => void handleSubmit()} disabled={isGenerating} style={primaryButton(isGenerating)}>
                  {isGenerating ? (progress?.stageLabel ?? 'Working…') : editing ? 'Suggest changes' : 'Generate'}
                </button>
                <button type="button" onClick={onClose} style={secondaryButton}>
                  {isGenerating ? 'Run in background' : 'Cancel'}
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </div>
  )
}

function PendingEditReview({ pendingEdit, onApply, onDiscard, onSwitchToCreate }: {
  pendingEdit: PendingProcessEdit
  onApply: () => void
  onDiscard: () => void
  onSwitchToCreate: () => void
}) {
  const confirm = useConfirm()
  // Discarding the reviewed changes throws away the AI's work (nothing was applied yet); a question
  // back from the AI ("Rephrase") has nothing to lose, so only this asks first.
  const handleDiscard = async () => {
    const count = pendingEdit.changes.length
    if (await confirm({
      eyebrow: 'Discard AI changes',
      title: 'Discard the suggested changes?',
      message: `The ${count > 0 ? `${count} ` : ''}change${count === 1 ? '' : 's'} the AI suggested will be thrown away without being applied. Your flow stays as it is; to get them back you'd have to ask the AI again.`,
      confirmLabel: 'Discard',
    })) onDiscard()
  }

  if (pendingEdit.clarification) {
    return (
      <>
        <div className="recipe-ai-edit-review" role="status">
          <div style={{ fontWeight: 700, marginBottom: 4 }}>The AI needs a bit more detail</div>
          <div>{pendingEdit.clarification}</div>
        </div>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
          <button type="button" onClick={onDiscard} style={primaryButton(false)}>Rephrase</button>
          <button type="button" onClick={onSwitchToCreate} style={secondaryButton}>Generate a new flow instead</button>
        </div>
      </>
    )
  }

  return (
    <>
      <div className="recipe-ai-edit-review">
        <div style={{ fontWeight: 700, marginBottom: 6 }}>{pendingEdit.summary}</div>
        <ul style={{ margin: 0, paddingLeft: 18, display: 'flex', flexDirection: 'column', gap: 3 }}>
          {pendingEdit.changes.map((change, index) => <li key={index}>{change}</li>)}
        </ul>
      </div>
      <div style={{ fontSize: 11.5, color: 'var(--flow-text-subtle)' }}>
        Only these changes are made; the rest of the flow stays as it is. Undo reverts them in one step.
      </div>
      <div style={{ display: 'flex', gap: 8 }}>
        <button type="button" onClick={onApply} style={primaryButton(false)}>Apply changes</button>
        <button type="button" onClick={() => void handleDiscard()} style={secondaryButton}>Discard</button>
      </div>
    </>
  )
}
