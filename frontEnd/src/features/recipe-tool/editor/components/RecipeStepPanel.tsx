import { useState } from 'react'
import '../../styles/RecipePropertiesPanel.css'
import '../../../../styles/flow-ui.css'
import SearchableSelect, { type SearchableSelectOption } from '../../../../shared/components/SearchableSelect'
import { useConfirm } from '../../../../shared/components/confirm/confirmContext'
import IngredientSelector from './IngredientSelector'
import ZoomableImage from '../../../../shared/components/image-viewer/ZoomableImage'
import { draftKeys } from '../../../../shared/drafts/draftStore'
import type { Process } from '../../../../types/process'
import { PreparationStyleSelect, UnitSelect } from './StepFieldInputs'
import {
  ACTIONS_BY_CATEGORY,
  ACTION_CATEGORY_ORDER,
  CUSTOM_ACTION_ID,
  actionAllowsIngredients,
  actionAllowsProcesses,
  getActionCategoryLabel,
  getStepActionById,
} from '../../../../shared/catalog/actionCatalog'
import { getStepActionSchema, isStepFieldEnabled, type StepSchemaFieldConfig } from '../../../../shared/catalog/actionSchemaCatalog'
import { getIngredientById } from '../../../../shared/catalog/ingredientCatalog'
import { CUSTOM_UNIT_ID, isQuantifiableUnit } from '../../../../shared/catalog/unitCatalog'
import { DURATION_UNIT_OPTIONS, TEMPERATURE_UNIT_OPTIONS } from '../../../../shared/catalog/stepFieldCatalog'
import { CUSTOM_FLAME_LEVEL_ID, FLAME_LEVEL_CATALOG } from '../../../../shared/catalog/flameLevelCatalog'
import {
  getActionOnIngredientDisplayName,
  getRecipeStepTitle,
  normalizeRecipeStepNodeData,
  type ActionOnIngredient,
  type RecipeStepActionOnProcess,
} from '../../model/recipeStepData'
import type { ReadinessIssue } from '../../model/recipeReadiness'
import {
  PREPARED_REFERENCE_PROBLEM_LABELS,
  describePreparedReference,
  getPreparedOutputs,
  preparedReferenceKey,
  type PreparedOutput,
} from '../../model/preparationFlows'
import {
  STEP_OUTPUT_PROBLEM_LABELS,
  getAvailableStepOutputs,
  getStepOutputLabel,
  getStepOutputReferenceProblem,
  getStepsMissingOutput,
  type StepOutputGraph,
} from '../../model/recipeStepOutputs'
import StepDescriptionField, { type StepDataUpdater } from './annotations/StepDescriptionField'

type RecipeStepPanelProps = {
  node: { id: string; data: unknown }
  /** Every process of the recipe — the Preparation's step outputs are what a main step can use (see preparationFlows.ts). */
  recipeProcesses: Process[]
  /** The step belongs to a preparation flow: preparations are independent, so only main steps use prepared outputs. */
  isPreparation?: boolean
  updateStepField: (nodeId: string, field: string, value: string) => void
  updateActionOnIngredients: (nodeId: string, ingredients: ActionOnIngredient[]) => void
  updateActionOnProcesses: (nodeId: string, references: RecipeStepActionOnProcess[]) => void
  updateActionOnSteps: (nodeId: string, stepIds: string[]) => void
  /** One undoable change of the step's data — the description editor adds an annotation's target with its text. */
  updateStepData: StepDataUpdater
  /** The open process's step-output sources/reachability — decides which earlier steps' outputs this step may use. */
  stepOutputGraph: StepOutputGraph
  onDeleteNode?: (nodeId: string) => void
  onDuplicateNode?: (nodeId: string) => void
  /** Opens the Preparation on the step that makes a used component (`stepId` absent for a legacy whole-process use). */
  onOpenSubprocess?: (processId: number, stepId?: string) => void
  /**
   * Triggers the whole-process visualization job (see RecipeProcessCanvas's generateVisuals) — V1 has no
   * single-step generation endpoint, so "Regenerate" here re-runs the same process-wide job rather
   * than a step-scoped call; steps that already have an image are still re-checked cheaply (the
   * backend's asset lookup only regenerates what's actually missing).
   */
  onGenerateVisuals?: () => void
  isGeneratingVisuals?: boolean
  /** Button label while generating, e.g. "Generating… 2/5" — falls back to "Generating…". */
  visualsProgressLabel?: string
  /** Readiness issues about this step (shown under its description). */
  issues?: ReadinessIssue[]
  /** 'popup': the editor opened on the node itself — a close button, and Save & close at the bottom. */
  variant?: 'panel' | 'popup'
  autoFocusDescription?: boolean
  onClose?: () => void
  onSaveAndClose?: () => void
}

type ActionOnTab = 'INGREDIENTS' | 'PROCESSES' | 'STEPS'

/** The one Action On entry being edited: an ingredient by its row index, a subprocess by id, a step output by step id. */
type ActionOnEdit =
  | { nodeId: string; kind: 'INGREDIENT'; index: number }
  | { nodeId: string; kind: 'PROCESS'; key: string }
  | { nodeId: string; kind: 'STEP'; stepId: string }

const actionOptions: SearchableSelectOption[] = ACTION_CATEGORY_ORDER.flatMap((category) =>
  ACTIONS_BY_CATEGORY[category].map((action) => ({
    value: action.id,
    label: action.displayName,
    icon: action.icon,
    category: getActionCategoryLabel(category),
    description: action.description,
    keywords: action.aliases,
  }))
)

const flameLevelOptions: SearchableSelectOption[] = FLAME_LEVEL_CATALOG.map((level) => ({ value: level.id, label: level.label, description: level.description }))

/** Step-level schema fields rendered under Advanced Options (per-ingredient ones live on the Action On rows). */
const ADVANCED_FIELD_KEYS = new Set(['temperature', 'flameLevelId', 'duration', 'repeatInterval'])

const requirementHint = (field: StepSchemaFieldConfig) =>
  field.requirement === 'required' ? ' *' : field.requirement === 'recommended' ? ' (recommended)' : ''

export default function RecipeStepPanel({
  node,
  recipeProcesses,
  isPreparation = false,
  updateStepField,
  updateActionOnIngredients,
  updateActionOnProcesses,
  updateActionOnSteps,
  updateStepData,
  stepOutputGraph,
  onDeleteNode,
  onDuplicateNode,
  onOpenSubprocess,
  onGenerateVisuals,
  isGeneratingVisuals,
  visualsProgressLabel,
  issues = [],
  variant = 'panel',
  autoFocusDescription,
  onClose,
  onSaveAndClose,
}: RecipeStepPanelProps) {
  const [actionOnTab, setActionOnTab] = useState<ActionOnTab>('INGREDIENTS')
  const [advancedOpen, setAdvancedOpen] = useState(false)
  const confirm = useConfirm()
  // Tied to the step it was opened on, so selecting another step never shows a stale editor.
  const [actionOnEdit, setActionOnEdit] = useState<ActionOnEdit | null>(null)
  const currentEdit = actionOnEdit?.nodeId === node.id ? actionOnEdit : null
  const closeEdit = () => setActionOnEdit(null)
  const normalized = normalizeRecipeStepNodeData(node.data)
  const step = normalized.step
  const title = getRecipeStepTitle(step, {
    preparedName: (reference) => describePreparedReference(recipeProcesses, reference).label,
    stepOutputLabel: (stepId) => getStepOutputLabel(stepOutputGraph, stepId),
  })
  const visualization = normalized.visualization
  const schema = getStepActionSchema(step.action)
  const actionDefinition = step.action ? getStepActionById(step.action) : null
  const quantityRelevant = isStepFieldEnabled(step.action, 'quantity')
  // quantity/unitId/preparationStyleId are per-ingredient (the Action On rows below), not step-level.
  const extraFields = schema.fields.filter((field) => ADVANCED_FIELD_KEYS.has(field.key))

  // Only the Action On target kinds the action accepts are offered (catalog `actionOn`); an existing
  // value of a now-disallowed kind stays visible under its tab so it can still be removed.
  const ingredientsAllowed = actionAllowsIngredients(step.action) || step.actionOn.ingredients.length > 0
  // Prepared outputs are used by main steps; a preparation step only shows ones it already has (to remove them).
  const canUsePrepared = actionAllowsProcesses(step.action) && !isPreparation
  const processesAllowed = canUsePrepared || step.actionOn.processes.length > 0
  // A step output is a prepared intermediate, like a subprocess output, so it's offered wherever the
  // catalog lets the action act on subprocess outputs.
  const stepOutputsAllowed = actionAllowsProcesses(step.action) || step.actionOn.steps.length > 0
  const tabAllowed: Record<ActionOnTab, boolean> = { INGREDIENTS: ingredientsAllowed, PROCESSES: processesAllowed, STEPS: stepOutputsAllowed }
  const effectiveTab: ActionOnTab | null = tabAllowed[actionOnTab]
    ? actionOnTab
    : (['INGREDIENTS', 'PROCESSES', 'STEPS'] as const).find((tab) => tabAllowed[tab]) ?? null

  const ingredientIds = step.actionOn.ingredients.map((entry) => entry.ingredientId)
  const selectedReferences = step.actionOn.processes
  const selectedReferenceKeys = selectedReferences.map(preparedReferenceKey)
  const preparedOutputs = getPreparedOutputs(recipeProcesses)
  const outputOption = (output: PreparedOutput): SearchableSelectOption => ({
    value: preparedReferenceKey(output),
    label: output.label,
    category: `Flow ${output.flowNumber}`,
    description: output.isFlowOutput ? 'Flow output' : 'Intermediate result',
  })
  const referenceOfKey = (key: string): RecipeStepActionOnProcess | null => {
    const output = preparedOutputs.find((candidate) => preparedReferenceKey(candidate) === key)
    return output ? { processId: output.processId, stepId: output.stepId } : null
  }
  const selectedStepIds = step.actionOn.steps.map((entry) => entry.stepId)
  const availableStepOutputs = getAvailableStepOutputs(stepOutputGraph, node.id)
  const unselectedStepOutputs = availableStepOutputs.filter((source) => !selectedStepIds.includes(source.stepId))
  const stepsMissingOutput = getStepsMissingOutput(stepOutputGraph, node.id)

  // Every removal is confirmed first (the shared dialog is modal, so the step doesn't change meanwhile).
  const confirmRemoval = (title: string, message: string) =>
    confirm({ eyebrow: 'Remove from step', title, message, confirmLabel: 'Remove' })

  const addActionOnStep = (stepId: string) => {
    if (selectedStepIds.includes(stepId)) return
    updateActionOnSteps(node.id, [...selectedStepIds, stepId])
  }

  const removeActionOnStep = async (stepId: string) => {
    const label = getStepOutputLabel(stepOutputGraph, stepId)
    if (!(await confirmRemoval(
      label ? `Remove “${label}” from this step?` : 'Remove this step output from this step?',
      'This step will no longer use that earlier step\'s output. The earlier step itself is kept.',
    ))) return
    if (currentEdit?.kind === 'STEP') closeEdit()
    updateActionOnSteps(node.id, selectedStepIds.filter((id) => id !== stepId))
  }

  /** Swaps one step-output reference for another in place, keeping the list's order. */
  const replaceActionOnStep = (stepId: string, nextStepId: string) => {
    closeEdit()
    if (stepId === nextStepId || selectedStepIds.includes(nextStepId)) return
    updateActionOnSteps(node.id, selectedStepIds.map((id) => (id === stepId ? nextStepId : id)))
  }

  const addActionOnIngredient = (ingredient: ActionOnIngredient) => {
    updateActionOnIngredients(node.id, [...step.actionOn.ingredients, ingredient])
  }

  const removeActionOnIngredient = async (index: number) => {
    const entry = step.actionOn.ingredients[index]
    if (!entry) return
    if (!(await confirmRemoval(
      `Remove ${getActionOnIngredientDisplayName(entry) || 'this ingredient'} from this step?`,
      'Its quantity, unit, preparation style and notes for this step will be lost.',
    ))) return
    if (currentEdit?.kind === 'INGREDIENT') closeEdit()
    updateActionOnIngredients(node.id, step.actionOn.ingredients.filter((_, i) => i !== index))
  }

  const updateActionOnIngredientAt = (index: number, patch: Partial<ActionOnIngredient>) => {
    updateActionOnIngredients(node.id, step.actionOn.ingredients.map((entry, i) => (i === index ? { ...entry, ...patch } : entry)))
  }

  /** Saves the edit form's entry over the one at `index` (the whole entry: ingredient, amount, preparation, notes). */
  const replaceActionOnIngredientAt = (index: number, ingredient: ActionOnIngredient) => {
    closeEdit()
    updateActionOnIngredients(node.id, step.actionOn.ingredients.map((entry, i) => (i === index ? ingredient : entry)))
  }

  const addActionOnProcess = (key: string) => {
    const reference = referenceOfKey(key)
    if (!reference || selectedReferenceKeys.includes(key)) return
    updateActionOnProcesses(node.id, [...selectedReferences, reference])
  }

  const removeActionOnProcess = async (key: string) => {
    const reference = selectedReferences.find((entry) => preparedReferenceKey(entry) === key)
    if (!reference) return
    const { label } = describePreparedReference(recipeProcesses, reference)
    if (!(await confirmRemoval(
      `Remove “${label || 'this prepared component'}” from this step?`,
      'This step will no longer use it. The preparation itself is kept.',
    ))) return
    if (currentEdit?.kind === 'PROCESS') closeEdit()
    updateActionOnProcesses(node.id, selectedReferences.filter((entry) => preparedReferenceKey(entry) !== key))
  }

  /** Swaps one prepared component for another in place, keeping the list's order. */
  const replaceActionOnProcess = (key: string, nextKey: string) => {
    closeEdit()
    const next = referenceOfKey(nextKey)
    if (!next || key === nextKey || selectedReferenceKeys.includes(nextKey)) return
    updateActionOnProcesses(node.id, selectedReferences.map((entry) => (preparedReferenceKey(entry) === key ? next : entry)))
  }

  const unselectedOutputs = preparedOutputs.filter((output) => !selectedReferenceKeys.includes(preparedReferenceKey(output)))

  const editButton = (title: string, onClick: () => void) => (
    <button type="button" className="flow-action-on-entry-btn" onClick={onClick} title={title} aria-label={title}>
      ✏️
    </button>
  )
  const removeButton = (onClick: () => void) => (
    <button type="button" className="flow-action-on-entry-btn is-danger" onClick={onClick} title="Remove" aria-label="Remove">
      🗑
    </button>
  )
  const cancelEditButton = (
    <button
      type="button"
      onClick={closeEdit}
      style={{ flexShrink: 0, padding: '5px 10px', borderRadius: 7, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)', color: 'var(--flow-text-muted)', fontSize: 11, fontWeight: 700, cursor: 'pointer' }}
    >
      Cancel
    </button>
  )

  const renderAmountWithUnit = (
    key: string,
    label: string,
    valueField: 'durationValue' | 'repeatIntervalValue',
    unitField: 'durationUnit' | 'repeatIntervalUnit',
    placeholder: string,
  ) => (
    <div key={key} className="flow-properties-field">
      <label className="flow-properties-label">{label}</label>
      <div className="flow-properties-actions" style={{ marginTop: 0, paddingTop: 0, borderTop: 'none' }}>
        <input
          className="flow-properties-input"
          inputMode="decimal"
          value={step[valueField]}
          onChange={(e) => updateStepField(node.id, valueField, e.target.value)}
          placeholder={placeholder}
        />
        <select
          className="flow-properties-input"
          value={step[unitField]}
          onChange={(e) => updateStepField(node.id, unitField, e.target.value)}
        >
          <option value="">Unit</option>
          {DURATION_UNIT_OPTIONS.map((option) => (
            <option key={option} value={option}>{option}</option>
          ))}
        </select>
      </div>
    </div>
  )

  const renderExtraField = (field: StepSchemaFieldConfig) => {
    const { key } = field
    const label = `${field.label}${requirementHint(field)}`
    switch (key) {
      case 'flameLevelId':
        return (
          <div key={key} className="flow-properties-field">
            <label className="flow-properties-label">{label}</label>
            <SearchableSelect
              value={step.flameLevelId}
              onChange={(value) => updateStepField(node.id, 'flameLevelId', value)}
              options={flameLevelOptions}
              placeholder="Select Heat Level"
            />
            {step.flameLevelId === CUSTOM_FLAME_LEVEL_ID && (
              <input
                className="flow-properties-input"
                style={{ marginTop: 6 }}
                value={step.customFlameLevel}
                onChange={(e) => updateStepField(node.id, 'customFlameLevel', e.target.value)}
                placeholder="Enter custom heat level"
              />
            )}
          </div>
        )
      case 'temperature':
        return (
          <div key={key} className="flow-properties-field">
            <label className="flow-properties-label">{label}</label>
            <div className="flow-properties-actions" style={{ marginTop: 0, paddingTop: 0, borderTop: 'none' }}>
              <input
                className="flow-properties-input"
                inputMode="decimal"
                value={step.temperatureValue}
                onChange={(e) => updateStepField(node.id, 'temperatureValue', e.target.value)}
                placeholder="180"
              />
              <select
                className="flow-properties-input"
                value={step.temperatureUnit}
                onChange={(e) => updateStepField(node.id, 'temperatureUnit', e.target.value)}
              >
                <option value="">Unit</option>
                {TEMPERATURE_UNIT_OPTIONS.map((option) => (
                  <option key={option.id} value={option.id}>{option.label}</option>
                ))}
              </select>
            </div>
          </div>
        )
      case 'duration':
        return renderAmountWithUnit(key, label, 'durationValue', 'durationUnit', '5')
      case 'repeatInterval':
        return renderAmountWithUnit(key, label, 'repeatIntervalValue', 'repeatIntervalUnit', 'every 2')
      default:
        return null
    }
  }

  return (
    <div className={`flow-properties-panel${variant === 'popup' ? ' flow-step-popup-panel' : ''}`}>
      <div className="flow-properties-header">
        <div className="flow-properties-header-content">
          <span className="flow-properties-icon">🍳</span>
          <div className="flow-properties-header-info">
            <div className="flow-properties-header-title">{title}</div>
            <span className="flow-properties-type-badge" style={{ background: '#f0fdf4', border: '1px solid #86efac', color: '#16a34a' }}>
              Step
            </span>
          </div>
          {variant === 'popup' && onClose && (
            <button type="button" className="flow-step-popup-close" onClick={onClose} aria-label="Close the step editor" title="Close (Esc)">
              ✕
            </button>
          )}
        </div>
      </div>

      <div className="flow-properties-content">
        <div className="flow-editor-section-heading">Action</div>
        <div className="flow-properties-field">
          <label className="flow-properties-label">Action *</label>
          <SearchableSelect
            value={step.action}
            onChange={(value) => updateStepField(node.id, 'action', value)}
            options={actionOptions}
            placeholder="Search actions (e.g. sauté, temper, knead)"
          />
          {actionDefinition && actionDefinition.id !== CUSTOM_ACTION_ID && (
            <div style={{ marginTop: 4, fontSize: 11, color: 'var(--flow-text-subtle)', lineHeight: 1.35 }}>{actionDefinition.description}</div>
          )}
        </div>
        {step.action === CUSTOM_ACTION_ID && (
          <div className="flow-properties-field">
            <label className="flow-properties-label">Custom Action Name</label>
            <input
              className="flow-properties-input"
              value={step.customActionName}
              onChange={(e) => updateStepField(node.id, 'customActionName', e.target.value)}
              placeholder="Enter action name"
            />
          </div>
        )}

        <div className="flow-editor-section-heading">Action Description *</div>
        <div className="flow-properties-field">
          <StepDescriptionField
            nodeId={node.id}
            step={step}
            recipeProcesses={recipeProcesses}
            isPreparation={isPreparation}
            stepOutputGraph={stepOutputGraph}
            updateStepData={updateStepData}
            updateActionOnIngredients={updateActionOnIngredients}
            onOpenSubprocess={onOpenSubprocess}
            autoFocus={autoFocusDescription}
          />
        </div>

        {issues.length > 0 && (
          <div className="flow-step-issues" role="status">
            {issues.map((issue, index) => (
              <div key={index} className={`flow-step-issue is-${issue.severity.toLowerCase()}`}>
                {issue.severity === 'BLOCKER' ? '⛔' : '⚠️'} {issue.message}
              </div>
            ))}
          </div>
        )}

        <div className="mt-2 h-px" style={{ background: 'var(--flow-border)' }} />
        <div className="flow-editor-section-heading">Used in this step</div>
        {effectiveTab === null ? (
          <div style={{ fontSize: 12, color: 'var(--flow-text-subtle)', marginBottom: 10 }}>
            {actionDefinition?.displayName ?? 'This action'} has no Action On target — it acts on equipment or time.
          </div>
        ) : (
          <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
            {([
              ['INGREDIENTS', ingredientsAllowed, `🥕 Ingredients (${ingredientIds.length})`],
              ['PROCESSES', processesAllowed, `🔪 Prepared (${selectedReferences.length})`],
              ['STEPS', stepOutputsAllowed, `↳ Step Outputs (${selectedStepIds.length})`],
            ] as const).filter(([, allowed]) => allowed).map(([tab, , label]) => (
              <button
                key={tab}
                type="button"
                onClick={() => setActionOnTab(tab)}
                style={{
                  flex: 1, padding: '6px 8px', borderRadius: 8, fontSize: 11.5, fontWeight: 700, cursor: 'pointer',
                  border: `1px solid ${effectiveTab === tab ? 'var(--flow-accent)' : 'var(--flow-border)'}`,
                  background: effectiveTab === tab ? 'var(--flow-accent)' : 'var(--flow-surface)',
                  color: effectiveTab === tab ? 'white' : 'var(--flow-text-muted)',
                }}
              >
                {label}
              </button>
            ))}
          </div>
        )}

        {effectiveTab === 'INGREDIENTS' && (
          <div className="flex flex-col gap-2">
            {step.actionOn.ingredients.length === 0 ? (
              <div style={{ fontSize: 12, color: 'var(--flow-text-subtle)' }}>No ingredients on this step yet.</div>
            ) : (
              step.actionOn.ingredients.map((entry, index) => {
                const catalogEntry = getIngredientById(entry.ingredientId)
                const quantifiable = isQuantifiableUnit(entry.unit)
                if (currentEdit?.kind === 'INGREDIENT' && currentEdit.index === index) {
                  // The add form, filled in with this entry: changes which ingredient it is (or its
                  // custom name) and its notes too, not only the amount/preparation editable on the row.
                  return (
                    <IngredientSelector
                      key={`edit-${entry.ingredientId}-${index}`}
                      excludeIngredientIds={ingredientIds.filter((_, i) => i !== index)}
                      action={step.action}
                      editing={entry}
                      onAdd={(ingredient) => replaceActionOnIngredientAt(index, ingredient)}
                      onCancel={closeEdit}
                    />
                  )
                }
                return (
                  <div
                    key={`${entry.ingredientId}-${index}`}
                    className="flex flex-wrap items-center gap-2 rounded-lg border p-2"
                    style={{ border: '1px solid var(--flow-border)', background: 'var(--flow-surface-muted)' }}
                  >
                    <div style={{ minWidth: 80, fontWeight: 700, fontSize: 12, color: 'var(--flow-text)' }}>
                      {catalogEntry.icon} {getActionOnIngredientDisplayName(entry)}
                      {entry.notes && (
                        <div style={{ fontSize: 10.5, fontWeight: 500, color: 'var(--flow-text-subtle)' }}>{entry.notes}</div>
                      )}
                    </div>
                    {quantityRelevant && (
                      <>
                        <input
                          className="flow-properties-input"
                          style={{ width: 64 }}
                          inputMode="decimal"
                          disabled={!quantifiable}
                          value={quantifiable && entry.quantity != null ? String(entry.quantity) : ''}
                          placeholder={quantifiable ? 'qty' : '—'}
                          onChange={(e) => {
                            const text = e.target.value.trim()
                            const parsed = Number(text)
                            updateActionOnIngredientAt(index, { quantity: text && Number.isFinite(parsed) ? parsed : null })
                          }}
                        />
                        <UnitSelect
                          ingredientId={entry.ingredientId}
                          value={entry.unit}
                          style={{ width: 110 }}
                          onChange={(unit) => updateActionOnIngredientAt(index, isQuantifiableUnit(unit) ? { unit } : { unit, quantity: null })}
                        />
                        {entry.unit === CUSTOM_UNIT_ID && (
                          <input
                            className="flow-properties-input"
                            style={{ width: 96 }}
                            value={entry.customUnit ?? ''}
                            placeholder="unit name"
                            aria-label="Custom unit name"
                            onChange={(e) => updateActionOnIngredientAt(index, { customUnit: e.target.value })}
                          />
                        )}
                      </>
                    )}
                    <PreparationStyleSelect
                      action={step.action}
                      ingredientId={entry.ingredientId}
                      value={entry.preparationStyleId ?? ''}
                      customValue={entry.customPreparationStyle ?? ''}
                      onChange={(preparationStyleId, customPreparationStyle) =>
                        updateActionOnIngredientAt(index, { preparationStyleId, customPreparationStyle: customPreparationStyle || undefined })}
                    />
                    <div style={{ display: 'flex', alignItems: 'center', gap: 2, marginLeft: 'auto' }}>
                      {actionAllowsIngredients(step.action) && editButton('Edit ingredient', () => setActionOnEdit({ nodeId: node.id, kind: 'INGREDIENT', index }))}
                      {removeButton(() => void removeActionOnIngredient(index))}
                    </div>
                  </div>
                )
              })
            )}
            {actionAllowsIngredients(step.action) ? (
              <IngredientSelector excludeIngredientIds={ingredientIds} action={step.action} onAdd={addActionOnIngredient} draftKey={draftKeys.stepIngredient(node.id)} />
            ) : (
              <div style={{ fontSize: 11, color: 'var(--flow-text-muted)', background: 'var(--flow-warning-soft)', border: '1px solid var(--flow-warning-border)', borderRadius: 8, padding: '6px 8px' }}>
                {actionDefinition?.displayName ?? 'This action'} doesn't act on ingredients — remove these, or choose another action.
              </div>
            )}
          </div>
        )}
        {effectiveTab === 'PROCESSES' && (
          <div className="flex flex-col gap-2">
            {step.actionOn.processes.length === 0 ? (
              <div style={{ fontSize: 12, color: 'var(--flow-text-subtle)' }}>This step uses no prepared components yet.</div>
            ) : (
              selectedReferences.map((entry) => {
                const key = preparedReferenceKey(entry)
                const { label, problem } = describePreparedReference(recipeProcesses, entry)
                if (currentEdit?.kind === 'PROCESS' && currentEdit.key === key) {
                  // Same choices as adding (outputs not already used), plus this entry's own.
                  const choices = preparedOutputs.filter((output) => preparedReferenceKey(output) === key || !selectedReferenceKeys.includes(preparedReferenceKey(output)))
                  return (
                    <div
                      key={key}
                      className="flex items-center gap-2 rounded-lg border p-2"
                      style={{ border: '1px solid var(--flow-accent)', background: 'var(--flow-surface)' }}
                    >
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <SearchableSelect
                          value={problem ? '' : key}
                          onChange={(value) => value && replaceActionOnProcess(key, value)}
                          options={choices.map(outputOption)}
                          placeholder="Switch to another prepared component"
                          emptyLabel="No matching prepared components"
                        />
                      </div>
                      {cancelEditButton}
                    </div>
                  )
                }
                return (
                  <div
                    key={key}
                    className="flex items-center justify-between gap-2 rounded-lg border p-2"
                    style={problem
                      ? { border: '1px solid var(--flow-danger-border)', background: 'var(--flow-danger-soft)' }
                      : { border: '1px solid #c7d2fe', background: '#eef2ff' }}
                  >
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 12, fontWeight: 700, color: problem ? 'var(--flow-danger)' : '#312e81', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        🔪 {problem ? PREPARED_REFERENCE_PROBLEM_LABELS[problem] : label}
                      </div>
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 2, flexShrink: 0 }}>
                      {!problem && onOpenSubprocess && (
                        <button
                          type="button"
                          onClick={() => onOpenSubprocess(entry.processId, entry.stepId)}
                          title="Open the step in Preparation that makes this"
                          style={{ border: 'none', background: 'transparent', cursor: 'pointer', fontSize: 11, fontWeight: 700, color: '#4338ca', marginRight: 4 }}
                        >
                          Open →
                        </button>
                      )}
                      {canUsePrepared && editButton('Switch to another prepared component', () => setActionOnEdit({ nodeId: node.id, kind: 'PROCESS', key }))}
                      {removeButton(() => void removeActionOnProcess(key))}
                    </div>
                  </div>
                )
              })
            )}

            {isPreparation && step.actionOn.processes.length > 0 && (
              <div style={{ fontSize: 11, color: 'var(--flow-text-muted)', background: 'var(--flow-warning-soft)', border: '1px solid var(--flow-warning-border)', borderRadius: 8, padding: '6px 8px' }}>
                Preparation flows are independent: only main steps use prepared components. Connect these steps into one flow instead, or remove these.
              </div>
            )}
            {!isPreparation && !actionAllowsProcesses(step.action) && (
              <div style={{ fontSize: 11, color: 'var(--flow-text-muted)', background: 'var(--flow-warning-soft)', border: '1px solid var(--flow-warning-border)', borderRadius: 8, padding: '6px 8px' }}>
                {actionDefinition?.displayName ?? 'This action'} doesn't act on prepared components — remove these, or choose another action.
              </div>
            )}
            {canUsePrepared && (
              <div className="flex items-end gap-2">
                <div style={{ flex: 1 }}>
                  <SearchableSelect
                    value=""
                    onChange={(value) => value && addActionOnProcess(value)}
                    options={unselectedOutputs.map(outputOption)}
                    placeholder={unselectedOutputs.length ? 'Use a prepared component' : 'Nothing more prepared yet'}
                    emptyLabel="No matching prepared components"
                  />
                </div>
              </div>
            )}
            <div
              style={{ fontSize: 10.5, color: 'var(--flow-text-muted)', background: 'var(--flow-warning-soft)', border: '1px solid var(--flow-warning-border)', borderRadius: 8, padding: '6px 8px' }}
            >
              Prepared components are what the Preparation's steps make — each flow's last step names its output (its Expected Output). Rename one there.
            </div>
          </div>
        )}
        {effectiveTab === 'STEPS' && (
          <div className="flex flex-col gap-2">
            {step.actionOn.steps.length === 0 ? (
              <div style={{ fontSize: 12, color: 'var(--flow-text-subtle)' }}>No earlier step outputs used by this step yet.</div>
            ) : (
              step.actionOn.steps.map((entry) => {
                // Always the referenced step's *current* Expected Output — edits there show up here.
                const label = getStepOutputLabel(stepOutputGraph, entry.stepId)
                const problem = getStepOutputReferenceProblem(stepOutputGraph, entry.stepId, node.id)
                if (currentEdit?.kind === 'STEP' && currentEdit.stepId === entry.stepId) {
                  // Same choices as adding (earlier steps with an Expected Output, not already used), plus this
                  // entry's own while it's still valid — so an unavailable reference can be repointed too.
                  const choices = availableStepOutputs.filter((source) => source.stepId === entry.stepId || !selectedStepIds.includes(source.stepId))
                  return (
                    <div
                      key={entry.stepId}
                      className="flex items-center gap-2 rounded-lg border p-2"
                      style={{ border: '1px solid var(--flow-accent)', background: 'var(--flow-surface)' }}
                    >
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <SearchableSelect
                          value={problem ? '' : entry.stepId}
                          onChange={(value) => value && replaceActionOnStep(entry.stepId, value)}
                          options={choices.map((source) => ({
                            value: source.stepId,
                            label: source.label,
                            category: 'Step Outputs',
                            description: `From step ${source.stepNumber}`,
                          }))}
                          placeholder={choices.length ? 'Switch to another earlier step output' : 'No earlier step outputs available'}
                          emptyLabel="No matching step outputs"
                        />
                      </div>
                      {cancelEditButton}
                    </div>
                  )
                }
                return (
                  <div
                    key={entry.stepId}
                    className="flex items-center justify-between gap-2 rounded-lg border p-2"
                    style={{ border: `1px solid ${problem ? '#fecaca' : '#bbf7d0'}`, background: problem ? '#fef2f2' : '#f0fdf4' }}
                  >
                    <div style={{ minWidth: 0 }}>
                      <div style={{ fontSize: 12, fontWeight: 700, color: problem ? '#991b1b' : '#166534', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                        ↳ {label || 'Unavailable step output'}
                      </div>
                      {problem && (
                        <div style={{ fontSize: 10.5, color: '#b91c1c' }}>Unavailable — {STEP_OUTPUT_PROBLEM_LABELS[problem]}. Remove it or reconnect the steps before saving.</div>
                      )}
                    </div>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 2, flexShrink: 0 }}>
                      {actionAllowsProcesses(step.action) && editButton('Switch to another step output', () => setActionOnEdit({ nodeId: node.id, kind: 'STEP', stepId: entry.stepId }))}
                      {removeButton(() => void removeActionOnStep(entry.stepId))}
                    </div>
                  </div>
                )
              })
            )}

            {actionAllowsProcesses(step.action) ? (
              <SearchableSelect
                value=""
                onChange={(value) => value && addActionOnStep(value)}
                options={unselectedStepOutputs.map((source) => ({
                  value: source.stepId,
                  label: source.label,
                  category: 'Step Outputs',
                  description: `From step ${source.stepNumber}`,
                }))}
                placeholder={unselectedStepOutputs.length ? 'Add an earlier step output' : 'No earlier step outputs available'}
                emptyLabel="No matching step outputs"
              />
            ) : (
              <div style={{ fontSize: 11, color: 'var(--flow-text-muted)', background: 'var(--flow-warning-soft)', border: '1px solid var(--flow-warning-border)', borderRadius: 8, padding: '6px 8px' }}>
                {actionDefinition?.displayName ?? 'This action'} doesn't act on prepared outputs — remove these, or choose another action.
              </div>
            )}
            <div style={{ fontSize: 10.5, color: 'var(--flow-text-muted)', background: 'var(--flow-surface-muted)', border: '1px solid var(--flow-border)', borderRadius: 8, padding: '6px 8px' }}>
              Every earlier step with an Expected Output is listed (by its connections, or its order while unconnected).
              {stepsMissingOutput.length > 0 && (
                <> Add an Expected Output to step{stepsMissingOutput.length > 1 ? 's' : ''} {stepsMissingOutput.map((source) => source.stepNumber).join(', ')} to use {stepsMissingOutput.length > 1 ? 'them' : 'it'} here.</>
              )}
            </div>
          </div>
        )}

        <div className="mt-2 h-px" style={{ background: 'var(--flow-border)' }} />
        <div className="flow-editor-section-heading">Expected Output *</div>
        <div className="flow-properties-field">
          <textarea
            className="flow-properties-textarea"
            rows={3}
            value={step.expectedOutput}
            onChange={(e) => updateStepField(node.id, 'expectedOutput', e.target.value)}
            placeholder="Describe the expected result after this step"
          />
        </div>

        {extraFields.length > 0 && (
          <>
            <div className="mt-2 h-px" style={{ background: 'var(--flow-border)' }} />
            <button
              type="button"
              onClick={() => setAdvancedOpen((value) => !value)}
              className="flow-editor-section-heading"
              style={{ display: 'flex', alignItems: 'center', gap: 6, border: 'none', background: 'transparent', cursor: 'pointer', padding: 0, width: '100%', textAlign: 'left' }}
            >
              <span>{advancedOpen ? '▾' : '▸'}</span> Advanced Options
            </button>
            {advancedOpen && extraFields.map(renderExtraField)}
          </>
        )}

        {onGenerateVisuals && (
          <>
            <div className="mt-2 h-px" style={{ background: 'var(--flow-border)' }} />
            <div className="flow-editor-section-heading">Visualization</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
              {visualization?.imageUrl && (
                <div style={{ width: '100%', aspectRatio: '16 / 10', borderRadius: 10, overflow: 'hidden', border: '1px solid var(--flow-border)', background: '#0f172a' }}>
                  <ZoomableImage
                    src={visualization.imageUrl}
                    alt="Generated visualization"
                    caption="Generated visualization"
                    style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }}
                  />
                </div>
              )}
              <button
                type="button"
                onClick={onGenerateVisuals}
                disabled={isGeneratingVisuals}
                style={{
                  alignSelf: 'flex-start', padding: '6px 12px', borderRadius: 7,
                  border: '1px solid var(--flow-magic-border)', background: 'var(--flow-magic-soft)', color: 'var(--flow-magic)',
                  fontSize: 11.5, fontWeight: 700, cursor: isGeneratingVisuals ? 'wait' : 'pointer', opacity: isGeneratingVisuals ? 0.7 : 1,
                }}
              >
                {isGeneratingVisuals ? (visualsProgressLabel ?? 'Generating…') : visualization?.imageUrl ? '🔄 Regenerate visuals' : '🖼️ Generate visuals'}
              </button>
              <div style={{ fontSize: 10, color: 'var(--flow-text-subtle)' }}>
                {isPreparation
                  ? 'Generates one image per preparation flow (a storyboard of its steps), shown on the step that makes its output.'
                  : 'Generates an image for every step of this process — not only this one.'}
              </div>
            </div>
          </>
        )}

        <div className="my-2 h-px" style={{ background: 'var(--flow-border)' }} />
        <div className="flow-editor-section-heading">Actions</div>
        <div className="flow-properties-actions">
          <button className="flow-properties-action-btn flow-properties-duplicate-btn" onClick={() => onDuplicateNode?.(node.id)}>
            📋 Duplicate
          </button>
          <button className="flow-properties-action-btn flow-properties-delete-btn" onClick={() => onDeleteNode?.(node.id)}>
            🗑 Delete
          </button>
        </div>
      </div>
      {variant === 'popup' && onSaveAndClose && (
        <div className="flow-step-popup-footer">
          <span className="flow-step-popup-hint">Changes apply as you type · click outside to close</span>
          <button type="button" className="flow-step-popup-save" onClick={onSaveAndClose}>Save &amp; close</button>
        </div>
      )}
    </div>
  )
}
