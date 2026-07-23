import type { CSSProperties } from 'react'
import { Handle, Position, NodeResizeControl, useNodeId } from '@xyflow/react'
import '../styles/flow-editor.css'
import { normalizeConditionNodeData, type ConditionNodeData } from '../../../types/recipeFlow'

type ConditionNodeProps = {
  selected: boolean
  style?: CSSProperties
  width?: number
  height?: number
  data: ConditionNodeData
}

const toNumber = (value: unknown, fallback: number) => {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Number.parseFloat(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return fallback
}

export default function ConditionNode({ selected, style: nodeStyle, data, width: nodeWidth, height: nodeHeight }: ConditionNodeProps) {
  const nodeId = useNodeId()
  const normalized = normalizeConditionNodeData(data)
  const condition = normalized.condition
  const width = toNumber(nodeWidth, toNumber(nodeStyle?.width, 160))
  const height = toNumber(nodeHeight, toNumber(nodeStyle?.height, 160))
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
      {selected && (
        <NodeResizeControl
          nodeId={nodeId ?? undefined}
          minWidth={120}
          minHeight={120}
          position="bottom-right"
          style={{
            background: '#d97706',
            border: '2px solid #fff',
            borderRadius: '999px',
            width: 14,
            height: 14,
            boxShadow: '0 0 0 2px rgba(217, 119, 6, 0.25)',
            zIndex: 30,
          }}
        />
      )}
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
          {normalized.title || 'Condition?'}
        </div>
        {condition.notes && (
          <div style={{ fontSize: 9, color: '#b45309', marginTop: 2, lineHeight: 1.2 }}>
            {condition.notes}
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
          left: '50%',
          transform: 'translate(-50%, -50%)',
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
        {condition.successLabel || 'Yes'}
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
        {condition.failureLabel || 'No'}
      </div>

      <div
        style={{
          position: 'absolute',
          bottom: -20,
          left: '50%',
          transform: 'translateX(-50%)',
          fontSize: 9,
          fontWeight: 700,
          color: condition.expectedResult === 'success' ? '#166534' : '#9f1239',
          background: condition.expectedResult === 'success' ? '#dcfce7' : '#ffe4e6',
          border: condition.expectedResult === 'success' ? '1px solid #86efac' : '1px solid #fda4af',
          borderRadius: 999,
          padding: '1px 6px',
          whiteSpace: 'nowrap',
        }}
      >
        Expect: {condition.expectedResult === 'success' ? 'Success' : 'Failure'}
      </div>
    </div>
  )
}