import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import Sidebar from '../sidebar/Sidebar'
import PropertiesPanel from '../toolbar/PropertiesPanel'
import FlowEditorTopBar from '../toolbar/FlowEditorTopBar'
import { FlowApi, VisualizationApi } from '../../../../api'
import './FlowCanvas.css'
import '../sidebar/Sidebar.css'
import '../toolbar/PropertiesPanel.css'

import {
  ReactFlow,
  Background,
  Controls,
  MiniMap,
  type ReactFlowInstance,
  useNodesState,
  useEdgesState,
  applyNodeChanges,
  addEdge,
  type Node,
  type Edge,
  type IsValidConnection,
  type Connection,
  type NodeChange,
  MarkerType,
  BackgroundVariant,
} from '@xyflow/react'

import '@xyflow/react/dist/style.css'
import { nodeTypes } from '../../nodes/nodeTypes.ts'
import {
  createFlowDataPayload,
  createNodeForType,
  normalizeFlowEdges,
  normalizeFlowNode,
  serializeFlowData,
  type EdgeKind,
} from './FlowCanvas.helpers.ts'
import type { VisualizationResponse } from '../../../../api'
import {
  type FlowNodeType,
  getFlowMetaCounts,
  isConditionNode,
  isParallelEndNode,
  isParallelNode,
  isParallelStartNode,
  isRecipeStepNode,
} from '../../model/flowNodeModel'
import {
  getParallelNodeTitle,
  normalizeParallelNodeData,
  getConditionNodeTitle,
  normalizeConditionNodeData,
  createDefaultStepFields,
  getStepNodeIcon,
  getStepNodeTitle,
  normalizeStepNodeData,
  type FlowData,
  type FlowDraftStorage,
  type FlowNodePayload,
} from '../../../../types/recipeFlow'
import {
  CUSTOM_INGREDIENT_ID,
  getIngredientDefaultUnit,
} from '../../catalog/ingredientCatalog'
import {
  buildDurationLabel,
  buildRepeatIntervalLabel,
} from '../../catalog/stepFieldCatalog'
import { getActionDisplayName, resolveStepActionId } from '../../catalog/actionCatalog'

// ─── Constants / helpers are moved to FlowCanvas.helpers.ts ────────────────
const initialNodes: Node[] = []
const initialEdges: Edge[] = []

type FlowCanvasProps = {
  recipe: { id: number; title: string }
  onBack: () => void
}

const getDraftKey = (recipeId: number | string) => `recipe-flow-draft:${recipeId}`

const readDraftFlowData = (recipeId: number | string): FlowData | null => {
  try {
    const raw = localStorage.getItem(getDraftKey(recipeId))
    if (!raw) return null

    const parsed = JSON.parse(raw) as FlowDraftStorage
    if (!parsed || parsed.version !== '2.0' || !parsed.data) return null
    return parsed.data
  } catch {
    return null
  }
}

const toFlowNodes = (rawNodes: FlowNodePayload[]): Node[] => rawNodes.map((node) => normalizeFlowNode(node))

// ─── Component ────────────────────────────────────────────────────────────────
export default function FlowCanvas({ recipe, onBack }: FlowCanvasProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const flowMeta = useMemo(() => getFlowMetaCounts(nodes), [nodes])
  const [history, setHistory] = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [future,  setFuture]  = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [exportJson, setExportJson] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [visualizationResult, setVisualizationResult] = useState<VisualizationResponse | null>(null)
  const [visualizing, setVisualizing] = useState(false)
  const dragSnapshotRef = useRef<{ nodes: Node[]; edges: Edge[] } | null>(null)

  const hasDraggedNodesChanged = useCallback((before: Node[], after: Node[]) => {
    if (before.length !== after.length) return true

    const beforeMap = new Map(before.map((node) => [String(node.id), node]))

    return after.some((node) => {
      const previous = beforeMap.get(String(node.id))
      if (!previous) return true
      return previous.position.x !== node.position.x || previous.position.y !== node.position.y
    })
  }, [])

  const selectedNode = nodes.find(n => n.selected)
  const selectedNodeId = selectedNode ? String(selectedNode.id) : null

  const selectNodeFromSidebar = useCallback((nodeId: string | null) => {
    setNodes((ns) => {
      if (nodeId == null) {
        const hasSelected = ns.some((n) => !!n.selected)
        if (!hasSelected) return ns
        return ns.map((n) => (n.selected ? { ...n, selected: false } : n))
      }

      const targetId = String(nodeId)
      const needsUpdate = ns.some((n) => {
        const shouldBeSelected = String(n.id) === targetId
        return !!n.selected !== shouldBeSelected
      })

      if (!needsUpdate) return ns

      return ns.map((n) => {
        const shouldBeSelected = String(n.id) === targetId
        return !!n.selected === shouldBeSelected ? n : { ...n, selected: shouldBeSelected }
      })
    })
  }, [setNodes])

  // ── Snapshot ──────────────────────────────────────────────────────────────
  const saveSnapshot = useCallback(() => {
    setHistory(prev => [...prev, { nodes: structuredClone(nodes), edges: structuredClone(edges) }])
    setFuture([])
  }, [nodes, edges])

  const undo = useCallback(() => {
    if (!history.length) return
    const prev = history[history.length - 1]
    setFuture(f => [...f, { nodes: structuredClone(nodes), edges: structuredClone(edges) }])
    setNodes(prev.nodes); setEdges(prev.edges)
    setHistory(h => h.slice(0, -1))
  }, [history, nodes, edges, setNodes, setEdges])

  const redo = useCallback(() => {
    if (!future.length) return
    const next = future[future.length - 1]
    setHistory(h => [...h, { nodes: structuredClone(nodes), edges: structuredClone(edges) }])
    setNodes(next.nodes); setEdges(next.edges)
    setFuture(f => f.slice(0, -1))
  }, [future, nodes, edges, setNodes, setEdges])

  // ── Drag history via node changes ─────────────────────────────────────────
  const handleNodesChange = useCallback((changes: NodeChange<Node>[]) => {
    const hasPositionChange = changes.some((change) => change.type === 'position')
    const hasDraggingStop = changes.some(
      (change) => change.type === 'position' && change.dragging === false,
    )

    if (hasPositionChange && !dragSnapshotRef.current) {
      dragSnapshotRef.current = { nodes: structuredClone(nodes), edges: structuredClone(edges) }
    }

    const nextNodes = hasPositionChange ? applyNodeChanges(changes, nodes) : nodes
    onNodesChange(changes)

    if (hasDraggingStop && dragSnapshotRef.current) {
      const snapshot = dragSnapshotRef.current
      dragSnapshotRef.current = null

      if (hasDraggedNodesChanged(snapshot.nodes, nextNodes)) {
        setHistory((historyState) => [...historyState, snapshot])
        setFuture([])
      }
    }
  }, [nodes, edges, onNodesChange, hasDraggedNodesChanged, setFuture])

  // ── Delete / duplicate ────────────────────────────────────────────────────
  const deleteSelected = useCallback(() => {
    saveSnapshot()
    setNodes(n => n.filter(nd => !nd.selected))
    setEdges(e => e.filter(ed => !ed.selected))
  }, [saveSnapshot, setNodes, setEdges])

  const deleteNode = useCallback((id: string) => {
    saveSnapshot()
    setNodes(n => n.filter(nd => nd.id !== id))
    setEdges(e => e.filter(ed => ed.source !== id && ed.target !== id))
  }, [saveSnapshot, setNodes, setEdges])

  const duplicateNode = useCallback((id: string) => {
    saveSnapshot()
    const orig = nodes.find(n => n.id === id)
    if (!orig) return
    setNodes(n => [...n, {
      ...structuredClone(orig), id: crypto.randomUUID(), selected: false,
      position: { x: orig.position.x + 24, y: orig.position.y + 24 },
    }])
  }, [nodes, saveSnapshot, setNodes])

  const addFreeNode = useCallback((nodeType: FlowNodeType) => {
    saveSnapshot()
    const baseY = 140 + nodes.length * 70
    const newNode = createNodeForType(crypto.randomUUID(), nodeType, { x: 720, y: Math.min(baseY, 420) })
    setNodes(n => [...n, newNode])
  }, [saveSnapshot, setNodes, nodes.length])

  // ── Update field ──────────────────────────────────────────────────────────
  const updateNodeField = useCallback((nodeId: string, field: string, value: string) => {
    setNodes((nds) => nds.map((node) => {
      if (node.id !== nodeId) return node

      if (field.startsWith('step.')) {
        const stepField = field.slice(5)
        const normalized = normalizeStepNodeData(node.data)
        const mergedStep = {
          ...createDefaultStepFields(),
          ...normalized.step,
          [stepField]: value,
        }

        if (stepField === 'ingredientId') {
          if (!value) {
            mergedStep.customIngredientName = ''
          } else if (value === CUSTOM_INGREDIENT_ID) {
            mergedStep.customIngredientName = mergedStep.customIngredientName
          } else {
            mergedStep.customIngredientName = ''
            if (!mergedStep.unit.trim()) {
              mergedStep.unit = getIngredientDefaultUnit(mergedStep.ingredientId)
            }
          }
        }

        if (stepField === 'unitOption') {
          if (value === 'Custom') {
            mergedStep.unit = mergedStep.customUnit.trim()
          } else {
            mergedStep.customUnit = ''
            mergedStep.unit = value
          }
        }

        if (stepField === 'customUnit') {
          if (mergedStep.unitOption === 'Custom') {
            mergedStep.unit = value
          }
        }

        if (stepField === 'specificationOption') {
          if (value === 'Custom') {
            mergedStep.specification = mergedStep.customSpecification.trim()
          } else {
            mergedStep.customSpecification = ''
            mergedStep.specification = value
          }
        }

        if (stepField === 'customSpecification') {
          if (mergedStep.specificationOption === 'Custom') {
            mergedStep.specification = value
          }
        }

        if (stepField === 'action' && !mergedStep.repeatAction) {
          mergedStep.repeatAction = resolveStepActionId(value)
        }

        mergedStep.duration = buildDurationLabel(mergedStep.durationValue, mergedStep.durationUnit)
        const repeatActionLabel = mergedStep.repeatAction ? getActionDisplayName(mergedStep.repeatAction) : ''
        mergedStep.repeatInterval = buildRepeatIntervalLabel(
          repeatActionLabel,
          mergedStep.repeatEveryValue,
          mergedStep.repeatEveryUnit,
        )

        const finalized = normalizeStepNodeData({ ...normalized, step: mergedStep })
        return {
          ...node,
          data: finalized,
        }
      }

      if (field.startsWith('condition.')) {
        const conditionField = field.slice(10)
        const normalized = normalizeConditionNodeData(node.data)
        const mergedCondition = {
          ...normalized.condition,
          [conditionField]: value,
        }

        const finalized = normalizeConditionNodeData({
          ...normalized,
          condition: mergedCondition,
        })

        return {
          ...node,
          data: {
            ...finalized,
            title: getConditionNodeTitle(finalized.condition.question),
            description: finalized.condition.notes,
            yesLabel: finalized.condition.successLabel,
            noLabel: finalized.condition.failureLabel,
          },
        }
      }

      if (field.startsWith('parallel.')) {
        const parallelField = field.slice(9)
        const fallbackKind = isParallelEndNode(node) ? 'end' : 'start'
        const normalized = normalizeParallelNodeData(node.data, fallbackKind)
        const mergedParallel = {
          ...normalized.parallel,
          [parallelField]: value,
        }

        const finalized = normalizeParallelNodeData({
          ...normalized,
          parallel: mergedParallel,
        }, fallbackKind)

        return {
          ...node,
          data: {
            ...finalized,
            title: getParallelNodeTitle(finalized.parallel.kind, finalized.parallel.label),
            description: finalized.parallel.notes,
          },
        }
      }

      if (field === 'title' && isRecipeStepNode(node)) {
        const normalized = normalizeStepNodeData(node.data)
        const finalized = {
          ...normalized,
          title: getStepNodeTitle(normalized.step.action),
          icon: getStepNodeIcon(normalized.step.action),
        }
        return {
          ...node,
          data: finalized,
        }
      }

      return {
        ...node,
        data: {
          ...node.data,
          [field]: value,
        },
      }
    }))
  }, [setNodes])

  const isValidConnection = useCallback<IsValidConnection<Edge>>((connectionOrEdge) => {
    const connection: Connection = {
      source: connectionOrEdge.source,
      target: connectionOrEdge.target,
      sourceHandle: connectionOrEdge.sourceHandle ?? null,
      targetHandle: connectionOrEdge.targetHandle ?? null,
    }

    if (!connection.source || !connection.target) return false
    if (connection.source === connection.target) return false

    const sourceNode = nodes.find((node) => node.id === connection.source)
    const targetNode = nodes.find((node) => node.id === connection.target)
    if (!sourceNode || !targetNode) return false

    const duplicate = edges.some((edge) =>
      edge.source === connection.source &&
      edge.target === connection.target &&
      (edge.sourceHandle ?? null) === (connection.sourceHandle ?? null) &&
      (edge.targetHandle ?? null) === (connection.targetHandle ?? null)
    )
    if (duplicate) return false

    const sourceOutgoing = edges.filter((edge) => edge.source === connection.source).length
    const targetIncoming = edges.filter((edge) => edge.target === connection.target).length

    if (isParallelEndNode(sourceNode) && sourceOutgoing >= 1) return false
    if (isParallelStartNode(targetNode) && targetIncoming >= 1) return false

    if (isParallelStartNode(sourceNode) && !String(connection.sourceHandle ?? '').startsWith('parallel-start-out')) {
      return false
    }

    if (isParallelEndNode(targetNode) && !String(connection.targetHandle ?? '').startsWith('parallel-end-in')) {
      return false
    }

    return true
  }, [nodes, edges])

  // ── Export ────────────────────────────────────────────────────────────────
  const exportFlow = useCallback(() => {
    const data = serializeFlowData(nodes, edges)
    setExportJson(JSON.stringify(data, null, 2))
  }, [nodes, edges])

  const downloadJson = useCallback(() => {
    if (!exportJson) return
    const blob = new Blob([exportJson], { type: 'application/json' })
    const url  = URL.createObjectURL(blob)
    const a    = document.createElement('a')
    a.href = url; a.download = 'recipe-flow.json'; a.click()
    URL.revokeObjectURL(url)
  }, [exportJson])

  const copyJson = useCallback(() => {
    if (!exportJson) return
    navigator.clipboard.writeText(exportJson).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }, [exportJson])

  useEffect(() => {
    const loadExistingFlow = async () => {
      const draftData = readDraftFlowData(recipe.id)

      try {
        const flowData = await FlowApi.getFlowByRecipeId(recipe.id)
        const hasRemoteData = (flowData?.nodes?.length ?? 0) > 0 || (flowData?.edges?.length ?? 0) > 0
        const sourceData = hasRemoteData ? flowData : draftData
        if (!sourceData) return

        const loadedNodes = toFlowNodes((sourceData.nodes ?? []) as FlowNodePayload[])

        const loadedEdges = normalizeFlowEdges(sourceData.edges ?? [])

        setNodes(loadedNodes)
        setEdges(loadedEdges)
        setHistory([])
        setFuture([])
      } catch (error) {
        if (draftData) {
          const loadedNodes = toFlowNodes((draftData.nodes ?? []) as FlowNodePayload[])
          const loadedEdges = normalizeFlowEdges(draftData.edges ?? [])

          setNodes(loadedNodes)
          setEdges(loadedEdges)
          setHistory([])
          setFuture([])
          return
        }

        console.error('Unable to load existing flow', error)
      }
    }

    void loadExistingFlow()
  }, [recipe.id, setNodes, setEdges])

  const saveFlow = useCallback(async () => {
    try {
      const flowData = createFlowDataPayload(nodes, edges)

      await FlowApi.saveFlow(recipe.id, flowData)
      const draftRecord: FlowDraftStorage = {
        version: '2.0',
        recipeId: recipe.id,
        updatedAt: new Date().toISOString(),
        data: flowData,
      }
      localStorage.setItem(getDraftKey(recipe.id), JSON.stringify(draftRecord))
      console.log('Flow saved successfully')
      alert('Flow saved successfully')
    } catch (error) {
      console.error(error)
      alert('Unable to save flow right now')
    }
  }, [nodes, edges, recipe.id])

  useEffect(() => {
    const draftRecord: FlowDraftStorage = {
      version: '2.0',
      recipeId: recipe.id,
      updatedAt: new Date().toISOString(),
      data: createFlowDataPayload(nodes, edges),
    }

    localStorage.setItem(getDraftKey(recipe.id), JSON.stringify(draftRecord))
  }, [nodes, edges, recipe.id])

  const visualizeFlow = useCallback(async () => {
    setVisualizing(true)
    setVisualizationResult(null)

    try {
      const visualizationData = createFlowDataPayload(nodes, edges)

      const result = await VisualizationApi.generateVisualization(recipe.id, visualizationData)
      setVisualizationResult(result ?? null)
    } catch (error) {
      console.error(error)
      alert('Unable to visualize flow right now')
    } finally {
      setVisualizing(false)
    }
  }, [nodes, edges, recipe.id])

  // ── onConnect ─────────────────────────────────────────────────────────────
  const onConnect = useCallback((connection: Connection) => {
    if (!isValidConnection(connection)) return
    saveSnapshot()
    const isYes = connection.sourceHandle === 'condition-yes'
    const isNo  = connection.sourceHandle === 'condition-no'
    const isParallel = String(connection.sourceHandle ?? '').startsWith('parallel-') || String(connection.targetHandle ?? '').startsWith('parallel-')
    const kind: EdgeKind = isYes ? 'yes' : isNo ? 'no' : isParallel ? 'parallel' : 'step'
    const colors: Record<EdgeKind, string> = { step: '#94a3b8', yes: '#16a34a', no: '#dc2626', parallel: '#7c3aed' }
    const color = colors[kind]
    setEdges(eds => addEdge({
      ...connection,
      type: 'smoothstep',
      animated: false,
      label: isYes ? 'Yes ✓' : isNo ? 'No ✗' : isParallel ? 'Parallel' : undefined,
      labelStyle: { fill: color, fontWeight: 700, fontSize: 11 },
      labelBgStyle: { fill: isYes ? '#f0fdf4' : isNo ? '#fff5f5' : isParallel ? '#f5f3ff' : 'transparent' },
      style: { stroke: color, strokeWidth: 1.5 },
      markerEnd: { type: MarkerType.ArrowClosed, color, width: 16, height: 16 },
    }, eds))
  }, [isValidConnection, saveSnapshot, setEdges])

  // ── Keyboard ──────────────────────────────────────────────────────────────
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA') return
      if (e.key === 'Delete' || e.key === 'Backspace') deleteSelected()
      if ((e.ctrlKey || e.metaKey) && e.key === 'z') { e.preventDefault(); undo() }
      if ((e.ctrlKey || e.metaKey) && e.key === 'y') { e.preventDefault(); redo() }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [deleteSelected, undo, redo])

  // ─────────────────────────────────────────────────────────────────────────
  // refs for resize observation and reactflow instance
  const sidebarRef = useRef<HTMLDivElement | null>(null)
  const propsRef = useRef<HTMLDivElement | null>(null)
  const reactFlowInstance = useRef<ReactFlowInstance<Node, Edge> | null>(null)
  const reactFlowWrapperRef = useRef<HTMLDivElement | null>(null)

  // observe size changes of the sidebar and call fitView
  useEffect(() => {
    if (!reactFlowInstance.current) return
    const ro = new ResizeObserver(() => {
      try { reactFlowInstance.current?.fitView({ padding: 0.12 }) } catch (e) { /* ignore */ }
    })
    if (sidebarRef.current) ro.observe(sidebarRef.current)
    const onWin = () => { try { reactFlowInstance.current?.fitView({ padding: 0.12 }) } catch (e) {} }
    window.addEventListener('resize', onWin)
    return () => { ro.disconnect(); window.removeEventListener('resize', onWin) }
  }, [reactFlowInstance])

  return (
    <div className="flow-canvas-container">
      {/* Sidebar */}
      <div ref={sidebarRef} className="flow-sidebar-wrapper">
        <Sidebar
          onAddNode={addFreeNode}
          nodes={nodes}
          edges={edges}
          selectedNodeId={selectedNodeId}
          onSelectNode={selectNodeFromSidebar}
          flowMeta={flowMeta}
        />
      </div>

      {/* Main Content Area */}
      <div className="flow-canvas-main">
        {/* Topbar */}
        <div className="flow-canvas-topbar">
          <FlowEditorTopBar
            title={recipe.title}
            onUndo={undo}
            onRedo={redo}
            canUndo={history.length > 0}
            canRedo={future.length > 0}
            onExport={exportFlow}
            onSave={saveFlow}
            onVisualize={visualizeFlow}
            isVisualizing={visualizing}
            onBack={onBack}
          />
        </div>

        {/* Canvas Area */}
        <div ref={reactFlowWrapperRef} className="flow-canvas-area">
          {visualizationResult && (
            <div className="flow-canvas-visualization-result">
              <div className="flow-canvas-visualization-box">
                <div className="flow-canvas-visualization-title">📝 Visualization plan prepared</div>
                <div className="flow-canvas-visualization-info">
                  {visualizationResult.clips?.length ?? 0} clips planned · final clip: {visualizationResult.finalClip?.clipId ?? 'n/a'}
                </div>
              </div>
            </div>
          )}
          
            <ReactFlow
              onInit={inst => { reactFlowInstance.current = inst; try { inst.fitView({ padding: 0.12 }) } catch (e) {} }}
              nodes={nodes} 
              edges={edges}
              onNodesChange={handleNodesChange} 
              onEdgesChange={onEdgesChange}
              nodeTypes={nodeTypes} 
              onConnect={onConnect}
              isValidConnection={isValidConnection}
              fitView 
              fitViewOptions={{ padding: 0.12 }}
              style={{ width: '100%', height: '100%' }}
              connectionRadius={28}
              defaultEdgeOptions={{ 
                type: 'smoothstep',
                style: { stroke: '#94a3b8', strokeWidth: 1.5 },
                markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8', width: 16, height: 16 } 
              }}>
            <Background variant={BackgroundVariant.Dots} color="#e2e8f0" gap={20} size={1} />
            <Controls style={{ borderRadius: 10, border: '1px solid #e2e8f0', boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }} />
            <MiniMap 
              style={{ borderRadius: 12, border: '1px solid #e2e8f0', boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }}
              nodeColor={n => {
                if (isConditionNode(n)) return '#fde68a'
                if (isParallelNode(n)) return '#ddd6fe'
                return '#e2e8f0'
              }} 
            />
          </ReactFlow>
        </div>
      </div>

        {/* Resizer between main and properties */}
        <div
          className="flow-resizer"
          onMouseDown={(e) => {
            const startX = e.clientX
            const startWidth = propsRef.current?.offsetWidth ?? 260
            const minW = 160
            const maxW = 520
            let rafId: number | null = null

            const onMove = (ev: MouseEvent) => {
              const delta = startX - ev.clientX
              let next = startWidth - delta
              if (next < minW) next = minW
              if (next > maxW) next = maxW
              if (propsRef.current) propsRef.current.style.width = `${next}px`
              // throttle fitView with rAF
              if (rafId == null) {
                rafId = window.requestAnimationFrame(() => {
                  try { reactFlowInstance.current?.fitView({ padding: 0.12 }) } catch (e) {}
                  rafId = null
                })
              }
            }

            const onUp = () => {
              document.removeEventListener('mousemove', onMove)
              document.removeEventListener('mouseup', onUp)
              if (rafId != null) { window.cancelAnimationFrame(rafId); rafId = null }
            }

            document.addEventListener('mousemove', onMove)
            document.addEventListener('mouseup', onUp)
            e.preventDefault()
          }}
        />

      {/* Properties Panel - Right Sidebar (wrapped so resizer can resize it) */}
      <div ref={propsRef} className="flow-properties-wrapper">
        <PropertiesPanel
          node={selectedNode as any}
          updateNodeField={updateNodeField}
          onDeleteNode={deleteNode}
          onDuplicateNode={duplicateNode}
        />
      </div>

      {/* Export Modal */}
      {exportJson && (
        <div className="flow-canvas-export-modal-overlay" onClick={() => setExportJson(null)}>
          <div className="flow-canvas-export-modal" onClick={e => e.stopPropagation()}>
            {/* Modal header */}
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between',
              padding: '14px 18px', borderBottom: '1px solid #e2e8f0' }}>
              <div>
                <div style={{ fontWeight: 700, fontSize: 14, color: '#1e293b' }}>📤 Export Flow Graph</div>
                <div style={{ fontSize: 11, color: '#94a3b8', marginTop: 2 }}>
                  {nodes.length} nodes · {edges.length} edges — use for visualization development
                </div>
              </div>
              <div style={{ display: 'flex', gap: 7 }}>
                <button onClick={downloadJson} style={{ padding: '6px 12px', borderRadius: 7,
                  border: '1px solid #86efac', background: '#f0fdf4', color: '#16a34a',
                  fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>
                  ⬇ Download .json
                </button>
                <button onClick={copyJson} style={{ padding: '6px 12px', borderRadius: 7,
                  border: '1px solid #bfdbfe', background: '#eff6ff', color: '#2563eb',
                  fontSize: 12, fontWeight: 600, cursor: 'pointer', minWidth: 70 }}>
                  {copied ? '✅ Copied!' : '📋 Copy'}
                </button>
                <button onClick={() => setExportJson(null)} style={{ width: 30, height: 30,
                  borderRadius: 7, border: '1px solid #e2e8f0', background: 'white',
                  color: '#94a3b8', cursor: 'pointer', fontSize: 15 }}>✕</button>
              </div>
            </div>
            {/* JSON body */}
            <pre style={{ flex: 1, overflow: 'auto', margin: 0, padding: '14px 18px',
              fontSize: 11, lineHeight: 1.65, color: '#1e293b', background: '#f8fafc',
              fontFamily: "'Fira Code', 'Cascadia Code', monospace" }}>
              {exportJson}
            </pre>
          </div>
        </div>
      )}
    </div>
  )
}