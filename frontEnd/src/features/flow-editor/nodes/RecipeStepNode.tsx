import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import type { CSSProperties, ReactNode } from 'react'
import { Handle, Position, NodeResizeControl, useNodeId, useUpdateNodeInternals } from '@xyflow/react'
import '../styles/flow-editor.css'
import {
  getStepIngredientName,
  normalizeStepNodeData,
  type RecipeStepNodeData,
} from '../../../types/recipeFlow'
import { getVisibleStepDetailRows } from '../catalog/stepActionPresentation'

const defaultStyle = {
  border: '#e5e7eb',
  iconBg: '#f9fafb',
  accent: '#6366f1',
  badge: '#eff6ff',
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
  const updateNodeInternals = useUpdateNodeInternals()
  const containerRef = useRef<HTMLDivElement | null>(null)
  const notesRef = useRef<HTMLDivElement | null>(null)
  const [isNotesExpanded, setIsNotesExpanded] = useState(false)
  const [shouldShowReadMore, setShouldShowReadMore] = useState(false)
  const normalized = normalizeStepNodeData(data)
  const step = normalized.step
  const width = toNumber(nodeWidth, toNumber(nodeStyle?.width, 320))
  const height = toNumber(nodeHeight, toNumber(nodeStyle?.height, 190))
  const notes = step.notes.trim() || 'Add notes for this step.'
  const ingredientName = getStepIngredientName(step).trim()
  const ingredientSummary = [step.quantity.trim(), step.unit.trim(), ingredientName, step.specification.trim()]
    .filter(Boolean)
    .join(' ')
  const detailRows = getVisibleStepDetailRows(step)
  const minimumWidth = 260
  const minimumHeight = 190
  const computedWidth = Math.min(Math.max(width, minimumWidth), 520)
  const computedMinHeight = Math.max(height, minimumHeight)
  const scale = Math.min(Math.max(Math.sqrt((computedWidth * computedMinHeight) / (320 * 190)), 0.75), 1.6)
  const iconBoxSize = Math.round(28 * scale)
  const iconFontSize = Math.max(11, Math.round(15 * scale))
  const titleFontSize = Math.max(11, Math.round(13 * scale))
  const stepLabelFontSize = Math.max(9, Math.round(10 * scale))
  const sectionHeadingFontSize = Math.max(9, Math.round(10 * scale))
  const ingredientFontSize = Math.max(10, Math.round(11 * scale))
  const detailRowFontSize = Math.max(9, Math.round(10 * scale))
  const notesFontSize = Math.max(9, Math.round(10 * scale))
  const readMoreFontSize = Math.max(9, Math.round(10 * scale))
  const cardPaddingY = Math.round(12 * scale)
  const cardPaddingX = Math.round(14 * scale)
  const cardGap = Math.round(10 * scale)
  const headerMinHeight = Math.round(36 * scale)
  const headerPaddingY = Math.round(8 * scale)
  const headerPaddingX = Math.round(10 * scale)
  const headerIconGap = Math.round(8 * scale)
  const detailsMinHeight = Math.round(78 * scale)
  const detailsPaddingY = Math.round(10 * scale)
  const detailsPaddingX = Math.round(12 * scale)

  const syncNodeLayout = useCallback(() => {
    if (nodeId) {
      updateNodeInternals(nodeId)
    }

    const noteElement = notesRef.current
    if (!noteElement) {
      setShouldShowReadMore(false)
      return
    }

    setShouldShowReadMore(noteElement.scrollHeight > noteElement.clientHeight + 1)
  }, [nodeId, updateNodeInternals])

  useLayoutEffect(() => {
    syncNodeLayout()
  }, [syncNodeLayout, computedWidth, computedMinHeight, notes, detailRows.length])

  useEffect(() => {
    const containerElement = containerRef.current
    if (!containerElement) return

    const observer = new ResizeObserver(() => {
      syncNodeLayout()
    })

    observer.observe(containerElement)
    if (notesRef.current) {
      observer.observe(notesRef.current)
    }

    return () => observer.disconnect()
  }, [syncNodeLayout])

  return (
    <div
      ref={containerRef}
      style={{
        width: computedWidth,
        minWidth: minimumWidth,
        maxWidth: 520,
        minHeight: computedMinHeight,
        background: 'white',
        borderRadius: 12,
        border: `1.5px solid ${selected ? style.accent : '#e5e7eb'}`,
        boxShadow: selected
          ? `0 0 0 2px ${style.accent}33, 0 4px 16px rgba(0,0,0,0.08)`
          : '0 2px 10px rgba(15, 23, 42, 0.08)',
        padding: `${cardPaddingY}px ${cardPaddingX}px`,
        transition: 'all 0.18s ease',
        position: 'relative',
        boxSizing: 'border-box',
        overflow: 'visible',
        display: 'flex',
        flexDirection: 'column',
        gap: cardGap,
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
          keepAspectRatio
          onResize={() => syncNodeLayout()}
          onResizeEnd={() => syncNodeLayout()}
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
          gap: cardGap,
          minHeight: headerMinHeight,
          padding: `${headerPaddingY}px ${headerPaddingX}px`,
          borderRadius: 10,
          background: '#f8fafc',
          border: `1px solid ${style.border}`,
        }}
      >
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            gap: headerIconGap,
            minWidth: 0,
            flex: 1,
          }}
        >
          <div
            style={{
              width: iconBoxSize,
              height: iconBoxSize,
              borderRadius: 8,
              background: style.iconBg,
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
              fontSize: iconFontSize,
              flexShrink: 0,
              border: `1px solid ${style.border}`,
            }}
          >
            {(normalized.icon as ReactNode) || 'S'}
          </div>
          <div
            style={{
              minWidth: 0,
              flex: 1,
              width: '100%',
            }}
          >
            <div
              style={{
                fontWeight: 700,
                fontSize: titleFontSize,
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
                overflowWrap: 'anywhere',
                width: '100%',
              }}
            >
              {normalized.title || 'Select Action'}
            </div>
            <div
              style={{
                fontSize: stepLabelFontSize,
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
          width: '100%',
          minHeight: detailsMinHeight,
          borderRadius: 10,
          border: `1px solid ${style.border}`,
          background: '#ffffff',
          padding: `${detailsPaddingY}px ${detailsPaddingX}px`,
          display: 'flex',
          flexDirection: 'column',
          justifyContent: 'flex-start',
          gap: 4,
        }}
      >
        <div
          style={{
            fontSize: sectionHeadingFontSize,
            fontWeight: 700,
            color: '#64748b',
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
            marginBottom: 6,
          }}
        >
          Details
        </div>
        {ingredientSummary && (
          <div
            style={{
              fontSize: ingredientFontSize,
              color: '#334155',
              lineHeight: 1.35,
              marginBottom: 6,
            }}
          >
            {ingredientSummary}
          </div>
        )}
        {detailRows.map((row) => (
          <div
            key={row.key}
            style={{
              fontSize: detailRowFontSize,
              color: '#64748b',
              lineHeight: 1.35,
            }}
          >
            {row.label}: {row.value}
          </div>
        ))}
        <div
          style={{
            fontSize: sectionHeadingFontSize,
            fontWeight: 700,
            color: '#64748b',
            letterSpacing: '0.04em',
            textTransform: 'uppercase',
            marginTop: detailRows.length > 0 || ingredientSummary ? 4 : 0,
            marginBottom: 4,
          }}
        >
          Notes
        </div>
        <div
          ref={notesRef}
          style={{
            fontSize: notesFontSize,
            color: '#475569',
            lineHeight: 1.45,
            overflow: 'hidden',
            whiteSpace: 'pre-wrap',
            display: isNotesExpanded ? 'block' : '-webkit-box',
            WebkitBoxOrient: isNotesExpanded ? undefined : 'vertical',
            WebkitLineClamp: isNotesExpanded ? undefined : 4,
            wordBreak: 'break-word',
            overflowWrap: 'anywhere',
            width: '100%',
          }}
        >
          {notes}
        </div>
        {(shouldShowReadMore || isNotesExpanded) && (
          <button
            type="button"
            onClick={() => setIsNotesExpanded((value) => !value)}
            className="nodrag"
            style={{
              marginTop: 8,
              alignSelf: 'flex-start',
              border: 'none',
              background: 'transparent',
              color: style.accent,
              fontSize: readMoreFontSize,
              fontWeight: 700,
              cursor: 'pointer',
              padding: 0,
            }}
          >
            {isNotesExpanded ? 'Read less' : 'Read more'}
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