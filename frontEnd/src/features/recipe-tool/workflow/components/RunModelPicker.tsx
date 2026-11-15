import { useEffect, useId, useRef, useState, type KeyboardEvent as ReactKeyboardEvent } from 'react'
import type { RunModelCapability } from '../../../../types/recipeAiWorkflow'
import { runModelOptions } from '../../../../shared/format/modelOptions'
import { RUN_MODEL_LABELS, runModelsFor, useRunModelChoices, type RunModelChoices } from '../model/runModels'
import type { RunModels } from '../model/workflowSelection'
import type { ExistingAssetCounts } from '../model/existingAssets'
import ExistingAssetsChoice from './ExistingAssetsChoice'
import '../../styles/modals.css'

/** One select per capability. Renders nothing until the choices have loaded. */
export function RunModelSelects({ choices, capabilities, value, onChange, disabled, note }: {
  choices: RunModelChoices | null | undefined
  capabilities: RunModelCapability[]
  value: RunModels
  onChange: (next: RunModels) => void
  disabled?: boolean
  /** Under the selects; defaults to saying the choice is for this run only. */
  note?: string
}) {
  if (!choices || capabilities.length === 0) return null
  return (
    <div className="recipe-ai-run-models" role="group" aria-label="AI models for this run">
      {capabilities.map((capability) => (
        <label key={capability} className="recipe-ai-run-model">
          <span>{RUN_MODEL_LABELS[capability]}</span>
          <select
            value={value[capability] ?? ''}
            disabled={disabled}
            onChange={(event) => onChange({ ...value, [capability]: event.target.value })}
          >
            {runModelOptions(choices.models[capability], choices.effective[capability]).map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
      ))}
      <div style={{ fontSize: 11.5, color: 'var(--flow-text-subtle)' }}>
        {note ?? 'For this run only. Change the account setting in Account Settings.'}
      </div>
    </div>
  )
}

const FOCUSABLE = 'button:not([disabled]), select:not([disabled]), input:not([disabled]), [href], [tabindex]:not([tabindex="-1"])'

/**
 * Asks which models to use before an AI action that has no form of its own starts (Generate Step Visuals, a
 * retry). Nothing is started until the user confirms; Esc, Cancel or the backdrop cancel.
 */
export function ModelChoiceDialog({ title, description, capabilities, initial = {}, existing, confirmLabel, onConfirm, onCancel }: {
  title: string
  description?: string
  capabilities: RunModelCapability[]
  /** Preselected models, e.g. the run's earlier choices for a retry. */
  initial?: RunModels
  /** What the steps already have: when any, the dialog also asks to keep them (default) or replace them. */
  existing?: ExistingAssetCounts
  confirmLabel: string
  /**
   * `modelKeys`: every asked capability, '' where the user kept the account setting (so a retry can clear a run's
   * choice). `overwriteExisting`: replace what the steps already have (false: keep it).
   */
  onConfirm: (modelKeys: RunModels, overwriteExisting: boolean) => void
  onCancel: () => void
}) {
  const choices = useRunModelChoices()
  const [models, setModels] = useState<RunModels>(initial)
  const [overwrite, setOverwrite] = useState(false)
  const titleId = useId()
  const dialogRef = useRef<HTMLDivElement>(null)
  const onCancelRef = useRef(onCancel)
  useEffect(() => {
    onCancelRef.current = onCancel
  })

  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    dialogRef.current?.querySelector<HTMLElement>(FOCUSABLE)?.focus()
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      // Ours alone: an editor or walkthrough underneath must not also react.
      event.stopPropagation()
      event.preventDefault()
      onCancelRef.current()
    }
    window.addEventListener('keydown', onKeyDown, true)
    return () => {
      window.removeEventListener('keydown', onKeyDown, true)
      opener?.focus()
    }
  }, [])

  // Once the selects arrive, focus the first one (the dialog opened with only Cancel focusable).
  const loaded = Boolean(choices.data)
  useEffect(() => {
    if (loaded) dialogRef.current?.querySelector<HTMLElement>('select')?.focus()
  }, [loaded])

  const trapFocus = (event: ReactKeyboardEvent) => {
    if (event.key !== 'Tab' || !dialogRef.current) return
    const focusable = Array.from(dialogRef.current.querySelectorAll<HTMLElement>(FOCUSABLE))
    if (focusable.length === 0) return
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div
      className="flow-canvas-export-modal-overlay"
      onMouseDown={(event) => {
        if (event.target === event.currentTarget) onCancel()
      }}
    >
      <div
        ref={dialogRef}
        className="flow-canvas-export-modal model-choice-dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={trapFocus}
        onMouseDown={(event) => event.stopPropagation()}
        onClick={(event) => event.stopPropagation()}
        style={{ width: 'min(440px, calc(100vw - 32px))' }}
      >
        <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--flow-border)' }}>
          <div id={titleId} style={{ fontWeight: 700, fontSize: 14, color: 'var(--flow-text)' }}>{title}</div>
          {description && <div style={{ fontSize: 11.5, color: 'var(--flow-text-subtle)', marginTop: 2 }}>{description}</div>}
        </div>
        <div style={{ padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 12 }}>
          {choices.error ? (
            <div className="recipe-builder-status recipe-builder-status-error" role="alert">
              Couldn&apos;t load the available models. Your account setting will be used.
            </div>
          ) : !choices.data ? (
            <div style={{ fontSize: 12, color: 'var(--flow-text-subtle)' }}>Loading models…</div>
          ) : (
            <RunModelSelects choices={choices.data} capabilities={capabilities} value={models} onChange={setModels} />
          )}
          {existing && <ExistingAssetsChoice counts={existing} overwrite={overwrite} onChange={setOverwrite} />}
          <div style={{ display: 'flex', gap: 8 }}>
            <button
              type="button"
              className="model-choice-confirm"
              disabled={!choices.data && !choices.error}
              onClick={() => onConfirm(runModelsFor(models, capabilities), overwrite)}
              style={{
                padding: '8px 16px', borderRadius: 8, border: '1px solid var(--flow-accent)', background: 'var(--flow-accent)',
                color: 'white', fontSize: 12.5, fontWeight: 700, cursor: 'pointer',
              }}
            >
              {confirmLabel}
            </button>
            <button
              type="button"
              onClick={onCancel}
              style={{
                padding: '8px 16px', borderRadius: 8, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)',
                color: 'var(--flow-text-muted)', fontSize: 12.5, fontWeight: 700, cursor: 'pointer',
              }}
            >
              Cancel
            </button>
          </div>
        </div>
      </div>
    </div>
  )
}
