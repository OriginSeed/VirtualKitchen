import React from 'react'
import '../../styles/flow-editor.css'

type FlowEditorTopBarProps = {
  title: string
  onUndo: () => void
  onRedo: () => void
  canUndo: boolean
  canRedo: boolean
  onExport: () => void
  onSave: () => void
  onVisualize: () => void
  isVisualizing?: boolean
  onBack?: () => void
}

export default function FlowEditorTopBar({
  title,
  onUndo,
  onRedo,
  canUndo,
  canRedo,
  onExport,
  onSave,
  onVisualize,
  isVisualizing = false,
  onBack,
}: FlowEditorTopBarProps) {
  const btnStyle = (extra: React.CSSProperties = {}): React.CSSProperties => ({
    padding: '8px 12px', borderRadius: 8, border: '1px solid #e2e8f0',
    background: 'white', color: '#475569', fontSize: 12, fontWeight: 600,
    cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 6,
    ...extra,
  })

  return (
    <div className="flex h-[3.75rem] flex-shrink-0 items-center justify-between border-b border-[var(--flow-border)] bg-white px-4">
      <div className="flex items-center gap-2.5">
        {onBack && (
          <button onClick={onBack} style={{ ...btnStyle(), padding: '8px 10px' }} title="Back">
            ← Back
          </button>
        )}
        <div>
          <div className="text-sm font-semibold text-[var(--flow-text)]">{title}</div>
          <div className="text-[0.7rem] text-[var(--flow-text-muted)]">Design your recipe flow</div>
        </div>
      </div>

      <div className="flex items-center gap-2">
        <button onClick={onUndo} disabled={!canUndo} style={btnStyle({ background: canUndo ? 'white' : '#f8fafc', color: canUndo ? '#475569' : '#cbd5e1', cursor: canUndo ? 'pointer' : 'default' })} title="Undo">↩</button>
        <button onClick={onRedo} disabled={!canRedo} style={btnStyle({ background: canRedo ? 'white' : '#f8fafc', color: canRedo ? '#475569' : '#cbd5e1', cursor: canRedo ? 'pointer' : 'default' })} title="Redo">↪</button>
        <button onClick={onVisualize} disabled={isVisualizing} style={btnStyle({ background: '#eff6ff', borderColor: '#93c5fd', color: '#2563eb', opacity: isVisualizing ? 0.7 : 1 })}>{isVisualizing ? '⏳ Visualizing…' : '🎬 Visualize'}</button>
        <button onClick={onExport} style={btnStyle({ background: '#f0fdf4', borderColor: '#86efac', color: '#16a34a' })}>📤 Export</button>
        <button onClick={onSave} style={btnStyle({ background: '#fef3c7', borderColor: '#fde68a', color: '#92400e' })}>💾 Save</button>
      </div>
    </div>
  )
}
