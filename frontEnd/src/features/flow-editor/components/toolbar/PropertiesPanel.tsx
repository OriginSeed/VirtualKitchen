import './PropertiesPanel.css'
import '../../styles/flow-editor.css'

type NodeData = {
  id: string
  type?: string
  data: {
    title?: string
    description?: string
    duration?: string
    icon?: string
    sectionId?: string
    yesLabel?: string
    noLabel?: string
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

        {/* Title field */}
        <div className="flow-properties-field">
          <label className="flow-properties-label">Title</label>
          <input 
            className="flow-properties-input" 
            value={d.title ?? ''}
            onChange={e => updateNodeField(node.id, 'title', e.target.value)}
          />
        </div>

        <div className="flow-properties-field">
          <label className="flow-properties-label">Section ID</label>
          <div className="flow-properties-readonly">
            {d.sectionId ?? 'none'}
          </div>
        </div>

        {/* Description field */}
        <div className="flow-properties-field">
          <label className="flow-properties-label">Description</label>
          <textarea 
            className="flow-properties-textarea" 
            rows={3} 
            value={d.description ?? ''}
            onChange={e => updateNodeField(node.id, 'description', e.target.value)}
          />
        </div>

        {/* Step-only fields */}
        {!isCondition && (
          <>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Duration</label>
              <input
                className="flow-properties-input"
                value={d.duration ?? ''}
                onChange={e => updateNodeField(node.id, 'duration', e.target.value)}
                placeholder="e.g. 5 mins" 
              />
            </div>
            <div className="flow-properties-field">
              <label className="flow-properties-label">Icon (emoji)</label>
              <input
                className="flow-properties-input"
                value={d.icon ?? ''}
                onChange={e => updateNodeField(node.id, 'icon', e.target.value)}
                placeholder="🍅" 
              />
            </div>
          </>
        )}

        {/* Condition-only fields */}
        {isCondition && (
          <>
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