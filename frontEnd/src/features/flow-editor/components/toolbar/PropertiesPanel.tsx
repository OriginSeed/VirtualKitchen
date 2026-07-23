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
  getIngredientSearchValue,
  resolveIngredientInput,
} from '../../catalog/ingredientCatalog'
import {
  DURATION_UNIT_OPTIONS,
  FLAME_OPTIONS,
  REPEAT_INTERVAL_UNIT_OPTIONS,
  SPECIFICATION_OPTIONS,
  UNIT_OPTIONS,
  buildRepeatIntervalLabel,
} from '../../catalog/stepFieldCatalog'
import {
  normalizeConditionNodeData,
  normalizeStepNodeData,
  type ConditionNodeStructuredFields,
  type StepNodeStructuredFields,
} from '../../../../types/recipeFlow'

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
  const stepData = !isCondition ? normalizeStepNodeData(d).step : undefined
  const conditionData = isCondition ? normalizeConditionNodeData(d).condition : undefined
  const ingredientInputValue = stepData ? getIngredientSearchValue(stepData.ingredientId, stepData.customIngredientName) : ''
  const ingredientListId = `ingredient-catalog-${node.id}`
  const selectedIngredientMeta = (() => {
    if (!stepData?.ingredientId || stepData.ingredientId === CUSTOM_INGREDIENT_ID) return null
    return getIngredientById(stepData.ingredientId)
  })()
  const repeatActionLabel = stepData?.repeatAction ? getActionDisplayName(stepData.repeatAction) : ''
  const repeatExpression = stepData
    ? buildRepeatIntervalLabel(repeatActionLabel, stepData.repeatEveryValue, stepData.repeatEveryUnit)
    : ''

  const typeIcon  = isCondition ? '🔀' : d.icon || '🍳'
  const typeLabel = isCondition ? 'Condition' : 'Step'
  const typeBg    = isCondition ? '#fffbeb' : '#f0fdf4'
  const typeBorder= isCondition ? '#fcd34d' : '#86efac'
  const typeColor = isCondition ? '#d97706' : '#16a34a'

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

        {!isCondition && (
          <>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Action *</label>
              <select
                className="flow-properties-input"
                value={stepData?.action ?? ''}
                onChange={e => updateNodeField(node.id, 'step.action', e.target.value)}
              >
                <option value="">Select Action</option>
                {ACTION_CATEGORY_ORDER.map((category) => (
                  <optgroup key={category} label={category}>
                    {ACTIONS_BY_CATEGORY[category].map((action) => (
                      <option key={action.id} value={action.id}>
                        {action.icon} {action.displayName}
                      </option>
                    ))}
                  </optgroup>
                ))}
              </select>
            </div>

            <div className="flow-editor-section-heading">Step Details</div>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Ingredient</label>
              <input
                className="flow-properties-input"
                list={ingredientListId}
                value={ingredientInputValue}
                onChange={e => {
                  const resolved = resolveIngredientInput(e.target.value)
                  updateNodeField(node.id, 'step.ingredientId', resolved.ingredientId)
                  updateNodeField(node.id, 'step.customIngredientName', resolved.customIngredientName)
                }}
                placeholder="Search ingredient"
              />
              <datalist id={ingredientListId}>
                {INGREDIENT_CATEGORY_ORDER.map((category) =>
                  INGREDIENTS_BY_CATEGORY[category].map((ingredient) => (
                    <option
                      key={ingredient.id}
                      value={ingredient.name}
                      label={`${ingredient.icon} ${ingredient.name} (${category})`}
                    />
                  ))
                )}
              </datalist>
            </div>

            {stepData?.ingredientId === CUSTOM_INGREDIENT_ID && (
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

            <div className="flow-properties-field">
              <label className="flow-properties-label">Catalog Details</label>
              <input
                className="flow-properties-readonly"
                value={
                  selectedIngredientMeta
                    ? `${selectedIngredientMeta.category} | Default unit: ${selectedIngredientMeta.defaultUnit}`
                    : (stepData?.ingredientId === CUSTOM_INGREDIENT_ID ? 'Custom ingredient' : 'Not selected')
                }
                readOnly
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Quantity</label>
              <input
                className="flow-properties-input"
                value={stepData?.quantity ?? ''}
                onChange={e => updateNodeField(node.id, 'step.quantity', e.target.value)}
                placeholder="2"
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Unit</label>
              <select
                className="flow-properties-input"
                value={stepData?.unitOption ?? ''}
                onChange={e => updateNodeField(node.id, 'step.unitOption', e.target.value)}
              >
                <option value="">Select Unit</option>
                {UNIT_OPTIONS.map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
            </div>

            {stepData?.unitOption === 'Custom' && (
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

            <div className="flow-properties-field">
              <label className="flow-properties-label">Specification</label>
              <select
                className="flow-properties-input"
                value={stepData?.specificationOption ?? ''}
                onChange={e => updateNodeField(node.id, 'step.specificationOption', e.target.value)}
              >
                <option value="">Select Specification</option>
                {SPECIFICATION_OPTIONS.map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
            </div>

            {stepData?.specificationOption === 'Custom' && (
              <div className="flow-properties-field">
                <label className="flow-properties-label">Custom Specification</label>
                <input
                  className="flow-properties-input"
                  value={stepData.customSpecification}
                  onChange={e => updateNodeField(node.id, 'step.customSpecification', e.target.value)}
                  placeholder="Enter custom specification"
                />
              </div>
            )}

            <div className="flow-properties-field">
              <label className="flow-properties-label">Flame</label>
              <select
                className="flow-properties-input"
                value={stepData?.flame ?? 'None'}
                onChange={e => updateNodeField(node.id, 'step.flame', e.target.value)}
              >
                {FLAME_OPTIONS.map((option) => (
                  <option key={option} value={option}>{option}</option>
                ))}
              </select>
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Temperature</label>
              <input
                className="flow-properties-input"
                value={stepData?.temperature ?? ''}
                onChange={e => updateNodeField(node.id, 'step.temperature', e.target.value)}
                placeholder="180 C"
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Duration</label>
              <div className="flow-properties-actions" style={{ marginTop: 0, paddingTop: 0, borderTop: 'none' }}>
                <input
                  className="flow-properties-input"
                  value={stepData?.durationValue ?? ''}
                  onChange={e => updateNodeField(node.id, 'step.durationValue', e.target.value)}
                  placeholder="5"
                />
                <select
                  className="flow-properties-input"
                  value={stepData?.durationUnit ?? ''}
                  onChange={e => updateNodeField(node.id, 'step.durationUnit', e.target.value)}
                >
                  <option value="">Unit</option>
                  {DURATION_UNIT_OPTIONS.map((option) => (
                    <option key={option} value={option}>{option}</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Repeat Interval</label>
              <div className="flow-properties-actions" style={{ marginTop: 0, paddingTop: 0, borderTop: 'none', marginBottom: 8 }}>
                <select
                  className="flow-properties-input"
                  value={stepData?.repeatAction ?? ''}
                  onChange={e => updateNodeField(node.id, 'step.repeatAction', e.target.value)}
                >
                  <option value="">Action</option>
                  {ACTION_CATEGORY_ORDER.map((category) => (
                    <optgroup key={category} label={category}>
                      {ACTIONS_BY_CATEGORY[category].map((action) => (
                        <option key={action.id} value={action.id}>{action.displayName}</option>
                      ))}
                    </optgroup>
                  ))}
                </select>
                <input
                  className="flow-properties-input"
                  value={stepData?.repeatEveryValue ?? ''}
                  onChange={e => updateNodeField(node.id, 'step.repeatEveryValue', e.target.value)}
                  placeholder="2"
                />
                <select
                  className="flow-properties-input"
                  value={stepData?.repeatEveryUnit ?? ''}
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
                value={repeatExpression || 'Example: Mix every 2 minutes'}
                readOnly
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Notes</label>
              <textarea
                className="flow-properties-textarea"
                rows={3}
                value={stepData?.notes ?? ''}
                onChange={e => updateNodeField(node.id, 'step.notes', e.target.value)}
                placeholder="Any additional instructions"
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