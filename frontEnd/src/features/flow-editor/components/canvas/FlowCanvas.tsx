import { useCallback, useEffect, useRef, useState } from 'react'
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
  useNodesState,
  useEdgesState,
  addEdge,
  type Node,
  type Edge,
  MarkerType,
  BackgroundVariant,
} from '@xyflow/react'

import '@xyflow/react/dist/style.css'
import { nodeTypes } from '../../nodes/nodeTypes.ts'
import {
  createNodeForType,
  serializeFlowData,
  type EdgeKind,
} from './FlowCanvas.helpers.ts'

// ─── Constants / helpers are moved to FlowCanvas.helpers.ts ────────────────
const initialNodes: Node[] = []
const initialEdges: Edge[] = []

type FlowCanvasProps = {
  recipe: { id: number; title: string }
  onBack: () => void
}

// ─── Component ────────────────────────────────────────────────────────────────
export default function FlowCanvas({ recipe, onBack }: FlowCanvasProps) {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const [flowMeta, setFlowMeta] = useState({ stepCount: 0, conditionCount: 0 })
  const [history, setHistory] = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [future,  setFuture]  = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [exportJson, setExportJson] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [selectedNodeId, setSelectedNodeId] = useState<string | null>(null)
  const [visualizationResult, setVisualizationResult] = useState<any>(null)
  const [visualizing, setVisualizing] = useState(false)
  const dragSnapshotRef = useRef<{ nodes: Node[]; edges: Edge[] } | null>(null)

  const selectedNode = nodes.find(n => n.selected)

  useEffect(() => {
    setFlowMeta({
      stepCount: nodes.filter(node => node.type === 'recipeStepNode').length,
      conditionCount: nodes.filter(node => node.type === 'conditionNode').length,
    })
  }, [nodes])

  useEffect(() => {
    if (selectedNodeId == null) {
      setNodes(ns => ns.map(n => ({ ...n, selected: false })))
      return
    }

    setNodes(ns => ns.map(n => ({ ...n, selected: String(n.id) === String(selectedNodeId) })))
  }, [selectedNodeId, setNodes])

  useEffect(() => {
    if (!selectedNode) {
      if (selectedNodeId !== null) setSelectedNodeId(null)
      return
    }

    if (selectedNodeId !== String(selectedNode.id)) {
      setSelectedNodeId(String(selectedNode.id))
    }
  }, [selectedNode, selectedNodeId, setSelectedNodeId])

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

  // ── Drag ──────────────────────────────────────────────────────────────────
  const handleDragStart = useCallback(() => {
    dragSnapshotRef.current = { nodes: structuredClone(nodes), edges: structuredClone(edges) }
  }, [nodes, edges])

  const handleDragStop = useCallback(() => {
    if (!dragSnapshotRef.current) return
    setHistory(h => [...h, structuredClone(dragSnapshotRef.current!)])
    setFuture([]); dragSnapshotRef.current = null
  }, [])

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

  const addFreeNode = useCallback((nodeType: string) => {
    saveSnapshot()
    const baseY = 140 + nodes.length * 70
    const newNode = createNodeForType(crypto.randomUUID(), nodeType, { x: 720, y: Math.min(baseY, 420) })
    setNodes(n => [...n, newNode])
  }, [saveSnapshot, setNodes, nodes.length])

  // ── Update field ──────────────────────────────────────────────────────────
  const updateNodeField = useCallback((nodeId: string, field: string, value: string) => {
    setNodes(nds => nds.map(n => n.id === nodeId ? { ...n, data: { ...n.data, [field]: value } } : n))
  }, [setNodes])

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
      try {
        const flowData = await FlowApi.getFlowByRecipeId(recipe.id)
        if (!flowData?.nodes && !flowData?.edges) return

        const loadedNodes = (flowData.nodes ?? []).map((node: any) => ({
          id: node.id,
          type: node.type ?? 'recipeStepNode',
          position: node.position ?? { x: 0, y: 0 },
          data: node.data ?? {},
          measured: node.measured,
          parentId: node.parentId,
          extent: node.extent,
          draggable: node.draggable,
          selectable: node.selectable,
          deletable: node.deletable,
          style: node.style,
        }))

        const loadedEdges = (flowData.edges ?? []).map((edge: any) => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          sourceHandle: edge.sourceHandle,
          targetHandle: edge.targetHandle,
          type: edge.type,
          animated: edge.animated,
          style: edge.style,
          data: edge.data,
          label: edge.label,
        }))

        setNodes(loadedNodes)
        setEdges(loadedEdges)
        setHistory([])
        setFuture([])
      } catch (error) {
        console.error('Unable to load existing flow', error)
      }
    }

    void loadExistingFlow()
  }, [recipe.id, setNodes, setEdges])

  const saveFlow = useCallback(async () => {
    try {
      const flowData = {
        nodes: nodes.map(node => ({
          id: node.id,
          type: node.type,
          position: node.position,
          measured: node.measured,
          parentId: node.parentId,
          extent: node.extent,
          draggable: node.draggable,
          selectable: node.selectable,
          deletable: node.deletable,
          data: node.data,
        })),
        edges: edges.map(edge => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          sourceHandle: edge.sourceHandle,
          targetHandle: edge.targetHandle,
          type: edge.type,
          animated: edge.animated,
          style: edge.style,
          data: edge.data,
          label: edge.label,
        })),
      }

      await FlowApi.saveFlow(recipe.id, flowData)
      console.log('Flow saved successfully')
      alert('Flow saved successfully')
    } catch (error) {
      console.error(error)
      alert('Unable to save flow right now')
    }
  }, [nodes, edges, recipe.id])

  const visualizeFlow = useCallback(async () => {
    setVisualizing(true)
    setVisualizationResult(null)

    try {
      const visualizationData = {
        nodes: nodes.map(node => ({
          id: node.id,
          type: node.type,
          position: node.position,
          data: node.data,
        })),
        edges: edges.map(edge => ({
          id: edge.id,
          source: edge.source,
          target: edge.target,
          sourceHandle: edge.sourceHandle,
          targetHandle: edge.targetHandle,
          type: edge.type,
          data: edge.data,
          label: edge.label,
        })),
      }

      const result = await VisualizationApi.generateVisualization(recipe.id, visualizationData)
      setVisualizationResult(result ?? null)
    } catch (error) {
      console.error(error)
      alert('Unable to visualize flow right now')
    } finally {
      setVisualizing(false)
    }
  }, [nodes, edges, recipe.id])

  const displayNodes = nodes.map(node => ({ ...node, data: { ...node.data } }))

  // ── onConnect ─────────────────────────────────────────────────────────────
  const onConnect = useCallback((connection: any) => {
    saveSnapshot()
    const isYes = connection.sourceHandle === 'condition-yes'
    const isNo  = connection.sourceHandle === 'condition-no'
    const kind: EdgeKind = isYes ? 'yes' : isNo ? 'no' : 'step'
    const colors: Record<EdgeKind, string> = { step: '#94a3b8', yes: '#16a34a', no: '#dc2626' }
    const color = colors[kind]
    setEdges(eds => addEdge({
      ...connection,
      type: 'smoothstep',
      animated: false,
      label: isYes ? 'Yes ✓' : isNo ? 'No ✗' : undefined,
      labelStyle: { fill: color, fontWeight: 700, fontSize: 11 },
      labelBgStyle: { fill: isYes ? '#f0fdf4' : isNo ? '#fff5f5' : 'transparent' },
      style: { stroke: color, strokeWidth: 1.5 },
      markerEnd: { type: MarkerType.ArrowClosed, color, width: 16, height: 16 },
    }, eds))
  }, [saveSnapshot, setEdges])

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
  const reactFlowInstance = useRef<any>(null)
  const reactFlowWrapperRef = useRef<HTMLDivElement | null>(null)

  // observe size changes of the sidebar and call fitView
  useEffect(() => {
    if (!reactFlowInstance.current) return
    const ro = new ResizeObserver(() => {
      try { reactFlowInstance.current.fitView({ padding: 0.12 }) } catch (e) { /* ignore */ }
    })
    if (sidebarRef.current) ro.observe(sidebarRef.current)
    const onWin = () => { try { reactFlowInstance.current.fitView({ padding: 0.12 }) } catch (e) {} }
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
          onSelectNode={setSelectedNodeId}
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
              nodes={displayNodes} 
              edges={edges}
              onNodesChange={onNodesChange} 
              onEdgesChange={onEdgesChange}
              nodeTypes={nodeTypes} 
              onConnect={onConnect}
              onNodeDragStart={handleDragStart} 
              onNodeDragStop={handleDragStop}
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
                if (n.type === 'conditionNode') return '#fde68a'
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