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
import { nodeTypes } from '../../nodes/nodeTypes'

// ─── Section definitions ──────────────────────────────────────────────────────

const SECTION_WIDTH = 500

const makeSectionNode = (
  id: string,
  title: string,
  y: number,
  initialHeight = 320
): Node => ({
  id,
  type: 'sectionNode',
  position: { x: 150, y },
  draggable: true,
  selectable: true,
  connectable: true,
  style: { width: SECTION_WIDTH, height: initialHeight },
  data: { title },
})

const sections: Node[] = [
  makeSectionNode('section-prep', '🥗 Preparation', 0, 340),
  makeSectionNode('section-cook', '🍳 Cooking', 380, 340),
  makeSectionNode('section-serve', '🍽️ Serving', 760, 300),
]

const stepNodes: Node[] = [
  {
    id: '1',
    type: 'recipeStepNode',
    parentId: 'section-prep',
    extent: 'parent',
    position: { x: 120, y: 80 },
    data: { sectionId: 'prep', title: 'Wash Vegetables', description: 'Clean all ingredients', duration: '2 mins', icon: '🥬' },
  },
  {
    id: '2',
    type: 'recipeStepNode',
    parentId: 'section-prep',
    extent: 'parent',
    position: { x: 120, y: 210 },
    data: { sectionId: 'prep', title: 'Chop Ingredients', description: 'Finely dice vegetables', duration: '10 mins', icon: '🥕' },
  },
  {
    id: '3',
    type: 'recipeStepNode',
    parentId: 'section-cook',
    extent: 'parent',
    position: { x: 120, y: 80 },
    data: { sectionId: 'cook', title: 'Heat Pan', description: 'Heat pan on medium flame', duration: '1 min', icon: '🍳' },
  },
  {
    id: '4',
    type: 'recipeStepNode',
    parentId: 'section-cook',
    extent: 'parent',
    position: { x: 120, y: 210 },
    data: { sectionId: 'cook', title: 'Cook Ingredients', description: 'Sauté until golden', duration: '15 mins', icon: '🧄' },
  },
]

const initialNodes: Node[] = [...sections, ...stepNodes]

const makeEdge = (id: string, source: string, target: string, isSectionEdge = false): Edge => ({
  id,
  source,
  target,
  sourceHandle: isSectionEdge ? 'section-source' : undefined,
  targetHandle: isSectionEdge ? 'section-target' : undefined,
  type: 'smoothstep',
  animated: isSectionEdge,
  style: {
    stroke: isSectionEdge ? '#6366f1' : '#94a3b8',
    strokeWidth: isSectionEdge ? 2.5 : 1.5,
    strokeDasharray: isSectionEdge ? '6 3' : undefined,
  },
  markerEnd: {
    type: MarkerType.ArrowClosed,
    color: isSectionEdge ? '#6366f1' : '#94a3b8',
    width: 16,
    height: 16,
  },
})

const initialEdges: Edge[] = [
  makeEdge('e1-2', '1', '2'),
  makeEdge('e2-3', '2', '3'),
  makeEdge('e3-4', '3', '4'),
  makeEdge('e-sec-prep-cook', 'section-prep', 'section-cook', true),
  makeEdge('e-sec-cook-serve', 'section-cook', 'section-serve', true),
]

// ─── Component ────────────────────────────────────────────────────────────────

export default function FlowCanvas() {
  const [nodes, setNodes, onNodesChange] = useNodesState(initialNodes)
  const [edges, setEdges, onEdgesChange] = useEdgesState(initialEdges)
  const [collapsedSections, setCollapsedSections] = useState<Set<string>>(new Set())

  const [history, setHistory] = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const [future, setFuture] = useState<{ nodes: Node[]; edges: Edge[] }[]>([])
  const dragSnapshotRef = useRef<{ nodes: Node[]; edges: Edge[] } | null>(null)

  const selectedNode = nodes.find(n => n.selected)

  // ── Snapshot helpers ──────────────────────────────────────────────────────

  const saveSnapshot = useCallback(() => {
    setHistory(prev => [...prev, { nodes: structuredClone(nodes), edges: structuredClone(edges) }])
    setFuture([])
  }, [nodes, edges])

  const undo = useCallback(() => {
    if (!history.length) return
    const previous = history[history.length - 1]
    setFuture(prev => [...prev, { nodes: structuredClone(nodes), edges: structuredClone(edges) }])
    setNodes(previous.nodes)
    setEdges(previous.edges)
    setHistory(prev => prev.slice(0, -1))
  }, [history, nodes, edges, setNodes, setEdges])

  const redo = useCallback(() => {
    if (!future.length) return
    const next = future[future.length - 1]
    setHistory(prev => [...prev, { nodes: structuredClone(nodes), edges: structuredClone(edges) }])
    setNodes(next.nodes)
    setEdges(next.edges)
    setFuture(prev => prev.slice(0, -1))
  }, [future, nodes, edges, setNodes, setEdges])

  // ── Drag ──────────────────────────────────────────────────────────────────

  const handleDragStart = useCallback(() => {
    dragSnapshotRef.current = { nodes: structuredClone(nodes), edges: structuredClone(edges) }
  }, [nodes, edges])

  const handleDragStop = useCallback(() => {
    if (!dragSnapshotRef.current) return
    setHistory(prev => [...prev, structuredClone(dragSnapshotRef.current!)])
    setFuture([])
    dragSnapshotRef.current = null
  }, [])

  // ── Delete ────────────────────────────────────────────────────────────────

  const deleteSelected = useCallback(() => {
    saveSnapshot()
    setNodes(nds => nds.filter(n => !n.selected))
    setEdges(eds => eds.filter(e => !e.selected))
  }, [saveSnapshot, setNodes, setEdges])

  const deleteNode = useCallback((nodeId: string) => {
    saveSnapshot()
    setNodes(nds => nds.filter(n => n.id !== nodeId && n.parentId !== nodeId))
    setEdges(eds => eds.filter(e => e.source !== nodeId && e.target !== nodeId))
  }, [saveSnapshot, setNodes, setEdges])

  const duplicateNode = useCallback((nodeId: string) => {
    saveSnapshot()
    const original = nodes.find(n => n.id === nodeId)
    if (!original) return
    const newNode: Node = {
      ...structuredClone(original),
      id: crypto.randomUUID(),
      selected: false,
      position: { x: original.position.x + 20, y: original.position.y + 20 },
    }
    setNodes(nds => [...nds, newNode])
  }, [nodes, saveSnapshot, setNodes])

  // ── Add step ─────────────────────────────────────────────────────────────

  const addStepToSection = useCallback((sectionId: string) => {
    saveSnapshot()
    const children = nodes.filter(n => n.parentId === sectionId)
    const centerX = (SECTION_WIDTH - 260) / 2
    const newNode: Node = {
      id: crypto.randomUUID(),
      type: 'recipeStepNode',
      parentId: sectionId,
      extent: 'parent' as const,
      position: {
        x: centerX,
        y: children.length === 0 ? 80 : 80 + children.length * 130,
      },
      data: { title: 'New Step', description: '', duration: '', icon: '🍳' },
    }
    setNodes(nds => [...nds, newNode])
  }, [nodes, saveSnapshot, setNodes])

  const addStep = useCallback(() => {
    saveSnapshot()
    setNodes(nds => [
      ...nds,
      {
        id: crypto.randomUUID(),
        type: 'recipeStepNode',
        parentId: 'section-prep',
        extent: 'parent' as const,
        position: { x: 700, y: 200 },
        data: { title: 'New Step', description: '', duration: '', icon: '🍳' },
      },
    ])
  }, [saveSnapshot, setNodes])

  // ── Collapse / expand ─────────────────────────────────────────────────────

  const toggleCollapse = useCallback((sectionId: string) => {
    setCollapsedSections(prev => {
      const next = new Set(prev)
      if (next.has(sectionId)) next.delete(sectionId)
      else next.add(sectionId)
      return next
    })
  }, [])

  // ── Update node field ─────────────────────────────────────────────────────

  const updateNodeField = useCallback((nodeId: string, field: string, value: string) => {
    setNodes(nds =>
      nds.map(n =>
        n.id === nodeId ? { ...n, data: { ...n.data, [field]: value } } : n
      )
    )
  }, [setNodes])

  // ── Compute display nodes ─────────────────────────────────────────────────

  const displayNodes = nodes.map(node => {
    if (node.type !== 'sectionNode') {
      // Hide children of collapsed sections
      if (node.parentId && collapsedSections.has(node.parentId)) {
        return { ...node, hidden: true }
      }
      return node
    }

    // Section node
    const isCollapsed = collapsedSections.has(node.id)
    const children = nodes.filter(n => n.parentId === node.id)
    const stepCount = children.length
    const dynamicHeight = isCollapsed ? 72 : Math.max(280, 80 + stepCount * 130 + 60)

    return {
      ...node,
      style: { ...node.style, width: SECTION_WIDTH, height: dynamicHeight },
      data: {
        ...node.data,
        stepCount,
        collapsed: isCollapsed,
        onAddStep: addStepToSection,
        onToggleCollapse: toggleCollapse,
      },
    }
  })

  // ── onConnect ─────────────────────────────────────────────────────────────

  const onConnect = useCallback(
    (connection: any) => {
      saveSnapshot()
      const isSectionConnection =
        connection.source?.startsWith('section-') || connection.target?.startsWith('section-')
      setEdges(eds =>
        addEdge(
          {
            ...connection,
            type: 'smoothstep',
            animated: isSectionConnection,
            style: {
              stroke: isSectionConnection ? '#6366f1' : '#94a3b8',
              strokeWidth: isSectionConnection ? 2.5 : 1.5,
              strokeDasharray: isSectionConnection ? '6 3' : undefined,
            },
            markerEnd: {
              type: MarkerType.ArrowClosed,
              color: isSectionConnection ? '#6366f1' : '#94a3b8',
              width: 16,
              height: 16,
            },
          },
          eds
        )
      )
    },
    [saveSnapshot, setEdges]
  )

  // ── Keyboard shortcuts ────────────────────────────────────────────────────

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      const tag = (e.target as HTMLElement)?.tagName
      if (tag === 'INPUT' || tag === 'TEXTAREA') return

      if (e.key === 'Delete' || e.key === 'Backspace') deleteSelected()
      if ((e.ctrlKey || e.metaKey) && e.key === 'z') { e.preventDefault(); undo() }
      if ((e.ctrlKey || e.metaKey) && e.key === 'y') { e.preventDefault(); redo() }
    }
    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [deleteSelected, undo, redo])

  return (
    <div
      style={{
        display: 'flex',
        height: '100vh',
        background: '#f8fafc',
        fontFamily: "'DM Sans', system-ui, sans-serif",
      }}
    >
      <Sidebar onAddStep={addStep} />

      {/* Topbar */}
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <div
          style={{
            height: 56,
            background: 'white',
            borderBottom: '1px solid #e2e8f0',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            padding: '0 20px',
            flexShrink: 0,
          }}
        >
          <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
            <span style={{ fontWeight: 700, fontSize: 15, color: '#1e293b' }}>Spaghetti Aglio e Olio</span>
            <span style={{ fontSize: 14, color: '#94a3b8', cursor: 'pointer' }}>✏️</span>
            <span
              style={{
                display: 'flex',
                alignItems: 'center',
                gap: 4,
                fontSize: 11,
                color: '#16a34a',
                background: '#f0fdf4',
                border: '1px solid #bbf7d0',
                borderRadius: 20,
                padding: '2px 8px',
              }}
            >
              ● All changes saved
            </span>
          </div>

          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            {/* Undo/redo */}
            <button
              onClick={undo}
              disabled={!history.length}
              style={{
                width: 32,
                height: 32,
                borderRadius: 8,
                border: '1px solid #e2e8f0',
                background: history.length ? 'white' : '#f8fafc',
                color: history.length ? '#475569' : '#cbd5e1',
                cursor: history.length ? 'pointer' : 'default',
                fontSize: 14,
              }}
              title="Undo (Ctrl+Z)"
            >
              ↩
            </button>
            <button
              onClick={redo}
              disabled={!future.length}
              style={{
                width: 32,
                height: 32,
                borderRadius: 8,
                border: '1px solid #e2e8f0',
                background: future.length ? 'white' : '#f8fafc',
                color: future.length ? '#475569' : '#cbd5e1',
                cursor: future.length ? 'pointer' : 'default',
                fontSize: 14,
              }}
              title="Redo (Ctrl+Y)"
            >
              ↪
            </button>

            <div style={{ width: 1, height: 24, background: '#e2e8f0', margin: '0 4px' }} />

            <button
              style={{
                padding: '6px 14px',
                borderRadius: 8,
                border: '1px solid #e2e8f0',
                background: 'white',
                color: '#475569',
                fontSize: 13,
                fontWeight: 600,
                cursor: 'pointer',
              }}
            >
              👁 Preview
            </button>
            <button
              style={{
                padding: '6px 14px',
                borderRadius: 8,
                border: '1px solid #e2e8f0',
                background: 'white',
                color: '#475569',
                fontSize: 13,
                fontWeight: 600,
                cursor: 'pointer',
              }}
            >
              💾 Save
            </button>
            <button
              style={{
                padding: '6px 16px',
                borderRadius: 8,
                border: 'none',
                background: 'linear-gradient(135deg, #6366f1, #8b5cf6)',
                color: 'white',
                fontSize: 13,
                fontWeight: 700,
                cursor: 'pointer',
                boxShadow: '0 2px 8px #6366f133',
              }}
            >
              🚀 Publish
            </button>
          </div>
        </div>

        {/* Canvas */}
        <div style={{ flex: 1, position: 'relative' }}>
          <ReactFlow
            nodes={displayNodes}
            edges={edges}
            onNodesChange={onNodesChange}
            onEdgesChange={onEdgesChange}
            nodeTypes={nodeTypes}
            onConnect={onConnect}
            onNodeDragStart={handleDragStart}
            onNodeDragStop={handleDragStop}
            fitView
            fitViewOptions={{ padding: 0.15 }}
            defaultEdgeOptions={{
              type: 'smoothstep',
              style: { stroke: '#94a3b8', strokeWidth: 1.5 },
              markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8', width: 16, height: 16 },
            }}
          >
            <Background variant={BackgroundVariant.Dots} color="#e2e8f0" gap={20} size={1} />
            <Controls
              style={{
                borderRadius: 10,
                border: '1px solid #e2e8f0',
                boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
              }}
            />
            <MiniMap
              style={{
                borderRadius: 12,
                border: '1px solid #e2e8f0',
                boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
              }}
              nodeColor={(n) => {
                if (n.id.includes('prep')) return '#dcfce7'
                if (n.id.includes('cook')) return '#fef3c7'
                if (n.id.includes('serve')) return '#dbeafe'
                return '#e2e8f0'
              }}
            />
          </ReactFlow>
        </div>
      </div>

      <PropertiesPanel
        node={selectedNode as any}
        updateNodeField={updateNodeField}
        onDeleteNode={deleteNode}
        onDuplicateNode={duplicateNode}
      />
    </div>
  )
}