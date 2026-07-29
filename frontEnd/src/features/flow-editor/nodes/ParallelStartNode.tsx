import { useCallback, useLayoutEffect } from 'react'
import type { CSSProperties } from 'react'
import { Handle, NodeResizeControl, Position, useNodeId, useUpdateNodeInternals } from '@xyflow/react'
import '../styles/flow-editor.css'
import { normalizeParallelNodeData, type ParallelNodeData } from '../../../types/recipeFlow'

type ParallelStartNodeProps = {
  selected: boolean
  style?: CSSProperties
  width?: number
  height?: number
  data: ParallelNodeData
}

const toNumber = (value: unknown, fallback: number) => {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Number.parseFloat(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return fallback
}

export default function ParallelStartNode({ selected, style: nodeStyle, width: nodeWidth, height: nodeHeight, data }: ParallelStartNodeProps) {
  const nodeId = useNodeId()
  const updateNodeInternals = useUpdateNodeInternals()
  const normalized = normalizeParallelNodeData(data, 'start')
  const width = toNumber(nodeWidth, toNumber(nodeStyle?.width, 180))
  const height = toNumber(nodeHeight, toNumber(nodeStyle?.height, 92))
  const computedWidth = Math.min(Math.max(width, 170), 320)
  const computedMinHeight = Math.max(height, 88)

  const syncNodeLayout = useCallback(() => {
    if (nodeId) {
      updateNodeInternals(nodeId)
    }
  }, [nodeId, updateNodeInternals])

  useLayoutEffect(() => {
    syncNodeLayout()
  }, [computedMinHeight, computedWidth, syncNodeLayout])

  return (
    <div
      style={{
        width: computedWidth,
        minWidth: 170,
        maxWidth: 320,
        minHeight: computedMinHeight,
        background: '#ffffff',
        borderRadius: 12,
        border: `1.5px solid ${selected ? '#7c3aed' : '#ddd6fe'}`,
        boxShadow: selected
          ? '0 0 0 3px rgba(124, 58, 237, 0.18), 0 6px 18px rgba(124, 58, 237, 0.14)'
          : '0 2px 10px rgba(15, 23, 42, 0.08)',
        padding: '10px 12px',
        boxSizing: 'border-box',
        position: 'relative',
        display: 'flex',
        flexDirection: 'column',
        justifyContent: 'center',
        gap: 4,
      }}
      className="flow-editor-surface"
    >
      {selected && (
        <NodeResizeControl
          nodeId={nodeId ?? undefined}
          minWidth={170}
          minHeight={88}
          maxWidth={320}
          maxHeight={240}
          onResize={() => syncNodeLayout()}
          onResizeEnd={() => syncNodeLayout()}
          position="bottom-right"
          style={{
            background: '#7c3aed',
            border: '2px solid #fff',
            borderRadius: '999px',
            width: 14,
            height: 14,
            boxShadow: '0 0 0 2px rgba(124, 58, 237, 0.22)',
            zIndex: 30,
          }}
        />
      )}
      <Handle
        id="parallel-start-in"
        type="target"
        position={Position.Top}
        style={{
          width: 10,
          height: 10,
          background: '#7c3aed',
          border: '2px solid #fff',
          boxShadow: '0 0 0 1.5px #7c3aed',
          left: '50%',
          transform: 'translate(-50%, -50%)',
        }}
      />

      <div style={{ fontSize: 10, fontWeight: 700, color: '#6d28d9', textTransform: 'uppercase', letterSpacing: '0.04em', width: '100%' }}>
        Parallel Start
      </div>
      <div style={{ fontSize: 12, fontWeight: 700, color: '#312e81', lineHeight: 1.25, width: '100%', wordBreak: 'break-word', overflowWrap: 'anywhere' }}>
        {normalized.parallel.label}
      </div>
      {normalized.parallel.notes && (
        <div style={{ fontSize: 10, color: '#6b7280', lineHeight: 1.25, width: '100%', wordBreak: 'break-word', overflowWrap: 'anywhere' }}>
          {normalized.parallel.notes}
        </div>
      )}

      <Handle
        id="parallel-start-out-a"
        type="source"
        position={Position.Bottom}
        style={{
          width: 10,
          height: 10,
          background: '#7c3aed',
          border: '2px solid #fff',
          boxShadow: '0 0 0 1.5px #7c3aed',
          left: '35%',
          transform: 'translate(-50%, 50%)',
        }}
      />

      <Handle
        id="parallel-start-out-b"
        type="source"
        position={Position.Bottom}
        style={{
          width: 10,
          height: 10,
          background: '#7c3aed',
          border: '2px solid #fff',
          boxShadow: '0 0 0 1.5px #7c3aed',
          left: '65%',
          transform: 'translate(-50%, 50%)',
        }}
      />
    </div>
  )
}
