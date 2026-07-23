import type { CSSProperties } from 'react'
import { Handle, Position } from '@xyflow/react'
import '../styles/flow-editor.css'
import { normalizeParallelNodeData, type ParallelNodeData } from '../../../types/recipeFlow'

type ParallelEndNodeProps = {
  selected: boolean
  style?: CSSProperties
  data: ParallelNodeData
}

export default function ParallelEndNode({ selected, data }: ParallelEndNodeProps) {
  const normalized = normalizeParallelNodeData(data, 'end')

  return (
    <div
      style={{
        minWidth: 170,
        maxWidth: 240,
        minHeight: 88,
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
      <Handle
        id="parallel-end-in-a"
        type="target"
        position={Position.Top}
        style={{
          width: 10,
          height: 10,
          background: '#7c3aed',
          border: '2px solid #fff',
          boxShadow: '0 0 0 1.5px #7c3aed',
          left: '35%',
          transform: 'translate(-50%, -50%)',
        }}
      />

      <Handle
        id="parallel-end-in-b"
        type="target"
        position={Position.Top}
        style={{
          width: 10,
          height: 10,
          background: '#7c3aed',
          border: '2px solid #fff',
          boxShadow: '0 0 0 1.5px #7c3aed',
          left: '65%',
          transform: 'translate(-50%, -50%)',
        }}
      />

      <div style={{ fontSize: 10, fontWeight: 700, color: '#6d28d9', textTransform: 'uppercase', letterSpacing: '0.04em' }}>
        Parallel End
      </div>
      <div style={{ fontSize: 12, fontWeight: 700, color: '#312e81', lineHeight: 1.25 }}>
        {normalized.parallel.label}
      </div>
      {normalized.parallel.notes && (
        <div style={{ fontSize: 10, color: '#6b7280', lineHeight: 1.25 }}>
          {normalized.parallel.notes}
        </div>
      )}

      <Handle
        id="parallel-end-out"
        type="source"
        position={Position.Bottom}
        style={{
          width: 10,
          height: 10,
          background: '#7c3aed',
          border: '2px solid #fff',
          boxShadow: '0 0 0 1.5px #7c3aed',
          left: '50%',
          transform: 'translate(-50%, 50%)',
        }}
      />
    </div>
  )
}
