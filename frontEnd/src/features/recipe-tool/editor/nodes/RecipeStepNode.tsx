import { useCallback, useLayoutEffect } from 'react'
import type { CSSProperties } from 'react'
import { Handle, Position, NodeResizeControl, useNodeId, useUpdateNodeInternals } from '@xyflow/react'
import '../../../../styles/flow-ui.css'
import {
  annotatedIngredientText,
  getRecipeStepActionName,
  getRecipeStepActionSummary,
  getRecipeStepDurationLabel,
  getRecipeStepRepeatIntervalLabel,
  getRecipeStepTemperatureLabel,
  getRecipeStepTitle,
  getStepAnnotationTargets,
  normalizeRecipeStepNodeData,
  stepAnnotationLabelResolver,
  type RecipeStepActionOnProcess,
  type RecipeStepNodeData,
} from '../../model/recipeStepData'
import { parsePreparedAnnotationId, sameAnnotationTarget, type Annotation } from '../../model/stepAnnotations'
import AnnotatedText from '../components/annotations/AnnotatedText'
import { ImageZoomButton } from '../../../../shared/components/image-viewer/ZoomableImage'
import type { ChipState } from '../components/annotations/AnnotatedTextEditor'
import { useNodeHandleSides, useRecipeProcessGraphContext } from '../context/RecipeProcessGraphContext'
import { DEFAULT_NODE_SIZE } from '../../model/recipeNodeTypes'
import { getStepOutputLabel, getStepOutputReferenceProblem } from '../../model/recipeStepOutputs'
import { describePreparedReference } from '../../model/preparationFlows'
import { getStepActionById, type ActionCategory, type StepActionId } from '../../../../shared/catalog/actionCatalog'
import { getFlameLevelDisplayName } from '../../../../shared/catalog/flameLevelCatalog'

type CategoryTheme = {
  emoji: string
  accent: string
  accentStrong: string
  soft: string
  border: string
}

// Per-action-category colors of a recipe step node, keyed by the catalog's actionCategories ids.
// Related categories share a palette (all heat-based cooking is orange, all prep is violet, …).
const HANDLING = { accent: '#2563eb', accentStrong: '#1d4ed8', soft: '#eff6ff', border: '#bfdbfe' }
const PREPARATION = { accent: '#7c3aed', accentStrong: '#6d28d9', soft: '#f5f3ff', border: '#ddd6fe' }
const COOKING = { accent: '#ea580c', accentStrong: '#c2410c', soft: '#fff7ed', border: '#fed7aa' }
const MIXING = { accent: '#0d9488', accentStrong: '#0f766e', soft: '#f0fdfa', border: '#99f6e4' }
const WAITING = { accent: '#64748b', accentStrong: '#475569', soft: '#f1f5f9', border: '#e2e8f0' }
const FINISHING = { accent: '#16a34a', accentStrong: '#15803d', soft: '#f0fdf4', border: '#bbf7d0' }
const NEUTRAL = { accent: '#94a3b8', accentStrong: '#64748b', soft: '#f8fafc', border: '#e2e8f0' }

const CATEGORY_THEMES: Record<ActionCategory, CategoryTheme> = {
  'handling': { emoji: '🧺', ...HANDLING },
  'cleaning': { emoji: '🧼', ...PREPARATION },
  'cutting': { emoji: '🔪', ...PREPARATION },
  'mixing': { emoji: '🥣', ...MIXING },
  'coating': { emoji: '🧂', ...MIXING },
  'heating': { emoji: '♨️', ...COOKING },
  'moist-heat': { emoji: '🍲', ...COOKING },
  'frying': { emoji: '🍳', ...COOKING },
  'dry-heat': { emoji: '🔥', ...COOKING },
  'sauce': { emoji: '🥄', ...COOKING },
  'dough': { emoji: '🥖', ...MIXING },
  'finishing': { emoji: '🍽️', ...FINISHING },
  'preservation': { emoji: '🫙', ...WAITING },
  'control': { emoji: '⏳', ...WAITING },
  'custom': { emoji: '✨', ...NEUTRAL },
}

const DEFAULT_THEME: CategoryTheme = { emoji: '🍳', accent: '#94a3b8', accentStrong: '#64748b', soft: '#f8fafc', border: '#e2e8f0' }

const getCategoryTheme = (action: StepActionId | ''): CategoryTheme => {
  if (!action) return DEFAULT_THEME
  return CATEGORY_THEMES[getStepActionById(action).category] ?? DEFAULT_THEME
}

const toNumber = (value: unknown, fallback: number) => {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string') {
    const parsed = Number.parseFloat(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return fallback
}

type RecipeStepNodeProps = {
  selected: boolean
  style?: CSSProperties
  width?: number
  height?: number
  data: RecipeStepNodeData
}

// Resizing is bounded below only — a card must stay readable, but may grow as large as wanted.
const MIN_WIDTH = 240
/** A titled section of the card (Description, Expected): always present, so every card has the same shape. */
const SECTION_STYLE: CSSProperties = { display: 'flex', flexDirection: 'column', gap: 3, borderTop: '1px dashed var(--flow-border)', paddingTop: 6 }
const SECTION_HEADING_STYLE: CSSProperties = { fontSize: 9, fontWeight: 800, color: 'var(--flow-text)', textTransform: 'uppercase', letterSpacing: '0.03em' }
const NOT_PROVIDED_STYLE: CSSProperties = { fontSize: 10, color: 'var(--flow-text-subtle)', fontStyle: 'italic' }

/** Smallest card height; a card is otherwise exactly as tall as its content. */
const MIN_HEIGHT = 60

const BORDER_WIDTH = 1.5
const ACCENT_BORDER_WIDTH = 5
// A handle's `left: 50%` is measured inside the borders, and the accent border makes the left one
// thicker — shift back by half the difference so top/bottom handles sit on the card's true center.
const HANDLE_CENTER_LEFT = `calc(50% - ${(ACCENT_BORDER_WIDTH - BORDER_WIDTH) / 2}px)`

/**
 * Where a handle sits for each side it can be on (see processLayout.ts's getHandleSides): centered
 * on that edge of the card. A left handle is pulled out over the thick accent border so it sits as
 * close to the card's outer edge as the other sides' handles do.
 */
const HANDLE_PLACEMENT: Record<'top' | 'left' | 'right' | 'bottom', { position: Position; style: CSSProperties }> = {
  top: { position: Position.Top, style: { top: 0, left: HANDLE_CENTER_LEFT, transform: 'translate(-50%, -50%)' } },
  bottom: { position: Position.Bottom, style: { bottom: 0, left: HANDLE_CENTER_LEFT, transform: 'translate(-50%, 50%)' } },
  left: { position: Position.Left, style: { top: '50%', left: -(ACCENT_BORDER_WIDTH - BORDER_WIDTH), transform: 'translate(-50%, -50%)' } },
  right: { position: Position.Right, style: { top: '50%', right: 0, transform: 'translate(50%, -50%)' } },
}

export default function RecipeStepNode({ selected, style: nodeStyle, width: nodeWidth, data }: RecipeStepNodeProps) {
  const nodeId = useNodeId()
  const updateNodeInternals = useUpdateNodeInternals()
  const { recipeProcesses, stepOrder, stepOutputGraph, onNodeResizeStart, onNodeResizeEnd, onOpenPrepared, highlightedNodeId } = useRecipeProcessGraphContext()
  const normalized = normalizeRecipeStepNodeData(data)
  const step = normalized.step
  const theme = getCategoryTheme(step.action)
  const handleSides = useNodeHandleSides(nodeId)
  const width = Math.max(toNumber(nodeWidth, toNumber(nodeStyle?.width, DEFAULT_NODE_SIZE.step.width)), MIN_WIDTH)
  // Height follows the content (description, badges, image); only the width is set by resizing.
  const minHeight = MIN_HEIGHT

  const stepIndex = nodeId ? stepOrder.indexOf(nodeId) : -1
  const stepNumber = stepIndex >= 0 ? stepIndex + 1 : data.stepNumber

  const imageUrl = normalized.visualization?.imageUrl
  // The image sits to the left of Action On/Description, sized off the card's own width rather
  // than a rigid fraction — object-fit: contain (not cover) so the whole generated image is always
  // visible, never cropped; a slightly wider-than-tall box keeps letterboxing minimal for typical
  // landscape generations without cropping portrait/square ones either.
  const imageBoxWidth = imageUrl ? Math.round(Math.min(170, Math.max(96, width * 0.36))) : 0
  const imageBoxHeight = Math.round(imageBoxWidth * 0.8)

  // Keeps the edge handles anchored correctly on the resized box — same pattern as
  // RecipeStepNode/RecipeConditionNode's own resize handling.
  const syncNodeLayout = useCallback(() => {
    if (nodeId) updateNodeInternals(nodeId)
  }, [nodeId, updateNodeInternals])

  // React Flow hands this component the node's *measured* size, which lags the resized wrapper by a
  // render — so the handle bounds it measured during the resize describe the old box. Re-measure
  // once this render has laid the card out at its new size, or edges keep pointing at the old center.
  useLayoutEffect(() => {
    syncNodeLayout()
  }, [syncNodeLayout, width, minHeight, imageUrl, handleSides.target, handleSides.source])

  // A used prepared component shows its *current* name: the Preparation step's live Expected Output.
  const preparedName = (reference: RecipeStepActionOnProcess) => describePreparedReference(recipeProcesses, reference).label
  const names = { preparedName, stepOutputLabel: (stepId: string) => getStepOutputLabel(stepOutputGraph, stepId) }
  const title = getRecipeStepTitle(step, names)
  const actionSummary = getRecipeStepActionSummary(step, names)
  const resolveLabel = stepAnnotationLabelResolver(names)
  const targets = getStepAnnotationTargets(step)

  const chipState = (annotation: Annotation): ChipState => {
    if (!targets[annotation.kind].some((id) => sameAnnotationTarget(annotation, annotation.kind, id))) return 'missing'
    if (annotation.kind === 'prepared') {
      const reference = parsePreparedAnnotationId(annotation.id)
      return reference && !describePreparedReference(recipeProcesses, reference).problem ? 'ok' : 'missing'
    }
    if (annotation.kind === 'step') return nodeId && getStepOutputReferenceProblem(stepOutputGraph, annotation.id, nodeId) ? 'missing' : 'ok'
    return 'ok'
  }
  const openPreparedAnnotation = (annotation: Annotation) => {
    const reference = parsePreparedAnnotationId(annotation.id)
    if (reference) onOpenPrepared?.(reference)
  }

  // Ingredient chips read with their amount ("2 onions"), taken from Action On — the card's only place for it.
  const chipText = (annotation: Annotation, words: string) =>
    (annotation.kind === 'ingredient' ? annotatedIngredientText(step, annotation.id, words) : null)

  const flameLevel = getFlameLevelDisplayName(step.flameLevelId, step.customFlameLevel)
  const duration = getRecipeStepDurationLabel(step)
  const temperature = getRecipeStepTemperatureLabel(step)
  const repeatInterval = getRecipeStepRepeatIntervalLabel(step)

  const detailBadges = [
    temperature && { icon: '🌡️', label: temperature },
    flameLevel && { icon: '🔥', label: flameLevel },
    duration && { icon: '⏱️', label: duration },
    repeatInterval && { icon: '🔁', label: repeatInterval },
  ].filter((entry): entry is { icon: string; label: string } => Boolean(entry))

  return (
    <div
      style={{
        width,
        minWidth: MIN_WIDTH,
        minHeight,
        background: 'var(--flow-surface)',
        borderRadius: 14,
        borderTop: `${BORDER_WIDTH}px solid ${selected ? theme.accent : 'var(--flow-border)'}`,
        borderRight: `${BORDER_WIDTH}px solid ${selected ? theme.accent : 'var(--flow-border)'}`,
        borderBottom: `${BORDER_WIDTH}px solid ${selected ? theme.accent : 'var(--flow-border)'}`,
        borderLeft: `${ACCENT_BORDER_WIDTH}px solid ${theme.accent}`,
        boxShadow: selected
          ? `0 0 0 3px ${theme.accent}26, 0 8px 20px rgba(15, 23, 42, 0.12)`
          : '0 2px 10px rgba(15, 23, 42, 0.08)',
        padding: '12px 14px',
        boxSizing: 'border-box',
        display: 'flex',
        flexDirection: 'column',
        gap: 8,
        position: 'relative',
      }}
      className={`flow-editor-surface process-step-node${highlightedNodeId != null && highlightedNodeId === nodeId ? ' step-node-highlight' : ''}`}
    >
      {selected && (
        <NodeResizeControl
          nodeId={nodeId ?? undefined}
          minWidth={MIN_WIDTH}
          minHeight={MIN_HEIGHT}
          resizeDirection="horizontal"
          onResizeStart={() => { if (nodeId) onNodeResizeStart?.(nodeId) }}
          onResize={() => syncNodeLayout()}
          onResizeEnd={() => {
            syncNodeLayout()
            if (nodeId) onNodeResizeEnd?.(nodeId)
          }}
          position="right"
          style={{
            background: theme.accent,
            border: '2px solid #fff',
            borderRadius: '999px',
            width: 14,
            height: 14,
            boxShadow: `0 0 0 2px ${theme.accent}40`,
            zIndex: 30,
          }}
        />
      )}
      <Handle
        type="target"
        position={HANDLE_PLACEMENT[handleSides.target].position}
        style={{ width: 10, height: 10, background: theme.accent, border: '2px solid white', boxShadow: `0 0 0 1.5px ${theme.accent}`, zIndex: 20, ...HANDLE_PLACEMENT[handleSides.target].style }}
      />

      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <div
          style={{
            width: 24, height: 24, borderRadius: '50%', flexShrink: 0,
            background: `linear-gradient(135deg, ${theme.accent}, ${theme.accentStrong})`,
            display: 'flex', alignItems: 'center', justifyContent: 'center',
            fontSize: 12, fontWeight: 800, color: 'white', boxShadow: `0 2px 6px ${theme.accent}55`,
          }}
          aria-hidden
        >
          {stepNumber ?? theme.emoji}
        </div>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ fontWeight: 700, fontSize: 13, color: theme.accentStrong, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
            {title}
          </div>
          <div
            style={{ fontSize: 9.5, color: 'var(--flow-text-subtle)', fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}
            title={actionSummary}
          >
            {stepNumber != null ? `Step ${stepNumber}` : 'Step'}
            {step.action && ` · ${getRecipeStepActionName(step)}`}
          </div>
        </div>
      </div>

      {/* Image (when generated) on the left — object-fit: contain so the whole image is always
          visible, never cropped — with Action On + Description alongside it on the right, up to
          some point; Expected Output is deliberately pulled out below, full width (see further down). */}
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start' }}>
        {imageUrl && (
          <div
            style={{
              position: 'relative', width: imageBoxWidth, height: imageBoxHeight, borderRadius: 10, overflow: 'hidden', flexShrink: 0,
              border: `1px solid ${theme.border}`, background: '#0f172a',
            }}
          >
            <img src={imageUrl} alt={title} style={{ width: '100%', height: '100%', objectFit: 'contain', display: 'block' }} />
            {/* Clicking the card selects/drags the node, so the full-size view gets its own button. */}
            <ImageZoomButton className="nodrag nopan" src={imageUrl} alt={title} caption={title} />
          </div>
        )}

        <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', gap: 6 }}>
          {detailBadges.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
              {detailBadges.map((badge) => (
                <span key={badge.icon} style={{ fontSize: 10.5, fontWeight: 700, padding: '2px 7px', borderRadius: 999, background: 'var(--flow-surface-muted)', color: 'var(--flow-text-muted)', border: '1px solid var(--flow-border)' }}>
                  {badge.icon} {badge.label}
                </span>
              ))}
            </div>
          )}

          {/* Description — always shown, like Expected below, so every card has the same shape. */}
          <div style={SECTION_STYLE}>
            <span style={SECTION_HEADING_STYLE}>Description</span>
            {step.actionDescription.trim() ? (
              <AnnotatedText
                text={step.actionDescription.trim()}
                resolveLabel={resolveLabel}
                chipState={chipState}
                chipText={chipText}
                onOpenPrepared={openPreparedAnnotation}
                style={{ fontSize: 10.5, color: 'var(--flow-text-muted)', lineHeight: 1.55 }}
              />
            ) : (
              <span style={NOT_PROVIDED_STYLE}>Not provided</span>
            )}
          </div>
        </div>
      </div>

      {/* Expected Output — pulled out of the image/text row so it always reads full width; always shown. */}
      <div style={SECTION_STYLE}>
        <span style={SECTION_HEADING_STYLE}>Expected</span>
        {step.expectedOutput.trim() ? (
          <span style={{ fontSize: 10, color: 'var(--flow-text-muted)', lineHeight: 1.4, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
            {step.expectedOutput.trim()}
          </span>
        ) : (
          <span style={NOT_PROVIDED_STYLE}>Not provided</span>
        )}
      </div>

      <Handle
        type="source"
        position={HANDLE_PLACEMENT[handleSides.source].position}
        style={{ width: 10, height: 10, background: theme.accent, border: '2px solid white', boxShadow: `0 0 0 1.5px ${theme.accent}`, zIndex: 20, ...HANDLE_PLACEMENT[handleSides.source].style }}
      />
    </div>
  )
}
