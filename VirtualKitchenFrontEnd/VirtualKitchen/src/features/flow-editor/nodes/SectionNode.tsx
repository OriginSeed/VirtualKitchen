import React from 'react'
import { Handle, Position, NodeResizer } from '@xyflow/react'

const sectionThemes = {
  'section-prep': {
    border: '#86efac',
    bg: '#f0fdf4',
    headerBg: '#dcfce7',
    accent: '#16a34a',
    light: '#bbf7d0',
    emoji: '🥗',
    label: 'Preparation',
  },
  'section-cook': {
    border: '#fcd34d',
    bg: '#fffbeb',
    headerBg: '#fef3c7',
    accent: '#d97706',
    light: '#fde68a',
    emoji: '🍳',
    label: 'Cooking',
  },
  'section-serve': {
    border: '#93c5fd',
    bg: '#eff6ff',
    headerBg: '#dbeafe',
    accent: '#2563eb',
    light: '#bfdbfe',
    emoji: '🍽️',
    label: 'Serving',
  },
} as const

type SectionId = keyof typeof sectionThemes

interface SectionNodeProps {
  selected: boolean
  id: string
  style?: React.CSSProperties
  data: {
    title: string
    stepCount?: number
    collapsed?: boolean
    onAddStep?: (sectionId: string) => void
    onToggleCollapse?: (sectionId: string) => void
  }
}

export default function SectionNode({ selected, id, data, style }: SectionNodeProps) {
  const theme = sectionThemes[id as SectionId] ?? {
    border: '#d1d5db',
    bg: '#f9fafb',
    headerBg: '#f3f4f6',
    accent: '#6b7280',
    light: '#e5e7eb',
    emoji: '📋',
    label: 'Section',
  }

  const isCollapsed = data.collapsed ?? false
  const stepCount = data.stepCount ?? 0

  return (
    <div
      style={{
        width: '100%',
        height: '100%',
        borderRadius: 16,
        border: `2px solid ${selected ? theme.accent : theme.border}`,
        background: isCollapsed ? theme.headerBg : theme.bg,
        boxShadow: selected
          ? `0 0 0 3px ${theme.accent}22, 0 4px 24px ${theme.border}66`
          : `0 2px 12px ${theme.border}44`,
        overflow: 'hidden',
        transition: 'box-shadow 0.2s, border-color 0.2s',
        display: 'flex',
        flexDirection: 'column',
      }}
    >
      <NodeResizer
        minWidth={420}
        minHeight={isCollapsed ? 72 : 220}
        isVisible={selected}
        lineStyle={{ borderColor: theme.accent }}
        handleStyle={{ borderColor: theme.accent, background: '#fff' }}
      />

      {/* Header */}
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          padding: '12px 16px',
          background: theme.headerBg,
          borderBottom: isCollapsed ? 'none' : `1px solid ${theme.light}`,
          flexShrink: 0,
        }}
      >
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <span style={{ fontSize: 20 }}>{theme.emoji}</span>
          <div>
            <div style={{ fontWeight: 700, fontSize: 14, color: '#1e293b', letterSpacing: '-0.01em' }}>
              {data.title}
            </div>
            <div style={{ fontSize: 11, color: theme.accent, fontWeight: 500 }}>
              {stepCount} {stepCount === 1 ? 'step' : 'steps'}
            </div>
          </div>
        </div>

        <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
          {/* Step count badge */}
          <span
            style={{
              background: theme.light,
              color: theme.accent,
              borderRadius: 20,
              padding: '2px 10px',
              fontSize: 11,
              fontWeight: 600,
            }}
          >
            {stepCount} steps
          </span>

          {/* Collapse toggle */}
          <button
            className="nodrag"
            onClick={(e) => {
              e.stopPropagation()
              data.onToggleCollapse?.(id)
            }}
            style={{
              background: 'white',
              border: `1px solid ${theme.light}`,
              borderRadius: 6,
              width: 26,
              height: 26,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              cursor: 'pointer',
              color: theme.accent,
              fontSize: 14,
              transition: 'background 0.15s',
            }}
            title={isCollapsed ? 'Expand' : 'Collapse'}
          >
            {isCollapsed ? '▼' : '▲'}
          </button>

          {/* Add step button */}
          <button
            className="nodrag"
            onClick={(e) => {
              e.stopPropagation()
              data.onAddStep?.(id)
            }}
            style={{
              background: theme.accent,
              color: 'white',
              border: 'none',
              borderRadius: 6,
              padding: '4px 10px',
              fontSize: 11,
              fontWeight: 600,
              cursor: 'pointer',
              letterSpacing: '0.01em',
            }}
          >
            + Step
          </button>
        </div>
      </div>

      {/* Body */}
      {!isCollapsed && (
        <div style={{ flex: 1 }} />
      )}

      {/* Handles */}
      <Handle
        id="section-target"
        type="target"
        position={Position.Top}
        style={{
          width: 14,
          height: 14,
          background: theme.accent,
          border: '2px solid white',
          boxShadow: `0 0 0 2px ${theme.accent}`,
          zIndex: 10,
        }}
      />
      <Handle
        id="section-source"
        type="source"
        position={Position.Bottom}
        style={{
          width: 14,
          height: 14,
          background: theme.accent,
          border: '2px solid white',
          boxShadow: `0 0 0 2px ${theme.accent}`,
          zIndex: 10,
        }}
      />
    </div>
  )
}