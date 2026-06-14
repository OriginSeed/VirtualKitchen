import React from 'react'

type NodeData = {
  id: string
  data: {
    title?: string
    description?: string
    duration?: string
    icon?: string
    sectionId?: string
  }
}

type Props = {
  node?: NodeData
  updateNodeField: (nodeId: string, field: string, value: string) => void
  onDeleteNode?: (nodeId: string) => void
  onDuplicateNode?: (nodeId: string) => void
}

const inputStyle: React.CSSProperties = {
  width: '100%',
  padding: '8px 12px',
  borderRadius: 8,
  border: '1.5px solid #e2e8f0',
  fontSize: 13,
  color: '#1e293b',
  background: 'white',
  outline: 'none',
  boxSizing: 'border-box',
  fontFamily: 'inherit',
  transition: 'border-color 0.15s',
}

const labelStyle: React.CSSProperties = {
  display: 'block',
  fontSize: 12,
  fontWeight: 600,
  color: '#64748b',
  marginBottom: 6,
  letterSpacing: '-0.01em',
}

const sectionOptions = [
  { value: 'prep', label: 'Preparation', emoji: '🥗' },
  { value: 'cook', label: 'Cooking', emoji: '🍳' },
  { value: 'serve', label: 'Serving', emoji: '🍽️' },
]

export default function PropertiesPanel({ node, updateNodeField, onDeleteNode, onDuplicateNode }: Props) {
  if (!node) {
    return (
      <div
        style={{
          width: 280,
          borderLeft: '1px solid #e2e8f0',
          background: '#f8fafc',
          padding: 20,
          display: 'flex',
          flexDirection: 'column',
          fontFamily: "'DM Sans', system-ui, sans-serif",
        }}
      >
        <h2 style={{ fontSize: 15, fontWeight: 700, color: '#1e293b', margin: '0 0 16px' }}>Step Properties</h2>
        <div
          style={{
            border: '1.5px dashed #cbd5e1',
            borderRadius: 12,
            padding: '32px 20px',
            textAlign: 'center',
            color: '#94a3b8',
            fontSize: 13,
          }}
        >
          <div style={{ fontSize: 28, marginBottom: 8 }}>👆</div>
          Select a step to edit its properties
        </div>
      </div>
    )
  }

  const d = node.data

  return (
    <div
      style={{
        width: 280,
        borderLeft: '1px solid #e2e8f0',
        background: 'white',
        display: 'flex',
        flexDirection: 'column',
        fontFamily: "'DM Sans', system-ui, sans-serif",
        height: '100vh',
        overflow: 'auto',
      }}
    >
      {/* Header */}
      <div
        style={{
          padding: '18px 20px 14px',
          borderBottom: '1px solid #f1f5f9',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
        }}
      >
        <h2 style={{ fontSize: 15, fontWeight: 700, color: '#1e293b', margin: 0 }}>Step Properties</h2>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div
            style={{
              width: 36,
              height: 36,
              borderRadius: 8,
              background: '#eff6ff',
              border: '1px solid #bfdbfe',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 20,
            }}
          >
            {d.icon || '🍳'}
          </div>
          <div>
            <div style={{ fontSize: 13, fontWeight: 600, color: '#1e293b' }}>{d.title || 'Step'}</div>
            <div style={{ fontSize: 10, color: '#94a3b8' }}>Action Step</div>
          </div>
        </div>
      </div>

      <div style={{ padding: '16px 20px', flex: 1, overflow: 'auto' }}>
        {/* General section */}
        <div style={{ marginBottom: 20 }}>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: '#94a3b8',
              letterSpacing: '0.08em',
              textTransform: 'uppercase',
              marginBottom: 14,
            }}
          >
            General
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>Title</label>
            <input
              style={inputStyle}
              value={d.title ?? ''}
              onChange={e => updateNodeField(node.id, 'title', e.target.value)}
              onFocus={e => ((e.target as HTMLInputElement).style.borderColor = '#6366f1')}
              onBlur={e => ((e.target as HTMLInputElement).style.borderColor = '#e2e8f0')}
            />
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>Description</label>
            <textarea
              rows={3}
              style={{ ...inputStyle, resize: 'none' }}
              value={d.description ?? ''}
              onChange={e => updateNodeField(node.id, 'description', e.target.value)}
              onFocus={e => ((e.target as HTMLTextAreaElement).style.borderColor = '#6366f1')}
              onBlur={e => ((e.target as HTMLTextAreaElement).style.borderColor = '#e2e8f0')}
            />
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>Duration</label>
            <div style={{ position: 'relative' }}>
              <span
                style={{
                  position: 'absolute',
                  left: 10,
                  top: '50%',
                  transform: 'translateY(-50%)',
                  fontSize: 14,
                }}
              >
                ⏱
              </span>
              <input
                style={{ ...inputStyle, paddingLeft: 30 }}
                value={d.duration ?? ''}
                onChange={e => updateNodeField(node.id, 'duration', e.target.value)}
                placeholder="e.g. 5 mins"
                onFocus={e => ((e.target as HTMLInputElement).style.borderColor = '#6366f1')}
                onBlur={e => ((e.target as HTMLInputElement).style.borderColor = '#e2e8f0')}
              />
            </div>
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>Icon</label>
            <input
              style={inputStyle}
              value={d.icon ?? ''}
              onChange={e => updateNodeField(node.id, 'icon', e.target.value)}
              placeholder="Paste an emoji, e.g. 🍅"
              onFocus={e => ((e.target as HTMLInputElement).style.borderColor = '#6366f1')}
              onBlur={e => ((e.target as HTMLInputElement).style.borderColor = '#e2e8f0')}
            />
          </div>

          <div style={{ marginBottom: 14 }}>
            <label style={labelStyle}>Section</label>
            <select
              style={{ ...inputStyle, appearance: 'none', cursor: 'pointer' }}
              value={d.sectionId ?? 'prep'}
              onChange={e => updateNodeField(node.id, 'sectionId', e.target.value)}
            >
              {sectionOptions.map(opt => (
                <option key={opt.value} value={opt.value}>
                  {opt.emoji} {opt.label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* Divider */}
        <div style={{ height: 1, background: '#f1f5f9', margin: '0 0 20px' }} />

        {/* Actions */}
        <div>
          <div
            style={{
              fontSize: 11,
              fontWeight: 700,
              color: '#94a3b8',
              letterSpacing: '0.08em',
              textTransform: 'uppercase',
              marginBottom: 14,
            }}
          >
            Actions
          </div>

          <button
            onClick={() => onDeleteNode?.(node.id)}
            style={{
              width: '100%',
              padding: '9px',
              borderRadius: 8,
              border: '1.5px solid #fecaca',
              background: '#fff5f5',
              color: '#dc2626',
              fontSize: 13,
              fontWeight: 600,
              cursor: 'pointer',
              marginBottom: 8,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 6,
              transition: 'background 0.15s',
            }}
            onMouseEnter={e => ((e.currentTarget as HTMLElement).style.background = '#fee2e2')}
            onMouseLeave={e => ((e.currentTarget as HTMLElement).style.background = '#fff5f5')}
          >
            🗑 Delete Step
          </button>

          <button
            onClick={() => onDuplicateNode?.(node.id)}
            style={{
              width: '100%',
              padding: '9px',
              borderRadius: 8,
              border: '1.5px solid #e2e8f0',
              background: 'white',
              color: '#475569',
              fontSize: 13,
              fontWeight: 600,
              cursor: 'pointer',
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              gap: 6,
            }}
            onMouseEnter={e => ((e.currentTarget as HTMLElement).style.background = '#f8fafc')}
            onMouseLeave={e => ((e.currentTarget as HTMLElement).style.background = 'white')}
          >
            📋 Duplicate Step
          </button>
        </div>
      </div>
    </div>
  )
}