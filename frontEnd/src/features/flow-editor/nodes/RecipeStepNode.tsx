import { useMemo, useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { Handle, Position, NodeResizeControl, useNodeId } from '@xyflow/react'
import '../styles/flow-editor.css'

const defaultStyle = {
  border: '#e5e7eb',
  iconBg: '#f9fafb',
  accent: '#6366f1',
  badge: '#eff6ff',
}

type RecipeStepNodeData = {
  icon: ReactNode
  title: string
  description: string
  duration: string
  stepNumber?: number
}

type RecipeStepNodeProps = {
  selected: boolean
  style?: CSSProperties
  width?: number
  height?: number
  data: RecipeStepNodeData
}

const toNumber = (value: unknown, fallback: number) => {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Number.parseFloat(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return fallback
}

export default function RecipeStepNode({ selected, style: nodeStyle, data, width: nodeWidth, height: nodeHeight }: RecipeStepNodeProps) {
  const style = defaultStyle
  const nodeId = useNodeId()
  const [isDescriptionExpanded, setIsDescriptionExpanded] = useState(false)
  const width = toNumber(nodeWidth, toNumber(nodeStyle?.width, 320))
  const height = toNumber(nodeHeight, toNumber(nodeStyle?.height, 190))
  const description = data.description?.trim() || 'Add step details here.'
  const descriptionLineCount = useMemo(() => description.split(/\r?\n/).length, [description])
  const shouldShowReadMore = description.length > 120 || descriptionLineCount > 4
  const minimumWidth = 260
  const minimumHeight = 190
  const computedWidth = Math.max(width, minimumWidth)
  const computedHeight = Math.max(height, minimumHeight)

  return (
    <div
      style={{
        width: computedWidth,
        minWidth: minimumWidth,
        maxWidth: 520,
        height: computedHeight,
        background: 'white',
        borderRadius: 12,
        border: `1.5px solid ${selected ? style.accent : '#e5e7eb'}`,
        boxShadow: selected
          ? `0 0 0 2px ${style.accent}33, 0 4px 16px rgba(0,0,0,0.08)`
          : '0 1px 6px rgba(0,0,0,0.06)',
        padding: '12px 14px',
        transition: 'all 0.18s ease',
        position: 'relative',
        boxSizing: 'border-box',
        overflow: 'visible',
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
      }}
      className="flow-editor-surface"
    >
      {selected && (
        <NodeResizeControl
          nodeId={nodeId ?? undefined}
          minWidth={minimumWidth}
          minHeight={minimumHeight}
          maxWidth={520}
          maxHeight={640}
          position="bottom-right"
          style={{
            background: style.accent,
            border: '2px solid #fff',
            borderRadius: '999px',
            width: 14,
            height: 14,
            boxShadow: '0 0 0 2px rgba(99, 102, 241, 0.25)',
            zIndex: 30,
          }}
        />
      )}
      <Handle
        type="target"
        position={Position.Top}
        style={{
          width: 10,
          height: 10,
          background: style.accent,
          border: '2px solid white',
          boxShadow: `0 0 0 1.5px ${style.accent}`,
          left: '50%',
          transform: 'translate(-50%, -50%)',
        }}
      />

      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 10,
          minHeight: 36,
          padding: '8px 10px',
          borderRadius: 10,
          background: '#f8fafc',
          border: `1px solid ${style.border}`,
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: 8,
            minWidth: 0,
          }}
        >
          <div
            style={{
              width: 28,
              height: 28,
              borderRadius: 8,
              background: style.iconBg,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: 15,
              flexShrink: 0,
              border: `1px solid ${style.border}`,
            }}
          >
            {data.icon}
          </div>
          <div
            style={{
              minWidth: 0,
            }}
          >
            <div
              style={{
                fontWeight: 700,
                fontSize: 13,
                color: '#1e293b',
                letterSpacing: '-0.01em',
                lineHeight: 1.2,
                overflow: 'hidden',
                textOverflow: 'ellipsis',
                whiteSpace: 'normal',
                display: '-webkit-box',
                WebkitBoxOrient: 'vertical',
                WebkitLineClamp: 2,
                wordBreak: 'break-word',
              }}
            >
              {data.title || 'New Step'}
            </div>
            <div
              style={{
                fontSize: 10,
                color: '#64748b',
                fontWeight: 600,
                marginTop: 2,
              }}
            >
              {data.stepNumber !== undefined ? `Step ${data.stepNumber}` : 'Recipe Step'}
            </div>
          </div>
        </div>

        <div
          style={{
            display: 'flex',
            flexDirection: 'column',
            gap: 2,
            paddingTop: 2,
            opacity: 0.3,
            flexShrink: 0,
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

      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between',
          gap: 8,
          minHeight: 28,
          padding: '0 2px',
        }}
      >
        <div
          style={{
            fontSize: 10,
            fontWeight: 700,
            color: '#64748b',
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
          }}
        >
          Time
        </div>
        <div
          style={{
            display: 'inline-flex',
            alignItems: 'center',
            gap: 4,
            background: style.badge,
            border: `1px solid ${style.border}`,
            borderRadius: 20,
            padding: '4px 10px',
            fontSize: 10,
            color: style.accent,
            fontWeight: 700,
            flexShrink: 0,
          }}
        >
          <span>⏱</span>
          {data.duration?.trim() || 'Not set'}
        </div>
      </div>

      <div
        style={{
          flex: 1,
          minHeight: 78,
          borderRadius: 10,
          border: `1px solid ${style.border}`,
          background: '#ffffff',
          padding: '10px 12px',
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'flex-start',
          overflow: 'hidden',
        }}
      >
        <div
          style={{
            fontSize: 10,
            fontWeight: 700,
            color: '#64748b',
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
            marginBottom: 6,
          }}
        >
          Description
        </div>
        <div
          style={{
            fontSize: 11,
            color: '#475569',
            lineHeight: 1.45,
            overflow: isDescriptionExpanded ? 'auto' : 'hidden',
            whiteSpace: 'pre-wrap',
            display: isDescriptionExpanded ? 'block' : '-webkit-box',
            WebkitBoxOrient: isDescriptionExpanded ? undefined : 'vertical',
            WebkitLineClamp: isDescriptionExpanded ? undefined : 4,
            wordBreak: 'break-word',
            paddingRight: isDescriptionExpanded ? 2 : 0,
          }}
        >
          {description}
        </div>
        {shouldShowReadMore && (
          <button
            type="button"
            onClick={() => setIsDescriptionExpanded((value) => !value)}
            className="nodrag"
            style={{
              marginTop: 8,
              alignSelf: 'flex-start',
              border: 'none',
              background: 'transparent',
              color: style.accent,
              fontSize: 10,
              fontWeight: 700,
              cursor: 'pointer',
              padding: 0,
            }}
          >
            {isDescriptionExpanded ? 'Read less' : 'Read more'}
          </button>
        )}
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
          left: '50%',
          transform: 'translate(-50%, 50%)',
        }}
      />
    </div>
  )
}