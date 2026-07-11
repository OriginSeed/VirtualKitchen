import './PropertiesPanel.css'

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
  sectionTitle?: string
  updateNodeField: (nodeId: string, field: string, value: string) => void
  onDeleteNode?: (nodeId: string) => void
  onDuplicateNode?: (nodeId: string) => void
}

export default function PropertiesPanel({ node, sectionTitle, updateNodeField, onDeleteNode, onDuplicateNode }: Props) {
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
  const isSection   = node.type === 'sectionNode'

  const typeIcon  = isCondition ? '🔀' : isSection ? '📋' : d.icon || '🍳'
  const typeLabel = isCondition ? 'Condition' : isSection ? 'Section' : 'Step'
  const typeBg    = isCondition ? '#fffbeb' : isSection ? '#eff6ff' : '#f0fdf4'
  const typeBorder= isCondition ? '#fcd34d' : isSection ? '#bfdbfe' : '#86efac'
  const typeColor = isCondition ? '#d97706' : isSection ? '#2563eb' : '#16a34a'

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
        <div className="flow-properties-section-header">General</div>

        {/* Title field */}
        <div className="flow-properties-field">
          <label className="flow-properties-label">Title</label>
          <input 
            className="flow-properties-input" 
            value={d.title ?? ''}
            onChange={e => updateNodeField(node.id, 'title', e.target.value)}
          />
        </div>

        {/* Section field (if nested) */}
        {sectionTitle && !isSection && (
          <div className="flow-properties-field">
            <label className="flow-properties-label">Section</label>
            <div className="flow-properties-readonly">
              {sectionTitle}
            </div>
          </div>
        )}

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
        {!isCondition && !isSection && (
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
            <div className="flow-properties-section-header">Branch Labels</div>
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
            <div style={{ fontSize: 11, color: '#94a3b8', background: '#fffbeb',
              border: '1px solid #fde68a', borderRadius: 8, padding: '8px 10px', marginBottom: 12 }}>
              <strong style={{ color: '#d97706' }}>Tip:</strong> Drag from the 🟢 green handle for <em>Yes</em>,
              🔴 red handle for <em>No</em>. Connect to any step or section.
            </div>
          </>
        )}

        {/* Actions */}
        <div style={{ height: 1, background: '#f1f5f9', margin: '4px 0 14px' }} />
        <div className="flow-properties-section-header">Actions</div>

        <div className="flow-properties-actions">
          {!isSection && (
            <button 
              className="flow-properties-action-btn flow-properties-duplicate-btn"
              onClick={() => onDuplicateNode?.(node.id)}
            >
              📋 Duplicate
            </button>
          )}

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