import type { Edge, Node } from '@xyflow/react'

export type EdgeKind = 'step' | 'section' | 'yes' | 'no'

export const SECTION_WIDTH = 500
export const SECTION_MIN_HEIGHT = 180

export const createSectionNode = (id: string, title: string, y: number, h = SECTION_MIN_HEIGHT): Node => ({
  id, type: 'sectionNode',
  position: { x: 150, y },
  draggable: true, selectable: true, connectable: true,
  style: { width: SECTION_WIDTH, height: h },
  data: { title },
})

export const createRecipeStepNode = (id: string, position: { x: number; y: number }, parentId?: string): Node => ({
  id, type: 'recipeStepNode',
  position,
  ...(parentId ? { parentId, extent: 'parent' as const } : {}),
  style: { width: 300, height: 140 },
  data: { title: 'New Step', description: '', duration: '', icon: '🍳' },
})

export const createConditionNode = (id: string, position: { x: number; y: number }, parentId?: string): Node => ({
  id, type: 'conditionNode',
  position,
  ...(parentId ? { parentId, extent: 'parent' as const } : {}),
  style: { width: 150, height: 150 },
  data: { title: 'Condition?', description: '', yesLabel: 'Yes', noLabel: 'No' },
})

export const createNodeForSection = (id: string, nodeType: string, sectionId: string, position: { x: number; y: number }): Node =>
  nodeType === 'conditionNode'
    ? createConditionNode(id, position, sectionId)
    : createRecipeStepNode(id, position, sectionId)

export const createFreeNode = (id: string, nodeType: string, position: { x: number; y: number }): Node =>
  nodeType === 'conditionNode'
    ? createConditionNode(id, position)
    : createRecipeStepNode(id, position)

export const getSelectedSectionTitle = (nodes: Node[], selectedNode?: Node): string | undefined => {
  if (!selectedNode?.parentId) return undefined
  const section = nodes.find(n => n.id === selectedNode.parentId)
  return section?.data?.title ? String(section.data.title) : undefined
}

export const serializeFlowData = (nodes: Node[], edges: Edge[]) => {
  const nodesById = Object.fromEntries(nodes.map(n => [n.id, n]))

  const sections = nodes
    .filter(n => n.type === 'sectionNode')
    .map(section => {
      const children = nodes.filter(n => n.parentId === section.id)

      const sectionNodes = children.map(n => ({
        id: n.id,
        type: n.type,
        position: n.position,
        data: {
          title:       n.data?.title       ?? '',
          description: n.data?.description ?? '',
          duration:    n.data?.duration    ?? null,
          sectionId:   n.data?.sectionId   ?? null,
          yesLabel:    n.data?.yesLabel    ?? null,
          noLabel:     n.data?.noLabel     ?? null,
        },
      }))

      const sectionEdges = edges
        .filter(e => {
          const s = nodesById[e.source]
          const t = nodesById[e.target]
          return s && t && s.parentId === section.id && t.parentId === section.id
        })
        .map(e => ({
          id: e.id,
          source: e.source,
          target: e.target,
          sourceHandle: e.sourceHandle ?? null,
          targetHandle: e.targetHandle ?? null,
          label: e.label ?? null,
        }))

      return {
        id: section.id,
        title: section.data?.title ?? '',
        nodes: sectionNodes,
        edges: sectionEdges,
      }
    })

  const sectionConnections = edges
    .filter(e => {
      const s = nodesById[e.source]
      const t = nodesById[e.target]
      return (s && s.type === 'sectionNode') || (t && t.type === 'sectionNode')
    })
    .map(e => ({
      id: e.id,
      source: e.source,
      target: e.target,
      label: e.label ?? null,
      sourceHandle: e.sourceHandle ?? null,
      targetHandle: e.targetHandle ?? null,
    }))

  const orphanNodes = nodes
    .filter(n => !n.parentId && n.type !== 'sectionNode')
    .map(n => ({
      id: n.id,
      type: n.type,
      position: n.position,
      data: {
        title: n.data?.title ?? '',
        description: n.data?.description ?? '',
        duration: n.data?.duration ?? null,
      },
    }))

  return { meta: { name: 'Recipe Flow', exportedAt: new Date().toISOString(), version: '1.0' }, sections, sectionConnections, orphanNodes }
}
