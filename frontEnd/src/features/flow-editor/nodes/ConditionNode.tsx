import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { CSSProperties } from 'react'
import { Handle, Position, NodeResizeControl, useNodeId, useUpdateNodeInternals, useReactFlow } from '@xyflow/react'
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
  const updateNodeInternals = useUpdateNodeInternals()
  const { updateNode } = useReactFlow()
  const normalized = normalizeConditionNodeData(data)
  const condition = normalized.condition
  const width = toNumber(nodeWidth, toNumber(nodeStyle?.width, 190))
  const height = toNumber(nodeHeight, toNumber(nodeStyle?.height, 190))
  const size = Math.min(width, height)
  const diamondSize = size * 0.72

  const titleRef = useRef<HTMLDivElement | null>(null)
  const notesRef = useRef<HTMLDivElement | null>(null)
  const collapsedSizeRef = useRef<{ width: number; height: number } | null>(null)
  const [isExpanded, setIsExpanded] = useState(false)
  const [shouldShowReadMore, setShouldShowReadMore] = useState(false)

  // Expanded state grows the whole node (like zooming in on this node only) so clamped text can show in full.
  const contentMaxWidth = isExpanded ? Math.max(120, Math.round(diamondSize * 0.9)) : Math.max(56, Math.round(diamondSize * 0.78))
  const contentPaddingX = Math.max(4, Math.round(diamondSize * 0.06))
  const iconFontSize = Math.max(12, Math.min(28, Math.round(diamondSize * 0.2)))
  const titleFontSize = Math.max(8, Math.min(18, Math.round(diamondSize * 0.12)))
  const notesFontSize = Math.max(7, Math.min(14, Math.round(diamondSize * 0.09)))
  const notesMarginTop = Math.max(1, Math.round(diamondSize * 0.02))

  const checkOverflow = useCallback(() => {
    const titleOverflow = titleRef.current ? titleRef.current.scrollHeight > titleRef.current.clientHeight + 1 : false
    const notesOverflow = notesRef.current ? notesRef.current.scrollHeight > notesRef.current.clientHeight + 1 : false
    setShouldShowReadMore(titleOverflow || notesOverflow)
  }, [])

  const syncNodeLayout = useCallback(() => {
    if (nodeId) {
      updateNodeInternals(nodeId)
    }
    checkOverflow()
  }, [nodeId, updateNodeInternals, checkOverflow])

  useLayoutEffect(() => {
    syncNodeLayout()
  }, [syncNodeLayout, width, height, normalized.title, condition.notes, isExpanded])

  useEffect(() => {
    const titleElement = titleRef.current
    const notesElement = notesRef.current
    if (!titleElement && !notesElement) return

    const observer = new ResizeObserver(() => checkOverflow())
    if (titleElement) observer.observe(titleElement)
    if (notesElement) observer.observe(notesElement)

    return () => observer.disconnect()
  }, [checkOverflow])

  const handleToggleExpand = useCallback(() => {
    if (!nodeId) return

    if (!isExpanded) {
      collapsedSizeRef.current = { width, height }
      const expandedSize = Math.min(Math.max(size * 1.8, 220), 380)
      updateNode(nodeId, { width: expandedSize, height: expandedSize })
      setIsExpanded(true)
    } else {
      const restored = collapsedSizeRef.current ?? { width: 190, height: 190 }
      updateNode(nodeId, { width: restored.width, height: restored.height })
      setIsExpanded(false)
    }

    requestAnimationFrame(() => syncNodeLayout())
  }, [height, isExpanded, nodeId, size, syncNodeLayout, updateNode, width])

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
          minWidth={140}
          minHeight={140}
          keepAspectRatio
          onResize={() => syncNodeLayout()}
          onResizeEnd={() => syncNodeLayout()}
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
          width: diamondSize,
          height: diamondSize,
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
          padding: `0 ${contentPaddingX}px`,
          maxWidth: contentMaxWidth,
        }}
      >
        <div style={{ fontSize: iconFontSize, marginBottom: 2 }}>🔀</div>
        <div
          ref={titleRef}
          style={{
            fontSize: titleFontSize,
            fontWeight: 700,
            color: '#92400e',
            lineHeight: 1.2,
            letterSpacing: '-0.01em',
            display: isExpanded ? 'block' : '-webkit-box',
            WebkitLineClamp: isExpanded ? undefined : 2,
            WebkitBoxOrient: isExpanded ? undefined : 'vertical',
            overflow: isExpanded ? 'visible' : 'hidden',
            textOverflow: isExpanded ? 'clip' : 'ellipsis',
            wordBreak: 'break-word',
          }}
        >
          {normalized.title || 'Condition?'}
        </div>
        {condition.notes && (
          <div
            ref={notesRef}
            style={{
              fontSize: notesFontSize,
              color: '#b45309',
              marginTop: notesMarginTop,
              lineHeight: 1.2,
              display: isExpanded ? 'block' : '-webkit-box',
              WebkitLineClamp: isExpanded ? undefined : 2,
              WebkitBoxOrient: isExpanded ? undefined : 'vertical',
              overflow: isExpanded ? 'visible' : 'hidden',
              textOverflow: isExpanded ? 'clip' : 'ellipsis',
              wordBreak: 'break-word',
            }}
          >
            {condition.notes}
          </div>
        )}
        {(shouldShowReadMore || isExpanded) && (
          <button
            type="button"
            onClick={handleToggleExpand}
            className="nodrag"
            style={{
              marginTop: notesMarginTop,
              border: 'none',
              background: 'transparent',
              color: '#b45309',
              fontSize: Math.max(8, notesFontSize - 1),
              fontWeight: 700,
              cursor: 'pointer',
              padding: 0,
              textDecoration: 'underline',
            }}
          >
            {isExpanded ? 'Collapse' : 'Read more'}
          </button>
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