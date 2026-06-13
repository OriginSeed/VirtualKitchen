import React from 'react'
import { Handle, Position } from '@xyflow/react'

const sectionStyles = {
  prep: {
    border: '#86efac',
    iconBg: '#dcfce7',
    accent: '#16a34a',
    badge: '#f0fdf4',
  },
  cook: {
    border: '#fcd34d',
    iconBg: '#fef3c7',
    accent: '#d97706',
    badge: '#fffbeb',
  },
  serve: {
    border: '#93c5fd',
    iconBg: '#dbeafe',
    accent: '#2563eb',
    badge: '#eff6ff',
  },
} as const

type SectionId = keyof typeof sectionStyles

interface RecipeStepNodeProps {
  selected: boolean
  data: {
    sectionId?: SectionId
    icon: React.ReactNode
    title: string
    description: string
    duration: string
    stepNumber?: number
  }
}

export default function RecipeStepNode({ selected, data }: RecipeStepNodeProps) {
  const style = sectionStyles[data.sectionId ?? 'prep'] ?? {
    border: '#e5e7eb',
    iconBg: '#f9fafb',
    accent: '#6b7280',
    badge: '#f3f4f6',
  }

  return (
    <div
      style={{
        minWidth: 260,
        maxWidth: 320,
        background: 'white',
        borderRadius: 12,
        border: `1.5px solid ${selected ? style.accent : '#e5e7eb'}`,
        boxShadow: selected
          ? `0 0 0 2px ${style.accent}33, 0 4px 16px rgba(0,0,0,0.08)`
          : '0 1px 6px rgba(0,0,0,0.06)',
        padding: '12px 14px',
        transition: 'all 0.18s ease',
        position: 'relative',
      }}
    >
      <Handle
        type="target"
        position={Position.Top}
        style={{
          width: 10,
          height: 10,
          background: style.accent,
          border: '2px solid white',
          boxShadow: `0 0 0 1.5px ${style.accent}`,
        }}
      />

      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        {/* Icon */}
        <div
          style={{
            width: 36,
            height: 36,
            borderRadius: 8,
            background: style.iconBg,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontSize: 18,
            flexShrink: 0,
            border: `1px solid ${style.border}`,
          }}
        >
          {data.icon}
        </div>

        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontWeight: 600,
              fontSize: 13,
              color: '#1e293b',
              letterSpacing: '-0.01em',
              lineHeight: 1.3,
              marginBottom: 2,
            }}
          >
            {data.stepNumber !== undefined && (
              <span
                style={{
                  color: style.accent,
                  marginRight: 4,
                  fontSize: 11,
                  fontWeight: 700,
                }}
              >
                {data.stepNumber}.
              </span>
            )}
            {data.title}
          </div>

          {data.description && (
            <div
              style={{
                fontSize: 11,
                color: '#64748b',
                lineHeight: 1.4,
                marginBottom: 6,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'nowrap',
              }}
            >
              {data.description}
            </div>
          )}

          {data.duration && (
            <div
              style={{
                display: 'inline-flex',
                alignItems: 'center',
                gap: 4,
                background: style.badge,
                border: `1px solid ${style.border}`,
                borderRadius: 20,
                padding: '2px 8px',
                fontSize: 10,
                color: style.accent,
                fontWeight: 600,
              }}
            >
              <span>⏱</span>
              {data.duration}
            </div>
          )}
        </div>

        {/* Drag handle dots */}
        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 2,
            paddingTop: 2,
            opacity: 0.3,
          }}
        >
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              style={{
                width: 3,
                height: 3,
                borderRadius: '50%',
                background: '#94a3b8',
              }}
            />
          ))}
        </div>
      </div>

      <Handle
        type="source"
        position={Position.Bottom}
        style={{
          width: 10,
          height: 10,
          background: style.accent,
          border: '2px solid white',
          boxShadow: `0 0 0 1.5px ${style.accent}`,
        }}
      />
    </div>
  )
}