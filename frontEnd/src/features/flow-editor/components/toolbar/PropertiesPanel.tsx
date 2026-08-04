import './PropertiesPanel.css'
import '../../styles/flow-editor.css'
import {
  ACTIONS_BY_CATEGORY,
  ACTION_CATEGORY_ORDER,
  getActionDisplayName,
} from '../../catalog/actionCatalog'
import {
  CUSTOM_INGREDIENT_ID,
  INGREDIENTS_BY_CATEGORY,
  INGREDIENT_CATEGORY_ORDER,
  getIngredientById,
} from '../../catalog/ingredientCatalog'
import {
  DURATION_UNIT_OPTIONS,
  REPEAT_INTERVAL_UNIT_OPTIONS,
  buildRepeatIntervalLabel,
} from '../../catalog/stepFieldCatalog'
import {
  CUSTOM_UNIT_ID,
  UNITS_BY_CATEGORY,
  UNIT_CATEGORY_ORDER,
} from '../../catalog/unitCatalog'
import {
  CUSTOM_PREPARATION_STYLE_ID,
  PREPARATION_STYLE_CATALOG,
} from '../../catalog/preparationStyleCatalog'
import {
  CUSTOM_FLAME_LEVEL_ID,
  FLAME_LEVEL_CATALOG,
} from '../../catalog/flameLevelCatalog'
import {
  normalizeParallelNodeData,
  type ParallelNodeStructuredFields,
  normalizeConditionNodeData,
  normalizeStepNodeData,
  type ConditionNodeStructuredFields,
  type StepNodeStructuredFields,
} from '../../../../types/recipeFlow'
import {
  type StepContextFieldConfig,
} from '../../catalog/stepActionPresentation'
import {
  getStepActionSchema,
  isStepFieldEnabled,
} from '../../catalog/actionSchemaCatalog'
import SearchableSelect, { type SearchableSelectOption } from '../../../../shared/components/SearchableSelect'

type NodeData = {
  id: string
  type?: string
  data: {
    title?: string
    description?: string
    icon?: string
    yesLabel?: string
    noLabel?: string
    step?: StepNodeStructuredFields
    condition?: ConditionNodeStructuredFields
    parallel?: ParallelNodeStructuredFields
  }
}

type Props = {
  node?: NodeData
  updateNodeField: (nodeId: string, field: string, value: string) => void
  onDeleteNode?: (nodeId: string) => void
  onDuplicateNode?: (nodeId: string) => void
}

export default function PropertiesPanel({ node, updateNodeField, onDeleteNode, onDuplicateNode }: Props) {
  if (!node) return (
    <div className="flow-properties-empty">
      <h2 className="flow-properties-title">Properties</h2>
      <div className="flow-properties-empty-box">
        <div className="flow-properties-empty-icon">👆</div>
        Select a node to edit
      </div>
    </div>
  )

  const d = node.data
  const isCondition = node.type === 'conditionNode'
  const isParallel = node.type === 'parallelStartNode' || node.type === 'parallelEndNode'
  const stepData = !isCondition && !isParallel ? normalizeStepNodeData(d).step : undefined
  const conditionData = isCondition ? normalizeConditionNodeData(d).condition : undefined
  const parallelData = isParallel
    ? normalizeParallelNodeData(d, node.type === 'parallelEndNode' ? 'end' : 'start').parallel
    : undefined
  const selectedIngredientMeta = (() => {
    if (!stepData?.ingredientId || stepData.ingredientId === CUSTOM_INGREDIENT_ID) return null
    return getIngredientById(stepData.ingredientId)
  })()
  const repeatActionLabel = stepData?.repeatAction ? getActionDisplayName(stepData.repeatAction) : ''
  const repeatExpression = stepData
    ? buildRepeatIntervalLabel(repeatActionLabel, stepData.repeatEveryValue, stepData.repeatEveryUnit)
    : ''
  const actionSchema = stepData ? getStepActionSchema(stepData.action) : null

  const actionOptions: SearchableSelectOption[] = ACTION_CATEGORY_ORDER.flatMap((category) =>
    ACTIONS_BY_CATEGORY[category].map((action) => ({
      value: action.id,
      label: action.displayName,
      icon: action.icon,
      category,
    }))
  )

  const ingredientOptions: SearchableSelectOption[] = INGREDIENT_CATEGORY_ORDER.flatMap((category) =>
    INGREDIENTS_BY_CATEGORY[category].map((ingredient) => ({
      value: ingredient.id,
      label: ingredient.name,
      icon: ingredient.icon,
      category,
    }))
  )

  const unitOptions: SearchableSelectOption[] = UNIT_CATEGORY_ORDER.flatMap((category) =>
    UNITS_BY_CATEGORY[category].map((unit) => ({
      value: unit.id,
      label: unit.label,
      category,
    }))
  )

  const preparationStyleOptions: SearchableSelectOption[] = PREPARATION_STYLE_CATALOG.map((style) => ({
    value: style.id,
    label: style.label,
  }))

  const flameLevelOptions: SearchableSelectOption[] = FLAME_LEVEL_CATALOG.map((flameLevel) => ({
    value: flameLevel.id,
    label: flameLevel.label,
  }))

  const renderActionField = (field: StepContextFieldConfig) => {
    if (!stepData) return null

    switch (field.key) {
      case 'ingredientId':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">Ingredient</label>
            <SearchableSelect
              value={stepData.ingredientId}
              onChange={(nextIngredientId) => {
                updateNodeField(node.id, 'step.ingredientId', nextIngredientId)
                if (nextIngredientId !== CUSTOM_INGREDIENT_ID) {
                  updateNodeField(node.id, 'step.customIngredientName', '')
                }
              }}
              options={ingredientOptions}
              placeholder="Select Ingredient"
            />
          </div>
        )
      case 'quantity':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">{actionSchema?.amountLabel ?? 'Quantity'}</label>
            <input
              className="flow-properties-input"
              value={stepData.quantity}
              onChange={e => updateNodeField(node.id, 'step.quantity', e.target.value)}
              placeholder="2"
            />
          </div>
        )
      case 'unitId':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">{actionSchema?.unitLabel ?? 'Unit'}</label>
            <SearchableSelect
              value={stepData.unitId}
              onChange={(nextUnitId) => updateNodeField(node.id, 'step.unitId', nextUnitId)}
              options={unitOptions}
              placeholder="Select Unit"
            />
          </div>
        )
      case 'preparationStyleId':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">{field.label}</label>
            <SearchableSelect
              value={stepData.preparationStyleId}
              onChange={(nextValue) => updateNodeField(node.id, 'step.preparationStyleId', nextValue)}
              options={preparationStyleOptions}
              placeholder="Select Preparation Style"
            />
          </div>
        )
      case 'temperature':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">{field.label}</label>
            <input
              className="flow-properties-input"
              value={stepData.temperature}
              onChange={e => updateNodeField(node.id, 'step.temperature', e.target.value)}
              placeholder="180 C"
            />
          </div>
        )
      case 'flameLevelId':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">{field.label}</label>
            <SearchableSelect
              value={stepData.flameLevelId}
              onChange={(nextValue) => updateNodeField(node.id, 'step.flameLevelId', nextValue)}
              options={flameLevelOptions}
              placeholder="Select Flame Level"
            />
          </div>
        )
      case 'duration':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">{field.label}</label>
            <div className="flow-properties-actions" style={{ marginTop: 0, paddingTop: 0, borderTop: 'none' }}>
              <input
                className="flow-properties-input"
                value={stepData.durationValue}
                onChange={e => updateNodeField(node.id, 'step.durationValue', e.target.value)}
                placeholder="5"
              />
              <select
                className="flow-properties-input"
                value={stepData.durationUnit}
                onChange={e => updateNodeField(node.id, 'step.durationUnit', e.target.value)}
              >
                <option value="">Unit</option>
                {DURATION_UNIT_OPTIONS.map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
            </div>
          </div>
        )
      case 'repeatInterval':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">{field.label}</label>
            <div className="flow-properties-actions" style={{ marginTop: 0, paddingTop: 0, borderTop: 'none', marginBottom: 8 }}>
              <SearchableSelect
                value={stepData.repeatAction}
                onChange={(nextAction) => updateNodeField(node.id, 'step.repeatAction', nextAction)}
                options={actionOptions}
                placeholder="Action"
              />
              <input
                className="flow-properties-input"
                value={stepData.repeatEveryValue}
                onChange={e => updateNodeField(node.id, 'step.repeatEveryValue', e.target.value)}
                placeholder="2"
              />
              <select
                className="flow-properties-input"
                value={stepData.repeatEveryUnit}
                onChange={e => updateNodeField(node.id, 'step.repeatEveryUnit', e.target.value)}
              >
                <option value="">Unit</option>
                {REPEAT_INTERVAL_UNIT_OPTIONS.map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
            </div>
            <input
              className="flow-properties-readonly"
              value={repeatExpression || 'Example: Stir every 2 minutes'}
              readOnly
            />
          </div>
        )
      case 'notes':
        return (
          <div key={field.key} className="flow-properties-field">
            <label className="flow-properties-label">Notes</label>
            <textarea
              className="flow-properties-textarea"
              rows={3}
              value={stepData.notes}
              onChange={e => updateNodeField(node.id, 'step.notes', e.target.value)}
              placeholder="Any additional instructions"
            />
          </div>
        )
      default:
        return null
    }
  }

  const typeIcon  = isCondition ? '🔀' : isParallel ? '⎇' : d.icon || '🍳'
  const typeLabel = isCondition ? 'Condition' : isParallel ? 'Parallel' : 'Step'
  const typeBg    = isCondition ? '#fffbeb' : isParallel ? '#f5f3ff' : '#f0fdf4'
  const typeBorder= isCondition ? '#fcd34d' : isParallel ? '#c4b5fd' : '#86efac'
  const typeColor = isCondition ? '#d97706' : isParallel ? '#6d28d9' : '#16a34a'

  return (
    <div className="flow-properties-panel">
      {/* Header */}
      <div className="flow-properties-header">
        <div className="flow-properties-header-content">
          <span className="flow-properties-icon">{typeIcon}</span>
          <div className="flow-properties-header-info">
            <div className="flow-properties-header-title">{d.title || 'Untitled'}</div>
            <span
              className="flow-properties-type-badge"
              style={{ background: typeBg, border: `1px solid ${typeBorder}`, color: typeColor }}
            >
              {typeLabel}
            </span>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flow-properties-content">
        <div className="flow-editor-section-heading">General</div>

        {!isCondition && !isParallel && (
          <>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Action *</label>
              <SearchableSelect
                value={stepData?.action ?? ''}
                onChange={(nextValue) => updateNodeField(node.id, 'step.action', nextValue)}
                options={actionOptions}
                placeholder="Select Action"
              />
            </div>

            {actionSchema && stepData && (
              <>
                <div className="flow-editor-section-heading">{actionSchema.category}</div>
                {actionSchema.fields.map(renderActionField)}

                {isStepFieldEnabled(stepData.action, 'ingredientId') && stepData.ingredientId === CUSTOM_INGREDIENT_ID && (
                  <div className="flow-properties-field">
                    <label className="flow-properties-label">Custom Ingredient</label>
                    <input
                      className="flow-properties-input"
                      value={stepData.customIngredientName}
                      onChange={e => updateNodeField(node.id, 'step.customIngredientName', e.target.value)}
                      placeholder="Enter ingredient name"
                    />
                  </div>
                )}

                {isStepFieldEnabled(stepData.action, 'unitId') && stepData.unitId === CUSTOM_UNIT_ID && (
                  <div className="flow-properties-field">
                    <label className="flow-properties-label">Custom Unit</label>
                    <input
                      className="flow-properties-input"
                      value={stepData.customUnit}
                      onChange={e => updateNodeField(node.id, 'step.customUnit', e.target.value)}
                      placeholder="Enter custom unit"
                    />
                  </div>
                )}

                {isStepFieldEnabled(stepData.action, 'preparationStyleId') && stepData.preparationStyleId === CUSTOM_PREPARATION_STYLE_ID && (
                  <div className="flow-properties-field">
                    <label className="flow-properties-label">Custom Preparation Style</label>
                    <input
                      className="flow-properties-input"
                      value={stepData.customPreparationStyle}
                      onChange={e => updateNodeField(node.id, 'step.customPreparationStyle', e.target.value)}
                      placeholder="Enter custom preparation style"
                    />
                  </div>
                )}

                {isStepFieldEnabled(stepData.action, 'flameLevelId') && stepData.flameLevelId === CUSTOM_FLAME_LEVEL_ID && (
                  <div className="flow-properties-field">
                    <label className="flow-properties-label">Custom Flame Level</label>
                    <input
                      className="flow-properties-input"
                      value={stepData.customFlameLevel}
                      onChange={e => updateNodeField(node.id, 'step.customFlameLevel', e.target.value)}
                      placeholder="Enter custom flame level"
                    />
                  </div>
                )}

                {isStepFieldEnabled(stepData.action, 'ingredientId') && (
                  <div className="flow-properties-field">
                    <label className="flow-properties-label">Catalog Details</label>
                    <input
                      className="flow-properties-readonly"
                      value={
                        selectedIngredientMeta
                          ? `${selectedIngredientMeta.category} | Default unit: ${selectedIngredientMeta.defaultUnit}`
                          : (stepData.ingredientId === CUSTOM_INGREDIENT_ID ? 'Custom ingredient' : 'Not selected')
                      }
                      readOnly
                    />
                  </div>
                )}
              </>
            )}
          </>
        )}

        {isParallel && (
          <>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Parallel Type</label>
              <input
                className="flow-properties-readonly"
                value={parallelData?.kind === 'end' ? 'Parallel End' : 'Parallel Start'}
                readOnly
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Label</label>
              <input
                className="flow-properties-input"
                value={parallelData?.label ?? ''}
                onChange={e => updateNodeField(node.id, 'parallel.label', e.target.value)}
                placeholder={parallelData?.kind === 'end' ? 'Parallel End' : 'Parallel Start'}
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Notes</label>
              <textarea
                className="flow-properties-textarea"
                rows={3}
                value={parallelData?.notes ?? ''}
                onChange={e => updateNodeField(node.id, 'parallel.notes', e.target.value)}
                placeholder="Optional context"
              />
            </div>
          </>
        )}

        {/* Condition-only fields */}
        {isCondition && (
          <>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Question</label>
              <input
                className="flow-properties-input"
                value={conditionData?.question ?? ''}
                onChange={e => updateNodeField(node.id, 'condition.question', e.target.value)}
                placeholder="Is Water Boiling?"
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Expected Result</label>
              <select
                className="flow-properties-input"
                value={conditionData?.expectedResult ?? 'success'}
                onChange={e => updateNodeField(node.id, 'condition.expectedResult', e.target.value)}
              >
                <option value="success">Success</option>
                <option value="failure">Failure</option>
              </select>
            </div>

            <div className="flow-editor-section-heading">Branch Labels</div>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Success Label</label>
              <input 
                className="flow-properties-input flow-properties-yes-input" 
                value={conditionData?.successLabel ?? 'Yes'}
                onChange={e => updateNodeField(node.id, 'condition.successLabel', e.target.value)}
              />
            </div>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Failure Label</label>
              <input 
                className="flow-properties-input flow-properties-no-input" 
                value={conditionData?.failureLabel ?? 'No'}
                onChange={e => updateNodeField(node.id, 'condition.failureLabel', e.target.value)}
              />
            </div>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Notes</label>
              <textarea
                className="flow-properties-textarea"
                rows={3}
                value={conditionData?.notes ?? ''}
                onChange={e => updateNodeField(node.id, 'condition.notes', e.target.value)}
                placeholder="Optional details"
              />
            </div>
            <div className="mb-3 rounded-lg border border-amber-200 bg-amber-50 px-2.5 py-2 text-[0.7rem] text-slate-500">
              <strong className="text-amber-700">Tip:</strong> Drag from the 🟢 green handle for <em>Yes</em>,
              🔴 red handle for <em>No</em>. Connect to any step or section.
            </div>
          </>
        )}

        {/* Actions */}
        <div className="my-2 h-px bg-slate-100" />
        <div className="flow-editor-section-heading">Actions</div>

        <div className="flow-properties-actions">
          <button
            className="flow-properties-action-btn flow-properties-duplicate-btn"
            onClick={() => onDuplicateNode?.(node.id)}
          >
            📋 Duplicate
          </button>

          <button
            className="flow-properties-action-btn flow-properties-delete-btn"
            onClick={() => onDeleteNode?.(node.id)}
          >
            🗑 Delete
          </button>
        </div>
      </div>
    </div>
  )
}