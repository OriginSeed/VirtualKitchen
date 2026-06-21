import React, { useCallback, useEffect, useRef, useState } from 'react'
import Sidebar from '../sidebar/Sidebar'
import PropertiesPanel from '../toolbar/PropertiesPanel'

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

// ─── Component ────────────────────────────────────────────────────────────────
export default function FlowCanvas() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const [collapsedSections, setCollapsedSections] = useState<Set<string>>(new Set())
  const [history, setHistory] = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [future,  setFuture]  = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [exportJson, setExportJson] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [selectedSectionId, setSelectedSectionId] = useState<string | null>(null)
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

  // ── Shared button styles ──────────────────────────────────────────────────
  const tbBtn = (extra: React.CSSProperties = {}): React.CSSProperties => ({
    padding: '6px 11px', borderRadius: 7, border: '1px solid #e2e8f0',
    background: 'white', color: '#475569', fontSize: 12, fontWeight: 600,
    cursor: 'pointer', display: 'flex', alignItems: 'center', gap: 4,
    ...extra,
  })

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
        <div style={{ height: 56, background: 'white', borderBottom: '1px solid #e2e8f0',
          display: 'flex', alignItems: 'center', justifyContent: 'space-between',
          padding: '0 16px', flexShrink: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <span style={{ fontWeight: 700, fontSize: 14, color: '#1e293b' }}>Spaghetti Aglio e Olio</span>
            <span style={{ fontSize: 13, color: '#94a3b8', cursor: 'pointer' }}>✏️</span>
            <span style={{ fontSize: 10, color: '#16a34a', background: '#f0fdf4',
              border: '1px solid #bbf7d0', borderRadius: 20, padding: '2px 8px' }}>
              ● All changes saved
            </span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 5 }}>
            <button onClick={undo} disabled={!history.length} title="Undo (Ctrl+Z)"
              style={{ width: 30, height: 30, borderRadius: 7, border: '1px solid #e2e8f0',
                background: history.length ? 'white' : '#f8fafc',
                color: history.length ? '#475569' : '#cbd5e1',
                cursor: history.length ? 'pointer' : 'default', fontSize: 13 }}>↩</button>
            <button onClick={redo} disabled={!future.length} title="Redo (Ctrl+Y)"
              style={{ width: 30, height: 30, borderRadius: 7, border: '1px solid #e2e8f0',
                background: future.length ? 'white' : '#f8fafc',
                color: future.length ? '#475569' : '#cbd5e1',
                cursor: future.length ? 'pointer' : 'default', fontSize: 13 }}>↪</button>

            <div style={{ width: 1, height: 22, background: '#e2e8f0', margin: '0 2px' }} />

            {/* ⭐ Add Section button */}
            <button onClick={addSection}
              style={tbBtn({ background: '#eff6ff', borderColor: '#bfdbfe', color: '#2563eb' })}>
              📋 + Section
            </button>

            {/* ⭐ Export JSON button */}
            <button onClick={exportFlow}
              style={tbBtn({ background: '#f0fdf4', borderColor: '#86efac', color: '#16a34a' })}>
              📤 Export JSON
            </button>

            <div style={{ width: 1, height: 22, background: '#e2e8f0', margin: '0 2px' }} />

            <button style={tbBtn()}>👁 Preview</button>
            <button style={tbBtn()}>💾 Save</button>
            <button style={{ padding: '6px 14px', borderRadius: 7, border: 'none',
              background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: 'white',
              fontSize: 12, fontWeight: 700, cursor: 'pointer', boxShadow: '0 2px 8px #6366f133' }}>
              🚀 Publish
            </button>
          </div>
        </div>

        {/* Canvas */}
        <div style={{ flex: 1, position: 'relative' }}>
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