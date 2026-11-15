import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import type { Dispatch, ReactNode, SetStateAction } from 'react'
import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  NodeToolbar,
  Position,
  useNodesState,
  useEdgesState,
  addEdge,
  BackgroundVariant,
  PanOnScrollMode,
  type ReactFlowInstance,
  type Node,
  type Edge,
  type Connection,
  type IsValidConnection,
  type NodeChange,
  type EdgeChange,
  type Viewport,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'

import { recipeNodeComponents } from '../nodes/recipeNodeComponents'
import {
  CONDITION_HANDLES,
  DEFAULT_NODE_SIZE,
  RECIPE_NODE_TYPES,
  isRecipeConditionNode,
  isRecipeStepNode,
  type RecipeNodeType,
} from '../../model/recipeNodeTypes'
import {
  createConditionNode,
  createFlowDataPayload,
  flowToProcess,
  getFlowEdgePresentation,
  normalizeFlowEdges,
  normalizeFlowNode,
  applyConditionFieldUpdate,
} from '../adapters/canvasFlow'
import { normalizeConditionNodeData } from '../../model/recipeConditionData'
import { type FlowViewport } from '../model/canvasGraph'
import { ROW_LAYOUT, arrangeFlowGrid, arrangeRows, findLoopEdges, getHandleSides, planFlowColumns, planRows, type RowPlan } from '../model/processLayout'
import { describePreparedReference, getPreparationFlows } from '../../model/preparationFlows'
import { useRecipeReadiness } from '../../session/useRecipeReadiness'
import { findPreparedOutputUses, withoutSubprocessReferences } from '../../model/subprocessReferences'
import { findFreePosition } from '../adapters/recipeProcessEditGraph'
import { getProcessReadingOrder } from '../../model/processReadingOrder'
import { buildStepOutputGraph } from '../../model/recipeStepOutputs'
import {
  buildProcessUpdateRequest,
  createRecipeStepNode,
  processToFlowData,
} from '../adapters/recipeProcessCanvasAdapter'
import {
  applyRecipeStepFieldUpdate,
  normalizeRecipeStepNodeData,
  withRecipeStepActionOnIngredients,
  withRecipeStepActionOnProcesses,
  withRecipeStepActionOnSteps,
  withoutAnnotationsOfRemovedTargets,
  type RecipeStepNodeData,
  withRecipeStepVisualization,
  type ActionOnIngredient,
  type RecipeStepActionOnProcess,
} from '../../model/recipeStepData'
import { RecipeProcessGraphProvider } from '../context/RecipeProcessGraphContext'
import { useRecipeSession, type RecipeSessionContextValue } from '../../session/sessionContext'
import type { Process } from '../../../../types/process'
import type { RecipeProcessBreadcrumbEntry, RecipeProcessVisualizationStepResult } from '../../../../types/recipe'
import { useNotifications } from '../../../../shared/components/notifications/notificationContext'
import { useConfirm } from '../../../../shared/components/confirm/confirmContext'
import RecipeConditionPanel from './RecipeConditionPanel'
import RecipeStepPanel from './RecipeStepPanel'
import RecipeProcessTopBar, { type NodeSelectorOption } from './RecipeProcessTopBar'
import AddNodeMenu from './AddNodeMenu'
import type { RecipeAiActions } from './AiActionsMenu'
import { ProcessSidebarActionsProvider, type ProcessSidebarActions } from '../context/ProcessSidebarActionsContext'
import RecipeVisualizationSlideshow from '../../walkthrough/RecipeVisualizationSlideshow'
import ExportProcessModal from './ExportProcessModal'
import { exportRecipeFile, type ExportedRecipeFile } from '../../session/recipeFile'
import { errorMessage } from '../../../../shared/errors'
import { startPanelResize } from './panelResize'
import { useCanvasShortcuts } from '../hooks/useCanvasShortcuts'
import EmptyCanvasPrompt from './EmptyCanvasPrompt'
import ShortcutHelpDialog from './ShortcutHelpDialog'
import { useProcessVisuals } from '../hooks/useProcessVisuals'
import { ModelChoiceDialog } from '../../workflow/components/RunModelPicker'
import type { RunModelCapability } from '../../../../types/recipeAiWorkflow'
import { buildCanvasSlideshowSteps } from '../model/canvasSlideshow'
import { describeNodeDeletion } from '../model/nodeDeletion'
import '../../../../styles/flow-ui.css'
import '../../styles/RecipePropertiesPanel.css'
import '../styles/RecipeProcessCanvas.css'
import '../../styles/modals.css'

type RecipeProcessCanvasProps = {
  recipeId: number
  processId: number
  /** Ancestors from the recipe's process list down to this process's parent, root-first (empty for MAIN or a directly-opened process). */
  breadcrumbAncestors: RecipeProcessBreadcrumbEntry[]
  onNavigateToList: () => void
  onNavigateToAncestor: (index: number) => void
  /** Called when the user chooses to open a subprocess referenced from a STEP's Action On (see RecipeStepPanel's "Open" button) — a Process graph never contains a node for this, so there's no double-click-to-open path anymore. */
  onOpenSubprocess: (subprocessId: number, currentProcessName: string, stepId?: string) => void
  onBack?: () => void
  /** The recipe's name for the top bar's breadcrumb — omitted where it isn't known (standalone route). */
  recipeName?: string
  /** Recipe-level AI actions for the top bar's AI menu (see RecipeEditorView) — omitted on the standalone route. */
  aiActions?: RecipeAiActions
  /**
   * The Recipe Tool's process list (see RecipeEditorView), rendered as this component's whole left
   * column when present; it gets the canvas's add-node actions (its "+ Add" menu) and collapsing
   * through ProcessSidebarActionsContext. Omitted for the standalone `/process/:processId` route,
   * where there's no sibling process list; that route has no left column at all (the canvas takes
   * the full width, and "+ Add" moves into the top bar instead).
   */
  sidebarHeader?: ReactNode
  /** Reports the selected node (null when none) — the AI edit dialog resolves "this step" with it. */
  onSelectedNodeChange?: (nodeId: string | null) => void
  /** Nodes to briefly highlight (e.g. the ones an AI edit just changed); display only, never saved. */
  highlightedNodeIds?: ReadonlySet<string>
  /** Selects this node once it's on the canvas (e.g. a readiness issue the user clicked); a new token repeats it. */
  focusNodeRequest?: { nodeId: string; token: number } | null
}

// Default sizes of a new node (see createRecipeStepNode/createConditionNode).
const NEW_STEP_SIZE = DEFAULT_NODE_SIZE.step
/** Generate Visuals: the text model writes the image prompts, the image model draws them. */
const VISUALS_MODELS: RunModelCapability[] = ['TEXT_TO_TEXT', 'TEXT_TO_IMAGE']
/** Size of the step popup (see .flow-step-popup) and its gap to the node — for placing it where it fits. */
const STEP_POPUP_WIDTH = 420
const STEP_POPUP_HEIGHT = 540
const STEP_POPUP_GAP = 14
const NEW_CONDITION_SIZE = DEFAULT_NODE_SIZE.condition
/** Free space kept around a newly added node, so it never lands touching another one. */
const NEW_NODE_CLEARANCE = 24

/** Below this zoom, node text gets hard to read — the initial view never zooms out further than this. */
const READABLE_MIN_ZOOM = 0.6
/** Loop edges run this far out from their ends, clear of the cards they connect. */
const LOOP_EDGE_OFFSET = 32
/** Frames Auto Arrange waits at most for resized cards to be re-measured before placing them anyway. */
const ARRANGE_MAX_WAIT_FRAMES = 20

/** An Auto Arrange waiting for re-measured cards: rows for MAIN, or (Preparation) each flow's node ids. */
type PendingArrange = { plan: RowPlan; order: string[]; flows?: string[][] }

/** A node's rendered size: what React Flow measured, falling back to its stored size. */
const nodeSize = (node: Node) => ({
  width: node.measured?.width ?? node.width ?? NEW_STEP_SIZE.width,
  height: node.measured?.height ?? node.height ?? NEW_STEP_SIZE.height,
})
const nodeBox = (node: Node) => ({ x: node.position.x, y: node.position.y, ...nodeSize(node) })

/** JSON of exactly what a save would send for this graph — equal strings mean nothing worth saving changed. */
const graphPayloadJson = (flowNodes: Node[], flowEdges: Edge[]) => {
  const { nodes, edges } = buildProcessUpdateRequest('', undefined, createFlowDataPayload(flowNodes, flowEdges))
  return JSON.stringify([nodes, edges])
}

/**
 * A recipe is one unified editing session — MAIN and every SUBPROCESS are
 * views of the same in-memory snapshot (RecipeSessionContext), not
 * independent flows. This outer component owns only what should genuinely
 * survive a process switch: the resizable panel layout (widths/collapsed
 * state). Everything that is legitimately *per-process* — the canvas's
 * nodes/edges, selection, zoom/viewport — lives in RecipeProcessCanvasContent
 * below, which is deliberately remounted (via `key`) whenever `processId`
 * changes. Undo/redo is recipe-wide and lives in the session itself, so it
 * survives process switches and remounts.
 *
 * This is *not* the same kind of remount the Recipe Tool used to do: that
 * remounted this whole component (topbar, panels, layout *and* data) and
 * re-fetched the process from the backend, which is what lost unsaved edits
 * and made switching feel like opening a separate flow. Here, the process's
 * data was already loaded once into RecipeSessionContext (no fetch on
 * switch), every edit is continuously pushed into that shared session as it
 * happens (not only on unmount), and only the small, genuinely per-process
 * slice of local UI state (selection, zoom) resets. Switching back to a
 * previously-edited process re-seeds straight from the session, so its
 * unsaved changes are still there.
 */
export default function RecipeProcessCanvas(props: RecipeProcessCanvasProps) {
  const session = useRecipeSession()
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false)
  const [sidebarWidth, setSidebarWidth] = useState(260)
  const [propsCollapsed, setPropsCollapsed] = useState(true)
  const [propsWidth, setPropsWidth] = useState(280)

  if (!session || session.loading) {
    return (
      <div className="flex h-full w-full items-center justify-center" style={{ color: 'var(--flow-text-muted)' }}>
        Loading process…
      </div>
    )
  }

  // A save may have just given a pending (temporary-id) process its real id; follow it, so the open
  // canvas keeps showing (and writing to) the same process until the selection catches up.
  const processId = session.getProcess(props.processId) ? props.processId : session.resolveProcessId(props.processId)
  const process = session.getProcess(processId)

  if (session.loadError || !process) {
    return (
      <div className="flex h-full w-full flex-col items-center justify-center gap-3" style={{ color: 'var(--flow-text-muted)' }}>
        <div>{session.loadError ?? 'This process could not be loaded.'}</div>
        {props.onBack && (
          <button onClick={props.onBack} style={{ padding: '8px 14px', borderRadius: 8, border: '1px solid var(--flow-border)', background: 'var(--flow-surface)', cursor: 'pointer' }}>
            ← Back
          </button>
        )}
      </div>
    )
  }

  const recipeProcesses = session.getProcesses()

  return (
    <RecipeProcessCanvasContent
      key={processId}
      {...props}
      processId={processId}
      process={process}
      recipeProcesses={recipeProcesses}
      session={session}
      sidebarCollapsed={sidebarCollapsed}
      setSidebarCollapsed={setSidebarCollapsed}
      sidebarWidth={sidebarWidth}
      setSidebarWidth={setSidebarWidth}
      propsCollapsed={propsCollapsed}
      setPropsCollapsed={setPropsCollapsed}
      propsWidth={propsWidth}
      setPropsWidth={setPropsWidth}
    />
  )
}

type RecipeProcessCanvasContentProps = RecipeProcessCanvasProps & {
  process: Process
  recipeProcesses: Process[]
  session: RecipeSessionContextValue
  sidebarCollapsed: boolean
  setSidebarCollapsed: Dispatch<SetStateAction<boolean>>
  sidebarWidth: number
  setSidebarWidth: Dispatch<SetStateAction<number>>
  propsCollapsed: boolean
  setPropsCollapsed: Dispatch<SetStateAction<boolean>>
  propsWidth: number
  setPropsWidth: Dispatch<SetStateAction<number>>
}

function RecipeProcessCanvasContent({
  recipeId,
  processId,
  process,
  recipeProcesses,
  session,
  breadcrumbAncestors,
  onNavigateToList,
  onNavigateToAncestor,
  onOpenSubprocess,
  onBack,
  recipeName,
  aiActions,
  sidebarHeader,
  onSelectedNodeChange,
  highlightedNodeIds,
  focusNodeRequest,
  sidebarCollapsed,
  setSidebarCollapsed,
  sidebarWidth,
  setSidebarWidth,
  propsCollapsed,
  setPropsCollapsed,
  propsWidth,
  setPropsWidth,
}: RecipeProcessCanvasContentProps) {
  const { notifySuccess, notifyError } = useNotifications()

  // Seeded once, synchronously, from the already-loaded session — no fetch, no loading flash.
  // Recomputed on every render of this instance, but only the *first* render's value is actually
  // used (React ignores later arguments to useNodesState/useEdgesState) since this whole component
  // remounts fresh (via `key={processId}` in RecipeProcessCanvas above) whenever the selected process
  // changes, rather than resetting this state in place.
  const initialFlowData = processToFlowData(process)
  const [nodes, setNodes, onNodesChange] = useNodesState(initialFlowData.nodes.map(normalizeFlowNode))
  const [edges, setEdges, onEdgesChange] = useEdgesState(normalizeFlowEdges(initialFlowData.edges))
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [selectedEdgeId, setSelectedEdgeId] = useState<string | null>(null)
  const [zoomPercent, setZoomPercent] = useState(Math.round((initialFlowData.viewport?.zoom ?? 1) * 100))
  const [exportedFile, setExportedFile] = useState<ExportedRecipeFile | null>(null)
  const [showSlideshow, setShowSlideshow] = useState(false)
  // Narration is generated from the *saved* step text, so opening the slideshow first flushes any
  // pending autosave; the slideshow waits on this before asking the backend for narration.
  const [narrationReady, setNarrationReady] = useState<Promise<boolean> | null>(null)
  const openSlideshow = useCallback(() => {
    setNarrationReady(session.saveNow().then((result) => result.ok, () => false))
    setShowSlideshow(true)
  }, [session])
  const reactFlowInstance = useRef<ReactFlowInstance<Node, Edge> | null>(null)
  const currentViewportRef = useRef<FlowViewport | null>(initialFlowData.viewport ?? null)
  const sidebarRef = useRef<HTMLDivElement | null>(null)
  const propsRef = useRef<HTMLDivElement | null>(null)
  const bodyRef = useRef<HTMLDivElement | null>(null)
  const reactFlowWrapperRef = useRef<HTMLDivElement | null>(null)

  // Always-current mirrors of `nodes`/`edges`, for callbacks that must read the latest state without
  // being recreated on every nodes/edges change (drag-start/resize-start snapshotting below fires
  // from gesture callbacks that are handed to React Flow / a node component once, not re-subscribed
  // every render).
  const nodesRef = useRef(nodes)
  const edgesRef = useRef(edges)
  useEffect(() => { nodesRef.current = nodes }, [nodes])
  useEffect(() => { edgesRef.current = edges }, [edges])

  /**
   * Frames the graph for reading, like a page: its width fits the canvas (never zoomed in past 100%,
   * never out past a readable zoom) and its *start* is at the top — a long recipe continues a scroll
   * away instead of shrinking to fit. An arranged graph fills the width exactly at 100%.
   */
  const showInitialView = useCallback((duration: number) => {
    const instance = reactFlowInstance.current
    const wrapper = reactFlowWrapperRef.current
    const current = nodesRef.current
    if (!instance || !wrapper || current.length === 0) return
    const { padding } = ROW_LAYOUT
    const bounds = instance.getNodesBounds(current)
    const roomWidth = wrapper.clientWidth - 2 * padding
    const zoom = Math.max(READABLE_MIN_ZOOM, Math.min(1, roomWidth / bounds.width))
    const x = bounds.width * zoom <= roomWidth
      ? wrapper.clientWidth / 2 - (bounds.x + bounds.width / 2) * zoom
      : padding - bounds.x * zoom
    void instance.setViewport({ x, y: padding - bounds.y * zoom, zoom }, { duration })
  }, [])

  const [flowReady, setFlowReady] = useState(false)
  // The initial view is applied once the nodes it should frame are rendered: on open when the
  // process has no saved viewport, and after Auto Arrange. A saved viewport is always respected as-is.
  const pendingInitialViewRef = useRef<{ duration: number } | null>(initialFlowData.viewport ? null : { duration: 0 })
  // A freshly AI-generated process is arranged to this screen's width once, as soon as it's shown:
  // opened while still unsaved (a temporary negative id, see recipeProcessGenerationConverter.ts), or
  // re-seeded with wholly new content (the AI regenerating an existing MAIN — see the re-seed below).
  // A saved process is never re-arranged on open.
  const pendingAutoArrangeRef = useRef(processId < 0 && initialFlowData.nodes.length > 0)

  // The canvas itself must never be resized away to nothing — every panel resizer caps its own
  // width against how much room the *other* panel + this floor are currently taking up, on top of
  // its own static max.
  const MIN_CANVAS_WIDTH = 280
  const getMaxPanelWidth = useCallback((staticMax: number, otherPanelWidth: number, gaps: number) => {
    const containerWidth = bodyRef.current?.offsetWidth ?? window.innerWidth
    const dynamicMax = containerWidth - otherPanelWidth - gaps - MIN_CANVAS_WIDTH
    return Math.min(staticMax, dynamicMax)
  }, [])

  useEffect(() => {
    onSelectedNodeChange?.(selectedNodeId)
  }, [selectedNodeId, onSelectedNodeChange])

  // Highlighting is applied only to what React Flow renders, so it never reaches the session or a save.
  const displayedNodes = useMemo(() => (highlightedNodeIds && highlightedNodeIds.size > 0
    ? nodes.map((node) => (highlightedNodeIds.has(node.id)
      ? { ...node, className: [node.className, 'flow-node-ai-changed'].filter(Boolean).join(' ') }
      : node))
    : nodes), [nodes, highlightedNodeIds])

  // Loop edges ("not done yet → repeat") are drawn dashed and routed out away from the cards, so they
  // read as loops rather than as the way forward. Display only, like the highlight above.
  const loopEdgeIds = useMemo(() => findLoopEdges(nodes, edges), [nodes, edges])
  const displayedEdges = useMemo(() => (loopEdgeIds.size === 0
    ? edges
    : edges.map((edge) => (loopEdgeIds.has(edge.id)
      ? { ...edge, pathOptions: { offset: LOOP_EDGE_OFFSET }, style: { ...edge.style, strokeDasharray: '6 4' } }
      : edge))), [edges, loopEdgeIds])

  // Which side each node's handles sit on, from where its neighbours are (model/processLayout.ts):
  // along a row the flow runs left to right, and from a row's end down to the next row's start.
  // Follows manual dragging too; never stored.
  const handleSides = useMemo(
    () => getHandleSides(nodes.map((node) => ({ id: node.id, ...nodeBox(node) })), edges, loopEdgeIds),
    [nodes, edges, loopEdgeIds],
  )

  // Auto-collapse the properties panel when nothing is selected, expand it when a node is selected,
  // so the rail only takes up space while it has something to show.
  // A STEP opens its own editor on the node (the step popup), so only checks open the panel by themselves.
  const selectedIsStep = nodes.some((node) => node.id === selectedNodeId && isRecipeStepNode(node))
  useEffect(() => {
    setPropsCollapsed(selectedNodeId == null || selectedIsStep)
  }, [selectedNodeId, selectedIsStep, setPropsCollapsed])

  // Publishes this process's nodes/edges into the shared recipe session on every *real* change —
  // not just on Save — so switching to a different process (or the Recipe Process view) always sees
  // this process's latest edits, and autosave persists them. "Real" is decided on exactly what a save
  // would send (the payload's JSON), so selection, re-measuring the same sizes, or re-seeding never
  // marks the recipe unsaved — robust under StrictMode's double-invoked effects too, unlike a
  // "skip the first run" flag. Not pushed mid-gesture (each drag/resize tick): the gesture's end
  // pushes once (see the drag/resize handlers below).
  const [seededGraphJson] = useState(() => graphPayloadJson(nodes, edges))
  const syncedGraphJsonRef = useRef(seededGraphJson)
  const resizeSnapshotRef = useRef<{ nodeId: string; before: Process[]; width?: number; height?: number } | null>(null)
  const pushToSession = useCallback((flowNodes: Node[], flowEdges: Edge[]) => {
    const json = graphPayloadJson(flowNodes, flowEdges)
    if (json === syncedGraphJsonRef.current) return
    syncedGraphJsonRef.current = json
    const flowData = createFlowDataPayload(flowNodes, flowEdges, currentViewportRef.current ?? undefined)
    const { nodes: processNodes, edges: processEdges } = buildProcessUpdateRequest(process.name, process.description, flowData)
    session.updateProcess(processId, { nodes: processNodes ?? [], edges: processEdges ?? [] })
  }, [session, processId, process.name, process.description])
  useEffect(() => {
    if (resizeSnapshotRef.current || nodes.some((node) => node.dragging)) return
    pushToSession(nodes, edges)
  }, [nodes, edges, pushToSession])

  // Re-seeds the canvas in place when the session's content changed from outside it — undo/redo,
  // recovering local changes, AI generation replacing this process, or a save assigning real ids
  // to processes this one references. Keeps the current selection where the node still exists.
  const { subscribeReseed, getProcess } = session
  useEffect(() => subscribeReseed(() => {
    const latest = getProcess(processId)
    if (!latest) return
    const flowData = processToFlowData(latest)
    const nextNodes = flowData.nodes.map(normalizeFlowNode)
    const nextEdges = normalizeFlowEdges(flowData.edges)
    // Wholly new content with no viewport of its own (an AI generation replacing this process):
    // arrange it to this screen once it's rendered, which also frames it.
    const currentIds = new Set(nodesRef.current.map((node) => node.id))
    if (!flowData.viewport && nextNodes.length > 0 && !nextNodes.some((node) => currentIds.has(node.id))) {
      pendingAutoArrangeRef.current = true
    }
    syncedGraphJsonRef.current = graphPayloadJson(nextNodes, nextEdges)
    setNodes((current) => {
      const selected = new Set(current.filter((node) => node.selected).map((node) => node.id))
      return nextNodes.map((node) => (selected.has(node.id) ? { ...node, selected: true } : node))
    })
    setEdges(nextEdges)
    setSelectedNodeId((current) => (current != null && nextNodes.some((node) => node.id === current) ? current : null))
    setSelectedEdgeId((current) => (current != null && nextEdges.some((edge) => edge.id === current) ? current : null))
  }), [subscribeReseed, getProcess, processId, setNodes, setEdges])

  /** Records an undo step for a structural change (add/delete/duplicate node, connect/remove edge) — call before applying it. */
  const pushHistorySnapshot = useCallback(() => {
    session.recordHistory({ focusProcessId: processId })
  }, [session, processId])

  /** Field edits: one undo step per typing burst in one field, not one per keystroke. */
  const recordFieldEdit = useCallback((nodeId: string, field: string) => {
    session.recordHistory({ focusProcessId: processId, coalesceKey: `${processId}:${nodeId}:${field}` })
  }, [session, processId])

  const undo = session.undo
  const redo = session.redo

  // Node move (drag) and resize each get their own start/end snapshot pair below instead — a
  // `remove` is the only structural node change left to catch here (add/connect/delete-edge already
  // snapshot explicitly at their own call sites).
  const handleNodesChange = useCallback((changes: NodeChange<Node>[]) => {
    if (changes.some((change) => change.type === 'remove')) pushHistorySnapshot()
    onNodesChange(changes)
  }, [onNodesChange, pushHistorySnapshot])

  const handleEdgesChange = useCallback((changes: EdgeChange<Edge>[]) => {
    if (changes.some((change) => change.type === 'remove')) pushHistorySnapshot()
    onEdgesChange(changes)
  }, [onEdgesChange, pushHistorySnapshot])

  /**
   * One undo entry per completed drag, not per mouse-move: `onNodeDragStart`/`onNodeDragStop` are
   * React Flow's own start/end pair for a node-move gesture (unlike position NodeChange events,
   * which fire continuously with `dragging: true` while the mouse moves). The session snapshot from
   * the gesture's start becomes the undo step, and only if the node actually ended up elsewhere.
   */
  const dragSnapshotRef = useRef<{ nodeId: string; before: Process[]; x: number; y: number } | null>(null)
  const handleNodeDragStart = useCallback((_event: unknown, node: Node) => {
    dragSnapshotRef.current = { nodeId: node.id, before: session.getProcesses(), x: node.position.x, y: node.position.y }
  }, [session])
  const handleNodeDragStop = useCallback((_event: unknown, node: Node) => {
    const snapshot = dragSnapshotRef.current
    dragSnapshotRef.current = null
    if (!snapshot || snapshot.nodeId !== node.id) return
    if (snapshot.x === node.position.x && snapshot.y === node.position.y) return
    session.recordHistory({ before: snapshot.before, focusProcessId: processId })
  }, [session, processId])

  /**
   * Same one-entry-per-gesture treatment for resize, driven by RecipeProcessGraphContext's
   * onNodeResizeStart/onNodeResizeEnd (NodeResizeControl's own start/end callbacks live inside each
   * node component, not here — see RecipeStepNode.tsx/RecipeConditionNode.tsx) rather than the
   * `dimensions` NodeChange stream, which fires on every intermediate resize tick. Session pushes
   * are paused in between, so the end pushes the final size once.
   */
  const handleNodeResizeStart = useCallback((nodeId: string) => {
    const node = nodesRef.current.find((n) => n.id === nodeId)
    resizeSnapshotRef.current = {
      nodeId,
      before: session.getProcesses(),
      width: node?.width ?? node?.measured?.width,
      height: node?.height ?? node?.measured?.height,
    }
  }, [session])
  const handleNodeResizeEnd = useCallback((nodeId: string) => {
    const snapshot = resizeSnapshotRef.current
    resizeSnapshotRef.current = null
    if (!snapshot || snapshot.nodeId !== nodeId) return
    const after = nodesRef.current.find((n) => n.id === nodeId)
    const resized = snapshot.width !== (after?.width ?? after?.measured?.width) || snapshot.height !== (after?.height ?? after?.measured?.height)
    if (resized) session.recordHistory({ before: snapshot.before, focusProcessId: processId })
    pushToSession(nodesRef.current, edgesRef.current)
  }, [session, processId, pushToSession])

  /**
   * Where a new node goes: centered one layer below the selected node (or the lowest node when
   * nothing is selected) — where the next step of the flow would be — moved further down past any
   * node already there, so it never lands on top of, or touching, another one.
   */
  const newNodePosition = useCallback((size: { width: number; height: number }) => {
    const current = nodesRef.current
    const anchor = current.find((node) => node.id === selectedNodeId)
      ?? current.reduce<Node | undefined>((lowest, node) => {
        if (!lowest) return node
        const bottom = nodeBox(node)
        const lowestBottom = nodeBox(lowest)
        return bottom.y + bottom.height > lowestBottom.y + lowestBottom.height ? node : lowest
      }, undefined)
    if (!anchor) return { x: 420, y: 140 }
    const box = nodeBox(anchor)
    const preferred = { x: Math.round(box.x + box.width / 2 - size.width / 2), y: Math.round(box.y + box.height + ROW_LAYOUT.rowGap) }
    const free = findFreePosition(
      current,
      { x: preferred.x - NEW_NODE_CLEARANCE, y: preferred.y - NEW_NODE_CLEARANCE },
      { width: size.width + 2 * NEW_NODE_CLEARANCE, height: size.height + 2 * NEW_NODE_CLEARANCE },
    )
    return { x: free.x + NEW_NODE_CLEARANCE, y: free.y + NEW_NODE_CLEARANCE }
  }, [selectedNodeId])

  const addNode = useCallback((nodeType: RecipeNodeType) => {
    pushHistorySnapshot()
    const id = crypto.randomUUID()
    const isCondition = nodeType === RECIPE_NODE_TYPES.condition
    const position = newNodePosition(isCondition ? NEW_CONDITION_SIZE : NEW_STEP_SIZE)
    const newNode = isCondition ? createConditionNode(id, position) : createRecipeStepNode(id, position)

    setNodes((nds) => [...nds, newNode])
    setSelectedNodeId(id)
    setSelectedEdgeId(null)
  }, [newNodePosition, setNodes, pushHistorySnapshot])

  /**
   * Auto Arrange: re-lays the process out in rows that fill the canvas width (model/processLayout.ts),
   * in reading order, resizing every card to its cell. Two phases, because a card's height depends on
   * its width: (1) cards take the cell width and their natural height; (2) once the browser has
   * re-measured them, rows are placed, each as tall as its tallest card. Node data and edges are never
   * touched. Recorded as one undo step (`recordUndo`), so Undo restores the previous layout and sizes;
   * an AI generation's own arrange is left out of history, folded into the generation's step.
   */
  const pendingArrangeRef = useRef<PendingArrange | null>(null)

  /** Phase 2 — checked once per frame until the resized cards have been re-measured (or it's waited long enough). */
  const finishArrange = useCallback((pending: PendingArrange) => {
    const attempt = (frame: number) => {
      const instance = reactFlowInstance.current
      if (pendingArrangeRef.current !== pending || !instance) return
      const remeasured = nodesRef.current
        .filter(isRecipeStepNode)
        .every((node) => Math.abs((instance.getInternalNode(node.id)?.measured?.width ?? 0) - pending.plan.cellWidth) <= 1)
      if (!remeasured && frame < ARRANGE_MAX_WAIT_FRAMES) requestAnimationFrame(() => attempt(frame + 1))
      else placeRows(instance)
    }
    const placeRows = (instance: ReactFlowInstance<Node, Edge>) => {
      pendingArrangeRef.current = null
      const current = nodesRef.current
      const measuredOf = (node: Node) => instance.getInternalNode(node.id)?.measured

      const byId = new Map(current.map((node) => [node.id, node]))
      const entries = (ids: string[]) => ids
        .map((id) => byId.get(id))
        .filter((node): node is Node => node != null)
        .map((node) => ({
          id: node.id,
          kind: isRecipeConditionNode(node) ? 'CONDITION' : 'STEP',
          height: measuredOf(node)?.height ?? nodeSize(node).height,
        }))
      const unordered = current.map((node) => node.id).filter((id) => !pending.order.includes(id))
      // The Preparation: each flow in its own column, flows side by side and wrapping (processLayout.ts).
      const layout = pending.flows
        ? arrangeFlowGrid([...pending.flows.map(entries), ...unordered.map((id) => entries([id]))], pending.plan)
        : arrangeRows(entries([...pending.order, ...unordered]), pending.plan)
      pendingInitialViewRef.current = { duration: 300 }
      setNodes((nds) => nds.map((node) => {
        const placed = layout.get(node.id)
        if (!placed) return node
        return { ...node, position: { x: placed.x, y: placed.y }, width: placed.width, height: placed.height }
      }))
    }
    attempt(0)
  }, [setNodes])

  const startArrange = useCallback((recordUndo: boolean) => {
    const wrapper = reactFlowWrapperRef.current
    const current = nodesRef.current
    if (!wrapper || current.length === 0) return
    const isPreparation = process.type === 'SUBPROCESS'
    const availableWidth = wrapper.clientWidth - 2 * ROW_LAYOUT.padding
    const plan = isPreparation ? planFlowColumns(availableWidth) : planRows(availableWidth)
    const asProcess = flowToProcess(process, current, edgesRef.current)
    const order = getProcessReadingOrder(asProcess).map((node) => node.id)
    // Each flow in reading order, the flows ordered by where their first node reads.
    const flows = isPreparation
      ? getPreparationFlows(asProcess)
        .map((flow) => order.filter((id) => flow.nodes.some((node) => node.id === id)))
        .sort((a, b) => order.indexOf(a[0]) - order.indexOf(b[0]))
      : undefined
    if (recordUndo) pushHistorySnapshot()
    const pending: PendingArrange = { plan, order, flows }
    pendingArrangeRef.current = pending
    // Phase 1: the cell width, and no fixed height (nor a leftover fixed `style` size) so each card
    // takes its natural content height at that width.
    setNodes((nds) => nds.map((node) => (isRecipeStepNode(node)
      ? { ...node, width: plan.cellWidth, height: undefined, style: { ...node.style, width: undefined, height: undefined } }
      : node)))
    requestAnimationFrame(() => finishArrange(pending))
  }, [process, setNodes, pushHistorySnapshot, finishArrange])

  const autoArrange = useCallback(() => startArrange(true), [startArrange])

  // Runs whatever is waiting for the canvas to be ready and the latest nodes to be rendered: an
  // AI-generated process's one-time arrange (which frames the view itself), else the initial view.
  useEffect(() => {
    if (!flowReady) return
    if (pendingAutoArrangeRef.current) {
      pendingAutoArrangeRef.current = false
      pendingInitialViewRef.current = null
      startArrange(false)
      return
    }
    const pending = pendingInitialViewRef.current
    if (!pending) return
    pendingInitialViewRef.current = null
    showInitialView(pending.duration)
  }, [nodes, flowReady, startArrange, showInitialView])

  const deleteNode = useCallback((id: string) => {
    pushHistorySnapshot()
    // Also drops other steps' step-output references to the deleted node, which would otherwise
    // point at nothing (and fail backend validation on save).
    setNodes((nds) => nds
      .filter((node) => node.id !== id)
      .map((node) => {
        if (!isRecipeStepNode(node)) return node
        const steps = normalizeRecipeStepNodeData(node.data).step.actionOn.steps
        if (!steps.some((entry) => entry.stepId === id)) return node
        return { ...node, data: withRecipeStepActionOnSteps(node.data, steps.map((entry) => entry.stepId).filter((stepId) => stepId !== id)) }
      }))
    setEdges((eds) => eds.filter((edge) => edge.source !== id && edge.target !== id))
    setSelectedNodeId((current) => (current === id ? null : current))
    // A Preparation step's output may be used by main steps: those uses go too (same undo step — the
    // snapshot above is of the whole recipe).
    if (process.type === 'SUBPROCESS') {
      session.getProcesses().forEach((other) => {
        if (other.id === processId) return
        const stripped = withoutSubprocessReferences(other, processId, id)
        if (stripped !== other) session.addProcess(stripped)
      })
    }
  }, [setNodes, setEdges, pushHistorySnapshot, process.type, processId, session])

  /**
   * Every way of deleting a node (the panels' Delete, the top bar's 🗑, the Delete/Backspace keys)
   * asks first, saying what goes with it. A second request while the dialog is open (e.g. holding
   * Delete) is ignored rather than stacking dialogs.
   */
  const confirm = useConfirm()
  const confirmingDeleteRef = useRef(false)
  const requestDeleteNode = useCallback(async (id: string) => {
    if (confirmingDeleteRef.current) return
    const usedByMainSteps = process.type === 'SUBPROCESS'
      ? findPreparedOutputUses(session.getProcesses().filter((other) => other.id !== processId), processId, id).length
      : 0
    const options = describeNodeDeletion(nodesRef.current, edgesRef.current, id, usedByMainSteps)
    if (!options) return

    confirmingDeleteRef.current = true
    try {
      if (await confirm(options)) deleteNode(id)
    } finally {
      confirmingDeleteRef.current = false
    }
  }, [confirm, deleteNode, process.type, processId, session])

  const deleteEdge = useCallback((id: string) => {
    pushHistorySnapshot()
    setEdges((eds) => eds.filter((edge) => edge.id !== id))
    setSelectedEdgeId((current) => (current === id ? null : current))
  }, [setEdges, pushHistorySnapshot])

  // A connection is deleted without asking: it carries no content of its own, is redrawn with one
  // drag, and Undo brings it back.
  const deleteSelected = useCallback(() => {
    if (selectedNodeId) void requestDeleteNode(selectedNodeId)
    else if (selectedEdgeId) deleteEdge(selectedEdgeId)
  }, [selectedNodeId, selectedEdgeId, requestDeleteNode, deleteEdge])

  const duplicateNode = useCallback((id: string) => {
    pushHistorySnapshot()
    setNodes((nds) => {
      const original = nds.find((node) => node.id === id)
      if (!original) return nds
      return [
        ...nds,
        {
          ...structuredClone(original),
          id: crypto.randomUUID(),
          selected: false,
          position: { x: original.position.x + 24, y: original.position.y + 24 },
        },
      ]
    })
  }, [setNodes, pushHistorySnapshot])

  const updateNodeField = useCallback((nodeId: string, field: string, value: string) => {
    recordFieldEdit(nodeId, field)
    setNodes((nds) => nds.map((node) => (node.id === nodeId ? applyConditionFieldUpdate(node, field, value) : node)))

    if (field === 'condition.successLabel' || field === 'condition.failureLabel') {
      const sourceHandle = field === 'condition.successLabel' ? CONDITION_HANDLES.yes : CONDITION_HANDLES.no
      const fallbackLabel = field === 'condition.successLabel' ? 'Yes' : 'No'
      setEdges((eds) => eds.map((edge) =>
        edge.source === nodeId && edge.sourceHandle === sourceHandle
          ? { ...edge, label: value.trim() || fallbackLabel }
          : edge))
    }
  }, [setNodes, setEdges, recordFieldEdit])

  const updateRecipeStepField = useCallback((nodeId: string, field: string, value: string) => {
    recordFieldEdit(nodeId, field)
    setNodes((nds) => nds.map((node) => (node.id === nodeId ? { ...node, data: applyRecipeStepFieldUpdate(node.data, field, value) } : node)))
  }, [setNodes, recordFieldEdit])

  /** One undoable change of a step's data (e.g. its description together with the target an annotation adds). */
  const updateStepData = useCallback((nodeId: string, coalesceField: string, update: (data: unknown) => RecipeStepNodeData) => {
    recordFieldEdit(nodeId, coalesceField)
    setNodes((nds) => nds.map((node) => (node.id === nodeId ? { ...node, data: update(node.data) } : node)))
  }, [setNodes, recordFieldEdit])

  // Removing a target in the editor turns the description's annotations of it back into plain words.
  const updateActionOnIngredients = useCallback((nodeId: string, ingredients: ActionOnIngredient[]) => {
    updateStepData(nodeId, 'actionOn.ingredients', (data) =>
      withoutAnnotationsOfRemovedTargets(normalizeRecipeStepNodeData(data).step, withRecipeStepActionOnIngredients(data, ingredients)))
  }, [updateStepData])

  const updateActionOnProcesses = useCallback((nodeId: string, references: RecipeStepActionOnProcess[]) => {
    updateStepData(nodeId, 'actionOn.processes', (data) =>
      withoutAnnotationsOfRemovedTargets(normalizeRecipeStepNodeData(data).step, withRecipeStepActionOnProcesses(data, references)))
  }, [updateStepData])

  const updateActionOnSteps = useCallback((nodeId: string, stepIds: string[]) => {
    updateStepData(nodeId, 'actionOn.steps', (data) =>
      withoutAnnotationsOfRemovedTargets(normalizeRecipeStepNodeData(data).step, withRecipeStepActionOnSteps(data, stepIds)))
  }, [updateStepData])

  // Which earlier steps' Expected Outputs each step may reference, and their live labels — derived
  // from the current nodes/edges (see recipeStepOutputs.ts), never stored.
  const stepOutputGraph = useMemo(() => buildStepOutputGraph(nodes, edges), [nodes, edges])

  const isValidConnection = useCallback<IsValidConnection<Edge>>((connectionOrEdge) => {
    const source = connectionOrEdge.source
    const target = connectionOrEdge.target
    if (!source || !target || source === target) return false

    const duplicate = edges.some((edge) =>
      edge.source === source &&
      edge.target === target &&
      (edge.sourceHandle ?? null) === (connectionOrEdge.sourceHandle ?? null) &&
      (edge.targetHandle ?? null) === (connectionOrEdge.targetHandle ?? null))

    return !duplicate
  }, [edges])

  const onConnect = useCallback((connection: Connection) => {
    pushHistorySnapshot()
    const sourceNode = nodes.find((node) => node.id === connection.source)
    let label: string | undefined

    if (sourceNode && isRecipeConditionNode(sourceNode)) {
      const condition = normalizeConditionNodeData(sourceNode.data).condition
      if (connection.sourceHandle === CONDITION_HANDLES.yes) label = condition.successLabel
      if (connection.sourceHandle === CONDITION_HANDLES.no) label = condition.failureLabel
    }

    const presentation = getFlowEdgePresentation(connection.sourceHandle, connection.targetHandle, label)
    setEdges((eds) => addEdge({ ...connection, id: crypto.randomUUID(), ...presentation }, eds))
  }, [nodes, setEdges, pushHistorySnapshot])

  const handleZoomIn = useCallback(() => {
    reactFlowInstance.current?.zoomIn()
  }, [])

  const handleZoomOut = useCallback(() => {
    reactFlowInstance.current?.zoomOut()
  }, [])

  const handleFitView = useCallback(() => {
    reactFlowInstance.current?.fitView({ padding: 0.12 })
  }, [])

  const handleViewportMove = useCallback((_event: unknown, viewport: Viewport) => {
    currentViewportRef.current = viewport
    setZoomPercent(Math.round(viewport.zoom * 100))
  }, [])

  // Suppressed exactly once: when this process had no saved viewport, the initial view (see
  // showInitialView above) is applied on mount and fires one onMoveEnd of its own — that
  // shouldn't itself mark a freshly-opened, otherwise-untouched process dirty. Every onMoveEnd after
  // that (drag, scroll-zoom, pinch, or the topbar's Zoom/Fit View buttons) is a real view change and
  // is pushed into the session so it survives a process switch and is included in the next Save —
  // see the module-level architecture note on why pure pan/zoom was previously silently lost.
  const suppressNextMoveEndRef = useRef(initialFlowData.viewport == null)
  const handleViewportMoveEnd = useCallback((_event: unknown, viewport: Viewport) => {
    currentViewportRef.current = viewport
    setZoomPercent(Math.round(viewport.zoom * 100))
    if (suppressNextMoveEndRef.current) {
      suppressNextMoveEndRef.current = false
      return
    }
    session.updateProcess(processId, { viewport: { x: viewport.x, y: viewport.y, zoom: viewport.zoom } })
  }, [session, processId])

  // The whole recipe as a file another user can import: saved first, so the file holds what is on screen
  // and the narration listed for it belongs to the saved steps.
  const handleExport = useCallback(async () => {
    try {
      await session.saveNow()
      setExportedFile(await exportRecipeFile(recipeId, session.getProcesses()))
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to export this recipe right now'))
    }
  }, [session, recipeId, notifyError])

  const buildSlideshowSteps = () => buildCanvasSlideshowSteps(process, nodes, edges, recipeProcesses, stepOutputGraph)

  const applyStepVisualization = useCallback((stepResult: RecipeProcessVisualizationStepResult) => {
    if (!stepResult.success || stepResult.visualizationAssetId == null) return
    setNodes((nds) => nds.map((node) => {
      if (node.id !== stepResult.stepId || !isRecipeStepNode(node)) return node
      return {
        ...node,
        data: withRecipeStepVisualization(node.data, {
          assetId: stepResult.visualizationAssetId,
          imageUrl: stepResult.imageUrl ?? undefined,
          status: 'generated',
        }),
      }
    }))
  }, [setNodes])

  // The Preparation gets one image per flow (attached to its output step); every other process one per step.
  const isPreparationProcess = process.type === 'SUBPROCESS'
  const visuals = useProcessVisuals({
    recipeId,
    processId,
    stepCount: isPreparationProcess
      ? getPreparationFlows(flowToProcess(process, nodes, edges)).filter((flow) => flow.output != null).length
      : nodes.filter(isRecipeStepNode).length,
    unit: isPreparationProcess ? 'flows' : 'steps',
    applyStep: applyStepVisualization,
  })
  // Generating visuals spends image credits: the user picks the models first (ModelChoiceDialog below).
  const [visualsModelsOpen, setVisualsModelsOpen] = useState(false)
  const askForVisualsModels = useCallback(() => {
    if (!visuals.generating) setVisualsModelsOpen(true)
  }, [visuals.generating])

  /**
   * Guards actually leaving via onBack. Pending edits are simply saved on the way out (autosave
   * flush); only when saving is currently failing is there anything to confirm — and even then the
   * edits stay on this device and are offered back when the recipe is reopened.
   */
  const confirmNavigatingAway = useCallback(() => {
    const { status } = session.saveState
    if (status !== 'failed' && status !== 'conflict') return true
    return window.confirm('Your latest changes could not be saved yet. They are kept on this device and will be offered back when you reopen this recipe. Leave anyway?')
  }, [session])

  /** Called from RecipeStepPanel's "Open" button on a referenced subprocess — the only way to open a subprocess from inside a process's own canvas, now that there's no PROCESS node to double-click. */
  const handleOpenSubprocess = useCallback((subprocessId: number, stepId?: string) => {
    onOpenSubprocess(subprocessId, process.name, stepId)
  }, [process, onOpenSubprocess])

  /** Explicit Save: flushes the pending autosave right away. Autosave itself never toasts. */
  const handleSave = useCallback(async () => {
    const { ok, error } = await session.saveNow()
    if (ok) notifySuccess('Recipe saved')
    else notifyError(error ?? 'Unable to save this recipe right now')
  }, [session, notifySuccess, notifyError])

  // Shortcuts only while this canvas is on screen: it stays mounted (hidden) while the Recipe Process tab shows.
  const [showShortcuts, setShowShortcuts] = useState(false)
  const isCanvasVisible = useCallback(() => bodyRef.current?.offsetParent != null, [])
  useCanvasShortcuts({
    onDelete: deleteSelected,
    onUndo: undo,
    onRedo: redo,
    onSave: () => void handleSave(),
    onShowHelp: () => setShowShortcuts(true),
    isActive: isCanvasVisible,
  })

  const handleBack = useCallback(() => {
    if (!confirmNavigatingAway()) return
    void session.saveNow()
    onBack?.()
  }, [confirmNavigatingAway, session, onBack])

  const handleNavigateToList = useCallback(() => {
    onNavigateToList()
  }, [onNavigateToList])

  const handleNavigateToAncestor = useCallback((index: number) => {
    onNavigateToAncestor(index)
  }, [onNavigateToAncestor])

  const selectedNode = nodes.find((node) => String(node.id) === String(selectedNodeId)) ?? null

  // STEP nodes in the same array order the rest of this component already numbers/lists them in
  // (buildSlideshowSteps) — CONDITION nodes are never numbered as steps (RecipeProcessGraphContext's
  // stepOrder); the top bar's node selector lists them after the steps, under their own heading.
  const stepNodes = nodes.filter(isRecipeStepNode)
  const stepOrder = stepNodes.map((node) => node.id)
  const nodeOptions: NodeSelectorOption[] = [
    ...stepNodes.map((node, index) => ({
      id: node.id,
      kind: 'STEP' as const,
      label: `Step ${index + 1} · ${normalizeRecipeStepNodeData(node.data).title || 'Untitled step'}`,
    })),
    ...nodes.filter(isRecipeConditionNode).map((node) => ({
      id: node.id,
      kind: 'CONDITION' as const,
      label: `Condition · ${normalizeConditionNodeData(node.data).title || 'Untitled condition'}`,
    })),
  ]

  const addStep = useCallback(() => addNode(RECIPE_NODE_TYPES.step), [addNode])
  const addCondition = useCallback(() => addNode(RECIPE_NODE_TYPES.condition), [addNode])
  const sidebarActions = useMemo<ProcessSidebarActions>(() => ({
    onAddStep: addStep,
    onAddCondition: addCondition,
    onCollapse: () => setSidebarCollapsed(true),
  }), [addStep, addCondition, setSidebarCollapsed])

  /**
   * Selects a node from the top bar's node selector and opens the properties panel on it —
   * deliberately does NOT move the viewport (no pan/zoom/fitView): the user's current view of the
   * canvas is left exactly as it was, only the selection changes. Must still flip each node's own
   * `selected` flag via `setNodes` — React Flow tracks selection on the node objects themselves,
   * not just via `selectedNodeId`; setting only the latter left the canvas's own selection model
   * out of sync, so the very next `onSelectionChange` (React Flow's own, e.g. from an unrelated
   * re-render) could silently revert the selector back to nothing.
   */
  const handleSelectNode = useCallback((nodeId: string) => {
    setSelectedNodeId(nodeId)
    setSelectedEdgeId(null)
    setNodes((nds) => nds.map((node) => (node.selected === (node.id === nodeId) ? node : { ...node, selected: node.id === nodeId })))
  }, [setNodes])

  // A node asked for from outside the canvas (a readiness issue): selected once it exists here — adjusted while
  // rendering, like the editor's other requests, so it applies on this process's first render.
  const [handledFocusToken, setHandledFocusToken] = useState<number | null>(null)
  // The node just navigated to: centred (keeping the zoom) and briefly highlighted.
  const [focusTarget, setFocusTarget] = useState<{ nodeId: string; token: number } | null>(null)
  if (focusNodeRequest && focusNodeRequest.token !== handledFocusToken && nodes.some((node) => node.id === focusNodeRequest.nodeId)) {
    setHandledFocusToken(focusNodeRequest.token)
    setFocusTarget(focusNodeRequest)
    setSelectedNodeId(focusNodeRequest.nodeId)
    setSelectedEdgeId(null)
    setNodes((nds) => nds.map((node) => (node.selected === (node.id === focusNodeRequest.nodeId) ? node : { ...node, selected: node.id === focusNodeRequest.nodeId })))
  }

  useEffect(() => {
    if (!focusTarget || !flowReady) return
    // After this render's own viewport work (the initial view), so the centring wins.
    const frame = requestAnimationFrame(() => {
      const instance = reactFlowInstance.current
      const node = nodesRef.current.find((candidate) => candidate.id === focusTarget.nodeId)
      if (!instance || !node) return
      const width = node.measured?.width ?? node.width ?? DEFAULT_NODE_SIZE.step.width
      const height = node.measured?.height ?? node.height ?? DEFAULT_NODE_SIZE.step.height
      void instance.setCenter(node.position.x + width / 2, node.position.y + height / 2, { zoom: instance.getZoom(), duration: 300 })
    })
    const clear = setTimeout(() => setFocusTarget((current) => (current?.token === focusTarget.token ? null : current)), 1800)
    return () => {
      cancelAnimationFrame(frame)
      clearTimeout(clear)
    }
  }, [focusTarget, flowReady])

  // --- The step popup: the step editor opened on the node itself by a click ---
  const readiness = useRecipeReadiness()
  const [stepPopup, setStepPopup] = useState<{ nodeId: string; side: Position } | null>(null)
  const popupNode = stepPopup ? nodes.find((node) => node.id === stepPopup.nodeId && isRecipeStepNode(node)) ?? null : null
  const popupRef = useRef<HTMLDivElement | null>(null)
  const closeStepPopup = useCallback(() => setStepPopup(null), [])

  /**
   * Opens beside the node — on the right, or on the left when only that side has room — and pans the canvas
   * just enough for the popup to be fully visible when neither side fits (the node stays on screen).
   */
  const openStepPopup = useCallback((node: Node) => {
    const instance = reactFlowInstance.current
    const wrapper = reactFlowWrapperRef.current
    if (!instance || !wrapper) {
      setStepPopup({ nodeId: node.id, side: Position.Right })
      return
    }
    const box = wrapper.getBoundingClientRect()
    const width = node.measured?.width ?? node.width ?? DEFAULT_NODE_SIZE.step.width
    const topLeft = instance.flowToScreenPosition({ x: node.position.x, y: node.position.y })
    const bottomRight = instance.flowToScreenPosition({ x: node.position.x + width, y: node.position.y })
    const needed = STEP_POPUP_WIDTH + STEP_POPUP_GAP + 12
    const roomRight = box.right - bottomRight.x
    const roomLeft = topLeft.x - box.left
    const side = roomRight >= needed || roomRight >= roomLeft ? Position.Right : Position.Left
    let dx = 0
    if (side === Position.Right && roomRight < needed) dx = -Math.min(needed - roomRight, Math.max(0, roomLeft - 12))
    if (side === Position.Left && roomLeft < needed) dx = Math.min(needed - roomLeft, Math.max(0, roomRight - 12))
    const popupHeight = Math.min(STEP_POPUP_HEIGHT, window.innerHeight - 140)
    const overflowBottom = topLeft.y + popupHeight + 12 - box.bottom
    const dy = overflowBottom > 0 ? -Math.min(overflowBottom, Math.max(0, topLeft.y - box.top - 12)) : 0
    if (dx !== 0 || dy !== 0) {
      const viewport = instance.getViewport()
      void instance.setViewport({ ...viewport, x: viewport.x + dx, y: viewport.y + dy }, { duration: 200 })
    }
    setStepPopup({ nodeId: node.id, side })
  }, [])

  // Clicking anywhere outside it hides the popup (every edit is already applied); its own pickers, the amount
  // popover and confirmation dialogs count as inside, and so does its node (a click there keeps it open).
  useEffect(() => {
    if (!stepPopup) return
    const onPointerDown = (event: PointerEvent) => {
      const target = event.target as Element | null
      if (!target || popupRef.current?.contains(target)) return
      if (target.closest('.annotation-suggestions, .annotation-amount-popover, [role="alertdialog"], [role="dialog"]')) return
      if (target.closest(`.react-flow__node[data-id="${CSS.escape(stepPopup.nodeId)}"]`)) return
      setStepPopup(null)
    }
    document.addEventListener('pointerdown', onPointerDown, true)
    return () => document.removeEventListener('pointerdown', onPointerDown, true)
  }, [stepPopup])

  const handleSaveAndClose = useCallback(async () => {
    setStepPopup(null)
    await handleSave()
  }, [handleSave])

  /** A prepared chip or "Used in this step" row: follows the reference by ids, or says it no longer resolves. */
  const handleOpenPrepared = useCallback((reference: { processId: number; stepId?: string }) => {
    if (describePreparedReference(recipeProcesses, reference).problem) {
      notifyError('This prepared component no longer exists.')
      return
    }
    handleOpenSubprocess(reference.processId, reference.stepId)
  }, [recipeProcesses, notifyError, handleOpenSubprocess])

  return (
    <div className="flow-canvas-container flex h-full w-full flex-col">
      <RecipeProcessTopBar
        recipeName={recipeName}
        name={process.name}
        processType={process.type}
        breadcrumbAncestors={breadcrumbAncestors}
        onNavigateToList={handleNavigateToList}
        onNavigateToAncestor={handleNavigateToAncestor}
        onBack={onBack ? handleBack : undefined}
        onSave={() => void handleSave()}
        isSaving={session.saveState.status === 'saving'}
        onShowShortcuts={() => setShowShortcuts(true)}
        onUndo={undo}
        onRedo={redo}
        canUndo={session.canUndo}
        canRedo={session.canRedo}
        onDeleteSelected={(selectedNodeId || selectedEdgeId) ? deleteSelected : undefined}
        zoomPercent={zoomPercent}
        onZoomIn={handleZoomIn}
        onZoomOut={handleZoomOut}
        onFitView={handleFitView}
        onAutoArrange={autoArrange}
        canAutoArrange={nodes.length > 0}
        onPreview={openSlideshow}
        onExport={() => void handleExport()}
        onGenerateVisuals={askForVisualsModels}
        isGeneratingVisuals={visuals.generating}
        visualsProgressLabel={visuals.progressLabel}
        generateVisualsStatus={visuals.status}
        aiActions={aiActions}
        // With a process sidebar, adding lives in its "+ Add" menu (and its collapsed rail's "+").
        onAddStep={sidebarHeader ? undefined : addStep}
        onAddCondition={sidebarHeader ? undefined : addCondition}
        nodeOptions={nodeOptions}
        currentNodeId={selectedNode?.id ?? null}
        onSelectNode={handleSelectNode}
      />

      <div className="flow-canvas-body" ref={bodyRef}>
        {sidebarHeader && (
          <div
            ref={sidebarRef}
            className={`flow-sidebar-wrapper ${sidebarCollapsed ? 'collapsed' : ''}`}
            style={{ width: sidebarCollapsed ? 48 : sidebarWidth, minWidth: sidebarCollapsed ? 48 : 200 }}
          >
            {sidebarCollapsed ? (
              <>
                <button type="button" className="sidebar-collapse-tab" aria-label="Expand Recipe Processes sidebar" title="Expand Recipe Processes" onClick={() => setSidebarCollapsed(false)} style={{ border: 'none' }}>
                  ☰
                </button>
                <AddNodeMenu
                  onAddStep={addStep}
                  onAddCondition={addCondition}
                  label="+"
                  buttonClassName="recipe-sidebar-rail-add"
                  placement="right-start"
                />
              </>
            ) : (
              // The sidebar collapses itself from its own header (onCollapse).
              <div style={{ flex: 1, minHeight: 0, overflow: 'hidden', display: 'flex', flexDirection: 'column' }}>
                <ProcessSidebarActionsProvider value={sidebarActions}>
                  {sidebarHeader}
                </ProcessSidebarActionsProvider>
              </div>
            )}
          </div>
        )}

        {sidebarHeader && !sidebarCollapsed && (
          <div
            className="flow-inline-resizer"
            role="separator"
            aria-orientation="vertical"
            aria-label="Resize sidebar"
            onMouseDown={(event) => startPanelResize(event, {
              side: 'left',
              startWidth: sidebarRef.current?.offsetWidth ?? sidebarWidth,
              minWidth: 200,
              // Leaves room for the properties panel (and its resizer) and the canvas itself.
              maxWidth: () => getMaxPanelWidth(460, propsCollapsed ? 48 : propsWidth, 10 + (propsCollapsed ? 0 : 10)),
              onResize: setSidebarWidth,
            })}
          />
        )}

        <div className="flow-canvas-main">
          <div className="flow-canvas-area">
            <div ref={reactFlowWrapperRef} className="flow-canvas-viewport">
              {/* STEP nodes need subprocess names to show Action On info directly on the canvas (per
                  the brief) — provided via context rather than extra node props, since React Flow's
                  custom node components only receive their own node's data. Ingredient names come
                  from the static catalog module directly, no context needed for those. */}
              <RecipeProcessGraphProvider
                value={{
                  recipeProcesses,
                  stepOrder,
                  stepOutputGraph,
                  onNodeResizeStart: handleNodeResizeStart,
                  onNodeResizeEnd: handleNodeResizeEnd,
                  handleSides,
                  onOpenPrepared: handleOpenPrepared,
                  highlightedNodeId: focusTarget?.nodeId ?? null,
                }}
              >
                <ReactFlow
                  nodes={displayedNodes}
                  edges={displayedEdges}
                  onNodesChange={handleNodesChange}
                  onEdgesChange={handleEdgesChange}
                  onConnect={onConnect}
                  isValidConnection={isValidConnection}
                  onNodeDragStart={handleNodeDragStart}
                  onNodeDragStop={handleNodeDragStop}
                  // A plain click on a step opens its editor on the node; a modifier click only (multi-)selects.
                  onNodeClick={(event, node) => {
                    if (event.shiftKey || event.ctrlKey || event.metaKey) return
                    if (isRecipeStepNode(node)) openStepPopup(node)
                    else setStepPopup(null)
                  }}
                  onSelectionChange={({ nodes: selectedNodes, edges: selectedEdges }) => {
                    if (selectedNodes.length > 0) {
                      setSelectedNodeId(selectedNodes[0].id)
                      setSelectedEdgeId(null)
                    } else if (selectedEdges.length > 0) {
                      setSelectedEdgeId(selectedEdges[0].id)
                      setSelectedNodeId(null)
                    } else {
                      setSelectedNodeId(null)
                      setSelectedEdgeId(null)
                    }
                  }}
                  // Disabled so keyboard deletion has exactly one path (our own window keydown
                  // handler above, covering both nodes and edges) instead of two competing ones —
                  // React Flow's own default ('Backspace' only) would otherwise also remove whatever
                  // it thinks is selected via its own onNodesChange/onEdgesChange 'remove' events,
                  // independently of and inconsistently with our selectedNodeId/selectedEdgeId state.
                  deleteKeyCode={null}
                  nodeTypes={recipeNodeComponents}
                  onInit={(instance) => {
                    reactFlowInstance.current = instance
                    setFlowReady(true)
                  }}
                  onMove={handleViewportMove}
                  onMoveEnd={handleViewportMoveEnd}
                  // Only takes effect at this component's own mount — fine here, since this whole
                  // component remounts fresh per process (see the outer RecipeProcessCanvas's `key`).
                  // Without one, showInitialView frames the graph instead.
                  defaultViewport={initialFlowData.viewport ?? undefined}
                  // Low enough to overview a long recipe by hand; the initial view stays readable.
                  minZoom={0.2}
                  panOnScroll
                  panOnScrollMode={PanOnScrollMode.Free}
                  zoomOnScroll={false}
                  zoomOnPinch
                  selectionOnDrag
                  connectionRadius={28}
                  style={{ width: '100%', height: '100%' }}
                >
                  <Background variant={BackgroundVariant.Dots} gap={16} size={1} />
                  <Controls style={{ borderRadius: 10, border: '1px solid var(--flow-border)', boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }} />
                  {/* The minimap would sit on top of the step popup, so it steps aside while one is open. */}
                  {!popupNode && (
                    <MiniMap pannable zoomable style={{ borderRadius: 12, border: '1px solid var(--flow-border)', boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }} />
                  )}
                  {stepPopup && popupNode && (
                    <NodeToolbar nodeId={popupNode.id} isVisible position={stepPopup.side} align="start" offset={STEP_POPUP_GAP}>
                      <div
                        ref={popupRef}
                        className="flow-step-popup nodrag nowheel nopan"
                        data-canvas-shortcuts="off"
                        role="dialog"
                        aria-label="Edit step"
                        onKeyDown={(event) => {
                          if (event.key === 'Escape') {
                            event.stopPropagation()
                            setStepPopup(null)
                          }
                        }}
                      >
                        <RecipeStepPanel
                          key={popupNode.id}
                          variant="popup"
                          node={{ id: popupNode.id, data: popupNode.data }}
                          recipeProcesses={recipeProcesses}
                          isPreparation={process.type === 'SUBPROCESS'}
                          updateStepField={updateRecipeStepField}
                          updateActionOnIngredients={updateActionOnIngredients}
                          updateActionOnProcesses={updateActionOnProcesses}
                          updateActionOnSteps={updateActionOnSteps}
                          updateStepData={updateStepData}
                          stepOutputGraph={stepOutputGraph}
                          onDeleteNode={(id) => void requestDeleteNode(id)}
                          onDuplicateNode={duplicateNode}
                          onOpenSubprocess={handleOpenSubprocess}
                          onGenerateVisuals={askForVisualsModels}
                          isGeneratingVisuals={visuals.generating}
                          visualsProgressLabel={visuals.progressLabel}
                          issues={readiness?.issues.filter((issue) => issue.nodeId === popupNode.id && issue.processId === processId) ?? []}
                          autoFocusDescription={Boolean(normalizeRecipeStepNodeData(popupNode.data).step.action)}
                          onClose={closeStepPopup}
                          onSaveAndClose={() => void handleSaveAndClose()}
                        />
                      </div>
                    </NodeToolbar>
                  )}
                </ReactFlow>
              </RecipeProcessGraphProvider>
              {flowReady && nodes.length === 0 && (
                <EmptyCanvasPrompt
                  isPreparation={isPreparationProcess}
                  onAddStep={addStep}
                  onAddCondition={addCondition}
                  onGenerateWithAi={aiActions && !isPreparationProcess ? () => aiActions.onOpenAiCreation() : undefined}
                />
              )}
            </div>
          </div>
        </div>

        <div
          className="flow-inline-resizer"
          role="separator"
          aria-orientation="vertical"
          aria-label="Resize properties panel"
          onMouseDown={(event) => startPanelResize(event, {
            side: 'right',
            startWidth: propsRef.current?.offsetWidth ?? propsWidth,
            minWidth: 220,
            // Leaves room for the sidebar (and its resizer) and the canvas itself.
            maxWidth: () => getMaxPanelWidth(520, sidebarHeader ? (sidebarCollapsed ? 48 : sidebarWidth) : 0, (sidebarHeader ? 10 : 0) + 10),
            onResize: setPropsWidth,
          })}
        />

        <div
          ref={propsRef}
          className={`flow-properties-wrapper ${propsCollapsed ? 'collapsed' : ''}`}
          style={{ width: propsCollapsed ? 48 : propsWidth, minWidth: propsCollapsed ? 48 : 200 }}
        >
          {propsCollapsed ? (
            <div className="props-collapse-tab" role="button" aria-label="Open properties" onClick={() => setPropsCollapsed(false)}>
              ▶
            </div>
          ) : (
            <>
              <div className="props-collapse-button" role="button" title="Collapse properties" onClick={() => setPropsCollapsed(true)}>▶</div>
              {selectedNode && isRecipeStepNode(selectedNode) ? (
                <RecipeStepPanel
                  node={{ id: selectedNode.id, data: selectedNode.data }}
                  recipeProcesses={recipeProcesses}
                  isPreparation={process.type === 'SUBPROCESS'}
                  updateStepField={updateRecipeStepField}
                  updateActionOnIngredients={updateActionOnIngredients}
                  updateActionOnProcesses={updateActionOnProcesses}
                  updateActionOnSteps={updateActionOnSteps}
                  updateStepData={updateStepData}
                  stepOutputGraph={stepOutputGraph}
                  onDeleteNode={(id) => void requestDeleteNode(id)}
                  onDuplicateNode={duplicateNode}
                  onOpenSubprocess={handleOpenSubprocess}
                  onGenerateVisuals={askForVisualsModels}
                  isGeneratingVisuals={visuals.generating}
                  visualsProgressLabel={visuals.progressLabel}
                />
              ) : (
                <RecipeConditionPanel
                  node={
                    selectedNode && isRecipeConditionNode(selectedNode)
                      ? { id: selectedNode.id, data: selectedNode.data as never }
                      : undefined
                  }
                  updateNodeField={updateNodeField}
                  onDeleteNode={(id) => void requestDeleteNode(id)}
                  onDuplicateNode={duplicateNode}
                />
              )}
            </>
          )}
        </div>
      </div>

      {showSlideshow && (
        <RecipeVisualizationSlideshow
          steps={buildSlideshowSteps()}
          recipeId={recipeId}
          processId={processId}
          narrationReady={narrationReady}
          onClose={() => setShowSlideshow(false)}
        />
      )}

      {visualsModelsOpen && (
        <ModelChoiceDialog
          title="🖼️ Generate visuals"
          description={isPreparationProcess
            ? 'One image per preparation flow. The text model writes the image prompts; the image model draws them.'
            : 'One image for every step of this process. The text model writes the image prompts; the image model draws them.'}
          capabilities={VISUALS_MODELS}
          existing={{ images: nodes.filter((node) => isRecipeStepNode(node) && normalizeRecipeStepNodeData(node.data).visualization?.imageUrl).length }}
          confirmLabel="Generate visuals"
          onCancel={() => setVisualsModelsOpen(false)}
          onConfirm={(modelKeys, overwriteExisting) => {
            setVisualsModelsOpen(false)
            void visuals.generate(modelKeys, overwriteExisting)
          }}
        />
      )}

      {exportedFile && <ExportProcessModal file={exportedFile} onClose={() => setExportedFile(null)} />}

      {showShortcuts && <ShortcutHelpDialog onClose={() => setShowShortcuts(false)} />}
    </div>
  )
}
