import React from 'react'
import { Handle, Position, NodeResizeControl, useNodeId } from '@xyflow/react'
import '../styles/flow-editor.css'

interface ConditionNodeProps {
  selected: boolean
  style?: React.CSSProperties
  data: {
    title: string
    description?: string
    yesLabel?: string
    noLabel?: string
  }
}

export default function ConditionNode({ selected, style: nodeStyle, data }: ConditionNodeProps) {
  const nodeId = useNodeId()
  const width = typeof nodeStyle?.width === 'number'
    ? nodeStyle.width
    : parseFloat(String(nodeStyle?.width)) || 150
  const height = typeof nodeStyle?.height === 'number'
    ? nodeStyle.height
    : parseFloat(String(nodeStyle?.height)) || 150
  const size = Math.min(width, height)

  return (
    <div
      style={{
        width,
        height,
        position: 'relative',
      }}
      className="flex items-center justify-center"
    >
      <NodeResizeControl
        nodeId={nodeId ?? undefined}
        minWidth={120}
        minHeight={120}
        position="bottom-right"
        style={{
          background: '#d97706',
          border: '2px solid #fff',
          borderRadius: '999px',
          width: 12,
          height: 12,
          zIndex: 20,
        }}
      />
      {/* Diamond shape via rotated square */}
      <div
        style={{
          position: 'absolute',
          width: size * 0.72,
          height: size * 0.72,
          background: selected
            ? 'linear-gradient(135deg, #fef3c7, #fde68a)'
            : 'linear-gradient(135deg, #fffbeb, #fef3c7)',
          border: `2px solid ${selected ? '#d97706' : '#fcd34d'}`,
          borderRadius: 8,
          transform: 'rotate(45deg)',
          boxShadow: selected
            ? '0 0 0 3px #d9770622, 0 4px 16px rgba(217,119,6,0.15)'
            : '0 2px 8px rgba(217,119,6,0.1)',
          transition: 'all 0.18s',
        }}
      />

      {/* Inner label */}
      <div
        style={{
          position: 'relative',
          zIndex: 2,
          textAlign: 'center',
          padding: '0 8px',
          maxWidth: 80,
        }}
      >
        <div style={{ fontSize: 16, marginBottom: 2 }}>🔀</div>
        <div
          style={{
            fontSize: 10,
            fontWeight: 700,
            color: '#92400e',
            lineHeight: 1.2,
            letterSpacing: '-0.01em',
          }}
        >
          {data.title || 'Condition?'}
        </div>
        {data.description && (
          <div style={{ fontSize: 9, color: '#b45309', marginTop: 2, lineHeight: 1.2 }}>
            {data.description}
          </div>
        )}
      </div>

      {/* Handles — Top (incoming), Bottom-right (Yes), Bottom-left (No) */}
      <Handle
        id="condition-target"
        type="target"
        position={Position.Top}
        style={{
          width: 10, height: 10,
          background: '#d97706',
          border: '2px solid white',
          boxShadow: '0 0 0 1.5px #d97706',
          top: 0,
        }}
      />

      {/* YES — right side */}
      <Handle
        id="condition-yes"
        type="source"
        position={Position.Right}
        style={{
          width: 10, height: 10,
          background: '#16a34a',
          border: '2px solid white',
          boxShadow: '0 0 0 1.5px #16a34a',
          right: 0,
        }}
      />

      {/* NO — left side */}
      <Handle
        id="condition-no"
        type="source"
        position={Position.Left}
        style={{
          width: 10, height: 10,
          background: '#dc2626',
          border: '2px solid white',
          boxShadow: '0 0 0 1.5px #dc2626',
          left: 0,
        }}
      />

      {/* YES / NO labels */}
      <div
        style={{
          position: 'absolute',
          right: -28,
          top: '50%',
          transform: 'translateY(-50%)',
          fontSize: 9,
          fontWeight: 700,
          color: '#16a34a',
          background: '#f0fdf4',
          border: '1px solid #86efac',
          borderRadius: 4,
          padding: '1px 4px',
        }}
      >
        {data.yesLabel || 'Yes'}
      </div>
      <div
        style={{
          position: 'absolute',
          left: -26,
          top: '50%',
          transform: 'translateY(-50%)',
          fontSize: 9,
          fontWeight: 700,
          color: '#dc2626',
          background: '#fff5f5',
          border: '1px solid #fca5a5',
          borderRadius: 4,
          padding: '1px 4px',
        }}
      >
        {data.noLabel || 'No'}
      </div>
    </div>
  )
}