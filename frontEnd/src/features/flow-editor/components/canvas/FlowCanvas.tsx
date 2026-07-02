import { useCallback, useEffect, useRef, useState } from 'react'
import Sidebar from '../sidebar/Sidebar'
import PropertiesPanel from '../toolbar/PropertiesPanel'
import FlowEditorTopBar from '../toolbar/FlowEditorTopBar'

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080'

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
  SECTION_WIDTH,
  createSectionNode,
  createNodeForSection,
  createFreeNode,
  getSelectedSectionTitle,
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
  const [collapsedSections, setCollapsedSections] = useState<Set<string>>(new Set())
  const [history, setHistory] = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [future,  setFuture]  = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [exportJson, setExportJson] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [selectedSectionId, setSelectedSectionId] = useState<string | null>(null)
  const [visualizationResult, setVisualizationResult] = useState<any>(null)
  const [visualizing, setVisualizing] = useState(false)
  const dragSnapshotRef = useRef<{ nodes: Node[]; edges: Edge[] } | null>(null)

  const selectedNode = nodes.find(n => n.selected)
  const selectedSectionTitle = getSelectedSectionTitle(nodes, selectedNode)

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
    setNodes(n => n.filter(nd => nd.id !== id && nd.parentId !== id))
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

  // ── Add nodes ─────────────────────────────────────────────────────────────
  const addStepToSection = useCallback((sectionId: string) => {
    saveSnapshot()
    const children = nodes.filter(n => n.parentId === sectionId)
    setNodes(n => [...n, {
      id: crypto.randomUUID(), type: 'recipeStepNode',
      parentId: sectionId, extent: 'parent' as const,
      position: { x: (SECTION_WIDTH - 260) / 2, y: children.length === 0 ? 80 : 80 + children.length * 130 },
      data: { title: 'New Step', description: '', duration: '', icon: '🍳' },
    }])
  }, [nodes, saveSnapshot, setNodes])

  const addConditionToSection = useCallback((sectionId: string) => {
    saveSnapshot()
    const children = nodes.filter(n => n.parentId === sectionId)
    setNodes(n => [...n, {
      id: crypto.randomUUID(), type: 'conditionNode',
      parentId: sectionId, extent: 'parent' as const,
      position: { x: (SECTION_WIDTH - 130) / 2, y: children.length === 0 ? 80 : 80 + children.length * 130 },
      data: { title: 'Condition?', description: '', yesLabel: 'Yes', noLabel: 'No' },
    }])
  }, [nodes, saveSnapshot, setNodes])

  const addFreeNode = useCallback((nodeType: string, sectionId?: string) => {
    saveSnapshot()

    if (sectionId) {
      const children = nodes.filter(n => n.parentId === sectionId)
      const position = {
        x: nodeType === 'conditionNode' ? (SECTION_WIDTH - 130) / 2 : (SECTION_WIDTH - 260) / 2,
        y: children.length === 0 ? 80 : 80 + children.length * 130,
      }
      const newNode = createNodeForSection(crypto.randomUUID(), nodeType, sectionId, position)
      setNodes(n => [...n, newNode])
      setCollapsedSections(s => {
        const next = new Set(s)
        next.delete(sectionId)
        return next
      })
      return
    }

    setNodes(n => [...n, createFreeNode(crypto.randomUUID(), nodeType, { x: 750, y: 300 })])
  }, [saveSnapshot, setNodes, nodes])

  const addSection = useCallback(() => {
    saveSnapshot()
    const centerY = 100
    setNodes(n => [...n, createSectionNode(`section-${crypto.randomUUID().slice(0, 8)}`, '📋 New Section', centerY, 180)])
  }, [saveSnapshot, setNodes])

  // ── Collapse ──────────────────────────────────────────────────────────────
  const toggleCollapse = useCallback((sectionId: string) => {
    setCollapsedSections(prev => {
      const next = new Set(prev)
      next.has(sectionId) ? next.delete(sectionId) : next.add(sectionId)
      return next
    })
  }, [])

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
        const response = await fetch(`${API_BASE_URL}/api/v1/flows/${recipe.id}`)
        if (!response.ok) return
        const result = await response.json()
        const flowData = result?.data
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
      const payload = {
        flowId: String(recipe.id),
        userId: 'demo-user',
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

      const response = await fetch(`${API_BASE_URL}/api/v1/flows/${payload.flowId}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })

      if (!response.ok) {
        throw new Error('Failed to save flow')
      }

      const result = await response.json()
      console.log('Flow saved:', result)
      alert('Flow saved successfully')
    } catch (error) {
      console.error(error)
      alert('Unable to save flow right now')
    }
  }, [nodes, edges])

  const visualizeFlow = useCallback(async () => {
    setVisualizing(true)
    setVisualizationResult(null)

    try {
      const payload = {
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

      const response = await fetch(`${API_BASE_URL}/api/v1/visualizations/${recipe.id}`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })

      if (!response.ok) {
        throw new Error('Failed to visualize flow')
      }

      const result = await response.json()
      setVisualizationResult(result?.data ?? null)
    } catch (error) {
      console.error(error)
      alert('Unable to visualize flow right now')
    } finally {
      setVisualizing(false)
    }
  }, [nodes, edges, recipe.id])

  // ── Display nodes ─────────────────────────────────────────────────────────
  const displayNodes = nodes.map(node => {
    if (node.type !== 'sectionNode') {
      if (node.parentId && collapsedSections.has(node.parentId)) return { ...node, hidden: true }
      return node
    }
    const isCollapsed = collapsedSections.has(node.id)
    const children    = nodes.filter(n => n.parentId === node.id)
    const stepCount   = children.length
    const dynamicH    = isCollapsed ? 72 : Math.max(280, 80 + stepCount * 130 + 60)
    return {
      ...node,
      style: { ...node.style, width: SECTION_WIDTH, height: dynamicH },
      data: {
        ...node.data, stepCount, collapsed: isCollapsed,
        onAddStep:        addStepToSection,
        onAddCondition:   addConditionToSection,
        onToggleCollapse: toggleCollapse,
      },
    }
  })

  // ── onConnect ─────────────────────────────────────────────────────────────
  const onConnect = useCallback((connection: any) => {
    saveSnapshot()
    const isSection = connection.source?.startsWith('section-') || connection.target?.startsWith('section-')
    const isYes = connection.sourceHandle === 'condition-yes'
    const isNo  = connection.sourceHandle === 'condition-no'
    const kind: EdgeKind = isSection ? 'section' : isYes ? 'yes' : isNo ? 'no' : 'step'
    const colors: Record<EdgeKind, string> = { step: '#94a3b8', section: '#6366f1', yes: '#16a34a', no: '#dc2626' }
    const color = colors[kind]
    setEdges(eds => addEdge({
      ...connection,
      type: 'smoothstep', animated: isSection,
      label: isYes ? 'Yes ✓' : isNo ? 'No ✗' : undefined,
      labelStyle: { fill: color, fontWeight: 700, fontSize: 11 },
      labelBgStyle: { fill: isYes ? '#f0fdf4' : isNo ? '#fff5f5' : 'transparent' },
      style: { stroke: color, strokeWidth: isSection ? 2.5 : 1.5, strokeDasharray: isSection ? '6 3' : undefined },
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
  return (
    <div style={{ display: 'flex', height: '100vh', background: '#f8fafc', fontFamily: "'DM Sans', system-ui, sans-serif" }}>
      <Sidebar 
        onAddNode={addFreeNode} 
        onAddSection={addSection}
        sections={nodes.filter(n => n.type === 'sectionNode')}
        selectedSectionId={selectedSectionId}
        onSectionSelect={setSelectedSectionId}
      />

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        {/* Topbar */}
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

        {/* Canvas */}
        <div style={{ flex: 1, position: 'relative' }}>
          {visualizationResult && (
            <div style={{ position: 'absolute', top: 12, left: 12, right: 12, zIndex: 5, pointerEvents: 'none' }}>
              <div style={{ display: 'inline-block', maxWidth: 480, padding: '10px 12px', borderRadius: 12, background: 'rgba(255,255,255,0.95)', border: '1px solid #bfdbfe', boxShadow: '0 10px 30px rgba(15,23,42,0.08)' }}>
                <div style={{ fontSize: 12, fontWeight: 700, color: '#1d4ed8' }}>📝 Visualization plan prepared</div>
                <div style={{ fontSize: 12, color: '#334155', marginTop: 4 }}>
                  {visualizationResult.clips?.length ?? 0} clips planned · final clip: {visualizationResult.finalClip?.clipId ?? 'n/a'}
                </div>
              </div>
            </div>
          )}
          <ReactFlow
            nodes={displayNodes} edges={edges}
            onNodesChange={onNodesChange} onEdgesChange={onEdgesChange}
            nodeTypes={nodeTypes} onConnect={onConnect}
            onNodeDragStart={handleDragStart} onNodeDragStop={handleDragStop}
            fitView fitViewOptions={{ padding: 0.12 }}
            connectionRadius={28}
            defaultEdgeOptions={{ type: 'smoothstep',
              style: { stroke: '#94a3b8', strokeWidth: 1.5 },
              markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8', width: 16, height: 16 } }}>
            <Background variant={BackgroundVariant.Dots} color="#e2e8f0" gap={20} size={1} />
            <Controls style={{ borderRadius: 10, border: '1px solid #e2e8f0', boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }} />
            <MiniMap style={{ borderRadius: 12, border: '1px solid #e2e8f0', boxShadow: '0 2px 8px rgba(0,0,0,0.06)' }}
              nodeColor={n => {
                if (n.type === 'conditionNode') return '#fde68a'
                return '#e2e8f0'
              }} />
          </ReactFlow>
        </div>
      </div>

      <PropertiesPanel node={selectedNode as any} sectionTitle={selectedSectionTitle}
        updateNodeField={updateNodeField} onDeleteNode={deleteNode} onDuplicateNode={duplicateNode} />

      {/* ── Export Modal ── */}
      {exportJson && (
        <div style={{ position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.6)',
          display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 9999 }}
          onClick={() => setExportJson(null)}>
          <div onClick={e => e.stopPropagation()}
            style={{ background: 'white', borderRadius: 16, width: 740, maxWidth: '95vw',
              maxHeight: '88vh', display: 'flex', flexDirection: 'column',
              boxShadow: '0 24px 80px rgba(0,0,0,0.25)', overflow: 'hidden' }}>
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