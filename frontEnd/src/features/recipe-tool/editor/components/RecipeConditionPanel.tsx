import '../../styles/RecipePropertiesPanel.css'
import '../../../../styles/flow-ui.css'
import {
  normalizeConditionNodeData,
  type ConditionNodeStructuredFields,
} from '../../model/recipeConditionData'
import { INPUT_LIMITS } from '../../../../shared/inputLimits'

type ConditionPanelNode = {
  id: string
  data: {
    title?: string
    condition?: ConditionNodeStructuredFields
  }
}

type Props = {
  /** The selected CONDITION node, or undefined when nothing is selected (renders the empty state). */
  node?: ConditionPanelNode
  updateNodeField: (nodeId: string, field: string, value: string) => void
  onDeleteNode?: (nodeId: string) => void
  onDuplicateNode?: (nodeId: string) => void
}

/**
 * Properties panel of the Recipe Process canvas for a CONDITION node — and the "Select a node to
 * edit" empty state when nothing is selected. STEP nodes use RecipeStepPanel instead.
 */
export default function RecipeConditionPanel({ node, updateNodeField, onDeleteNode, onDuplicateNode }: Props) {
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
  const conditionData = normalizeConditionNodeData(d).condition

  return (
    <div className="flow-properties-panel">
      {/* Header */}
      <div className="flow-properties-header">
        <div className="flow-properties-header-content">
          <span className="flow-properties-icon">🔀</span>
          <div className="flow-properties-header-info">
            <div className="flow-properties-header-title">{d.title || 'Untitled'}</div>
            <span
              className="flow-properties-type-badge"
              style={{ background: '#fffbeb', border: '1px solid #fcd34d', color: '#d97706' }}
            >
              Condition
            </span>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flow-properties-content">
        <div className="flow-editor-section-heading">General</div>

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
            maxLength={INPUT_LIMITS.notes}
            onChange={e => updateNodeField(node.id, 'condition.notes', e.target.value)}
            placeholder="Optional details"
          />
        </div>
        <div
          className="mb-3 rounded-lg px-2.5 py-2 text-[0.7rem]"
          style={{ border: '1px solid var(--flow-warning-border)', background: 'var(--flow-warning-soft)', color: 'var(--flow-text-muted)' }}
        >
          <strong style={{ color: 'var(--flow-warning)' }}>Tip:</strong> Drag from the 🟢 green handle for <em>Yes</em>,
          🔴 red handle for <em>No</em>. Connect to any step or section.
        </div>

        {/* Actions */}
        <div className="my-2 h-px" style={{ background: 'var(--flow-border)' }} />
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
