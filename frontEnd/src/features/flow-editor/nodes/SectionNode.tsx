import React, { useState } from 'react'
import { Handle, Position, NodeResizer } from '@xyflow/react'

const sectionThemes: Record<string, { border: string; bg: string; headerBg: string; accent: string; light: string; emoji: string }> = {
  'section-prep':  { border: '#86efac', bg: '#f0fdf4', headerBg: '#dcfce7', accent: '#16a34a', light: '#bbf7d0', emoji: '🥗' },
  'section-cook':  { border: '#fcd34d', bg: '#fffbeb', headerBg: '#fef3c7', accent: '#d97706', light: '#fde68a', emoji: '🍳' },
  'section-serve': { border: '#93c5fd', bg: '#eff6ff', headerBg: '#dbeafe', accent: '#2563eb', light: '#bfdbfe', emoji: '🍽️' },
}

const defaultTheme = { border: '#d1d5db', bg: '#f9fafb', headerBg: '#f3f4f6', accent: '#6b7280', light: '#e5e7eb', emoji: '📋' }

interface SectionNodeProps {
  selected: boolean
  id: string
  style?: React.CSSProperties
  data: {
    title: string
    stepCount?: number
    collapsed?: boolean
    onAddStep?: (id: string) => void
    onAddCondition?: (id: string) => void
    onToggleCollapse?: (id: string) => void
  }
}

export default function SectionNode({ selected, id, data, style }: SectionNodeProps) {
  const [showAddMenu, setShowAddMenu] = useState(false)
  const theme = sectionThemes[id] ?? defaultTheme
  const containerStyle = { width: '100%', height: '100%', ...style }
  const isCollapsed = data.collapsed ?? false
  const stepCount = data.stepCount ?? 0

  const handleAddStep = () => {
    data.onAddStep?.(id)
    setShowAddMenu(false)
  }

  const handleAddCondition = () => {
    data.onAddCondition?.(id)
    setShowAddMenu(false)
  }

  return (
    <div style={{
      ...containerStyle, borderRadius: 16,
      border: `2px solid ${selected ? theme.accent : theme.border}`,
      background: isCollapsed ? theme.headerBg : theme.bg,
      boxShadow: selected
        ? `0 0 0 3px ${theme.accent}22, 0 4px 24px ${theme.border}66`
        : `0 2px 12px ${theme.border}44`,
      overflow: 'hidden', transition: 'box-shadow 0.2s, border-color 0.2s',
      display: 'flex', flexDirection: 'column',
    }}>
      <NodeResizer
        minWidth={420} minHeight={isCollapsed ? 72 : 220} isVisible={selected}
        lineStyle={{ borderColor: theme.accent }}
        handleStyle={{ borderColor: theme.accent, background: '#fff' }}
      />

      {/* Header */}
      <div style={{
        display: 'flex', alignItems: 'center', justifyContent: 'space-between',
        padding: '10px 14px', background: theme.headerBg,
        borderBottom: isCollapsed ? 'none' : `1px solid ${theme.light}`, flexShrink: 0,
      }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          <div>
            <div style={{ fontWeight: 700, fontSize: 13, color: '#1e293b' }}>{data.title}</div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 5, position: 'relative' }}>
          <span style={{
            background: theme.light, color: theme.accent, borderRadius: 20,
            padding: '2px 8px', fontSize: 10, fontWeight: 600,
          }}>{stepCount} steps</span>

          {/* Collapse */}
          <button className="nodrag" onClick={e => { e.stopPropagation(); data.onToggleCollapse?.(id) }}
            style={{ background: 'white', border: `1px solid ${theme.light}`, borderRadius: 6,
              width: 24, height: 24, display: 'flex', alignItems: 'center', justifyContent: 'center',
              cursor: 'pointer', color: theme.accent, fontSize: 12 }}
            title={isCollapsed ? 'Expand' : 'Collapse'}>
            {isCollapsed ? '▼' : '▲'}
          </button>

          {/* Add Node with Dropdown */}
          <div style={{ position: 'relative' }}>
            <button className="nodrag" onClick={e => { e.stopPropagation(); setShowAddMenu(!showAddMenu) }}
              style={{ background: theme.accent, color: 'white', border: 'none', borderRadius: 6,
                padding: '4px 10px', fontSize: 11, fontWeight: 600, cursor: 'pointer',
                display: 'flex', alignItems: 'center', gap: 4 }}>
              + Add
              <span style={{ fontSize: 9, marginLeft: 2 }}>{showAddMenu ? '▲' : '▼'}</span>
            </button>

            {/* Dropdown Menu */}
            {showAddMenu && (
              <div style={{
                position: 'absolute', top: '100%', right: 0, marginTop: 4,
                background: 'white', border: `1px solid ${theme.light}`, borderRadius: 8,
                boxShadow: '0 4px 12px rgba(0,0,0,0.15)', zIndex: 1000, minWidth: 160,
              }}>
                <button className="nodrag" onClick={e => { e.stopPropagation(); handleAddStep() }}
                  style={{
                    width: '100%', textAlign: 'left', padding: '8px 12px', border: 'none',
                    background: 'transparent', cursor: 'pointer', fontSize: 11, fontWeight: 500,
                    color: '#1e293b', borderBottom: `1px solid ${theme.light}`,
                  }}>
                  📝 Step Node
                </button>
                <button className="nodrag" onClick={e => { e.stopPropagation(); handleAddCondition() }}
                  style={{
                    width: '100%', textAlign: 'left', padding: '8px 12px', border: 'none',
                    background: 'transparent', cursor: 'pointer', fontSize: 11, fontWeight: 500,
                    color: '#1e293b',
                  }}>
                  🔀 Condition Node
                </button>
              </div>
            )}
          </div>
        </div>
      </div>

      {!isCollapsed && <div style={{ flex: 1 }} />}

      {/* Handles */}
      <Handle id="section-target" type="target" position={Position.Top} style={{
        width: 14, height: 14, background: theme.accent, border: '2px solid white',
        boxShadow: `0 0 0 2px ${theme.accent}`, zIndex: 10,
      }} />
      <Handle id="section-source" type="source" position={Position.Bottom} style={{
        width: 14, height: 14, background: theme.accent, border: '2px solid white',
        boxShadow: `0 0 0 2px ${theme.accent}`, zIndex: 10,
      }} />
    </div>
  )
}