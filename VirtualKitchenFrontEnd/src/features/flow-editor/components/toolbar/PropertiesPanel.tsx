import React from 'react'

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

const inputStyle: React.CSSProperties = {
  width: '100%', padding: '7px 10px', borderRadius: 7,
  border: '1.5px solid #e2e8f0', fontSize: 13, color: '#1e293b',
  background: 'white', outline: 'none', boxSizing: 'border-box',
  fontFamily: 'inherit', transition: 'border-color 0.15s',
}
const label = (text: string) => (
  <label style={{ display: 'block', fontSize: 11, fontWeight: 600, color: '#64748b', marginBottom: 5, letterSpacing: '-0.01em' }}>
    {text}
  </label>
)
const Field = ({ children, style }: { children: React.ReactNode; style?: React.CSSProperties }) => (
  <div style={{ marginBottom: 12, ...style }}>{children}</div>
)
const SectionHeader = ({ text }: { text: string }) => (
  <div style={{ fontSize: 10, fontWeight: 700, color: '#94a3b8', letterSpacing: '0.08em',
    textTransform: 'uppercase', margin: '16px 0 10px' }}>
    {text}
  </div>
)
const focus = (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) =>
  (e.target.style.borderColor = '#6366f1')
const blur = (e: React.FocusEvent<HTMLInputElement | HTMLTextAreaElement>) =>
  (e.target.style.borderColor = '#e2e8f0')

export default function PropertiesPanel({ node, sectionTitle, updateNodeField, onDeleteNode, onDuplicateNode }: Props) {
  if (!node) return (
    <div style={{ width: 260, borderLeft: '1px solid #e2e8f0', background: '#f8fafc',
      padding: 20, fontFamily: "'DM Sans', system-ui, sans-serif" }}>
      <h2 style={{ fontSize: 14, fontWeight: 700, color: '#1e293b', margin: '0 0 14px' }}>Properties</h2>
      <div style={{ border: '1.5px dashed #cbd5e1', borderRadius: 10, padding: '28px 16px',
        textAlign: 'center', color: '#94a3b8', fontSize: 12 }}>
        <div style={{ fontSize: 24, marginBottom: 6 }}>👆</div>
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
    <div style={{ width: 260, borderLeft: '1px solid #e2e8f0', background: 'white',
      display: 'flex', flexDirection: 'column', fontFamily: "'DM Sans', system-ui, sans-serif",
      height: '100vh', overflow: 'auto' }}>

      {/* Header */}
      <div style={{ padding: '14px 16px 10px', borderBottom: '1px solid #f1f5f9' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <span style={{ fontSize: 22 }}>{typeIcon}</span>
          <div>
            <div style={{ fontSize: 13, fontWeight: 700, color: '#1e293b' }}>{d.title || 'Untitled'}</div>
            <span style={{ fontSize: 10, fontWeight: 600, color: typeColor,
              background: typeBg, border: `1px solid ${typeBorder}`, borderRadius: 4, padding: '1px 6px' }}>
              {typeLabel}
            </span>
          </div>
        </div>
      </div>

      <div style={{ padding: '4px 16px 16px', flex: 1, overflow: 'auto' }}>
        <SectionHeader text="General" />

        <Field>
          {label('Title')}
          <input style={inputStyle} value={d.title ?? ''}
            onChange={e => updateNodeField(node.id, 'title', e.target.value)}
            onFocus={focus} onBlur={blur} />
        </Field>

        {sectionTitle && !isSection && (
          <Field>
            {label('Section')}
            <div style={{ ...inputStyle, background: '#f8fafc', cursor: 'default' }}>
              {sectionTitle}
            </div>
          </Field>
        )}

        <Field>
          {label('Description')}
          <textarea rows={3} style={{ ...inputStyle, resize: 'none' }} value={d.description ?? ''}
            onChange={e => updateNodeField(node.id, 'description', e.target.value)}
            onFocus={focus as any} onBlur={blur as any} />
        </Field>

        {/* Step-only fields */}
        {!isCondition && !isSection && (
          <>
            <Field>
              {label('Duration')}
              <input style={inputStyle} value={d.duration ?? ''}
                onChange={e => updateNodeField(node.id, 'duration', e.target.value)}
                placeholder="e.g. 5 mins" onFocus={focus} onBlur={blur} />
            </Field>
            <Field>
              {label('Icon (emoji)')}
              <input style={inputStyle} value={d.icon ?? ''}
                onChange={e => updateNodeField(node.id, 'icon', e.target.value)}
                placeholder="🍅" onFocus={focus} onBlur={blur} />
            </Field>
          </>
        )}

        {/* Condition-only fields */}
        {isCondition && (
          <>
            <SectionHeader text="Branch Labels" />
            <Field>
              {label('Yes Label')}
              <input style={{ ...inputStyle, borderColor: '#86efac' }} value={d.yesLabel ?? 'Yes'}
                onChange={e => updateNodeField(node.id, 'yesLabel', e.target.value)}
                onFocus={e => (e.target.style.borderColor = '#16a34a')}
                onBlur={e => (e.target.style.borderColor = '#86efac')} />
            </Field>
            <Field>
              {label('No Label')}
              <input style={{ ...inputStyle, borderColor: '#fca5a5' }} value={d.noLabel ?? 'No'}
                onChange={e => updateNodeField(node.id, 'noLabel', e.target.value)}
                onFocus={e => (e.target.style.borderColor = '#dc2626')}
                onBlur={e => (e.target.style.borderColor = '#fca5a5')} />
            </Field>
            <div style={{ fontSize: 11, color: '#94a3b8', background: '#fffbeb',
              border: '1px solid #fde68a', borderRadius: 8, padding: '8px 10px', marginBottom: 12 }}>
              <strong style={{ color: '#d97706' }}>Tip:</strong> Drag from the 🟢 green handle for <em>Yes</em>,
              🔴 red handle for <em>No</em>. Connect to any step or section.
            </div>
          </>
        )}

        {/* Divider */}
        <div style={{ height: 1, background: '#f1f5f9', margin: '4px 0 14px' }} />

        {/* Actions */}
        <SectionHeader text="Actions" />

        {!isSection && (
          <button onClick={() => onDuplicateNode?.(node.id)}
            style={{ width: '100%', padding: '8px', borderRadius: 7, border: '1.5px solid #e2e8f0',
              background: 'white', color: '#475569', fontSize: 12, fontWeight: 600,
              cursor: 'pointer', marginBottom: 8, display: 'flex', alignItems: 'center',
              justifyContent: 'center', gap: 5 }}
            onMouseEnter={e => ((e.currentTarget as HTMLElement).style.background = '#f8fafc')}
            onMouseLeave={e => ((e.currentTarget as HTMLElement).style.background = 'white')}>
            📋 Duplicate
          </button>
        )}

        <button onClick={() => onDeleteNode?.(node.id)}
          style={{ width: '100%', padding: '8px', borderRadius: 7, border: '1.5px solid #fecaca',
            background: '#fff5f5', color: '#dc2626', fontSize: 12, fontWeight: 600,
            cursor: 'pointer', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 5 }}
          onMouseEnter={e => ((e.currentTarget as HTMLElement).style.background = '#fee2e2')}
          onMouseLeave={e => ((e.currentTarget as HTMLElement).style.background = '#fff5f5')}>
          🗑 Delete
        </button>
      </div>
    </div>
  )
}