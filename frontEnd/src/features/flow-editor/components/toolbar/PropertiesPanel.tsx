import './PropertiesPanel.css'
import '../../styles/flow-editor.css'
import {
  ACTIONS_BY_CATEGORY,
  ACTION_CATEGORY_ORDER,
} from '../../catalog/actionCatalog'
import {
  normalizeStepNodeData,
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
                value={stepData?.ingredient ?? ''}
                onChange={e => updateNodeField(node.id, 'step.ingredient', e.target.value)}
                placeholder="Tomato"
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
              <input
                className="flow-properties-input"
                value={stepData?.unit ?? ''}
                onChange={e => updateNodeField(node.id, 'step.unit', e.target.value)}
                placeholder="cups"
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Specification</label>
              <input
                className="flow-properties-input"
                value={stepData?.specification ?? ''}
                onChange={e => updateNodeField(node.id, 'step.specification', e.target.value)}
                placeholder="finely chopped"
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Flame</label>
              <input
                className="flow-properties-input"
                value={stepData?.flame ?? ''}
                onChange={e => updateNodeField(node.id, 'step.flame', e.target.value)}
                placeholder="medium"
              />
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
              <input
                className="flow-properties-input"
                value={stepData?.duration ?? ''}
                onChange={e => updateNodeField(node.id, 'step.duration', e.target.value)}
                placeholder="5 min"
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Repeat Interval</label>
              <input
                className="flow-properties-input"
                value={stepData?.repeatInterval ?? ''}
                onChange={e => updateNodeField(node.id, 'step.repeatInterval', e.target.value)}
                placeholder="Every 30 sec"
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
              <label className="flow-properties-label">Title</label>
              <input
                className="flow-properties-input"
                value={d.title ?? ''}
                onChange={e => updateNodeField(node.id, 'title', e.target.value)}
              />
            </div>

            <div className="flow-properties-field">
              <label className="flow-properties-label">Description</label>
              <textarea
                className="flow-properties-textarea"
                rows={3}
                value={d.description ?? ''}
                onChange={e => updateNodeField(node.id, 'description', e.target.value)}
              />
            </div>

            <div className="flow-editor-section-heading">Branch Labels</div>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Yes Label</label>
              <input 
                className="flow-properties-input flow-properties-yes-input" 
                value={d.yesLabel ?? 'Yes'}
                onChange={e => updateNodeField(node.id, 'yesLabel', e.target.value)}
              />
            </div>
            <div className="flow-properties-field">
              <label className="flow-properties-label">No Label</label>
              <input 
                className="flow-properties-input flow-properties-no-input" 
                value={d.noLabel ?? 'No'}
                onChange={e => updateNodeField(node.id, 'noLabel', e.target.value)}
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