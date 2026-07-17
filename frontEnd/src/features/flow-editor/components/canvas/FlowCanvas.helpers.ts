import type { Edge, Node } from '@xyflow/react'

export type EdgeKind = 'step' | 'yes' | 'no'

export const createRecipeStepNode = (id: string, position: { x: number; y: number }): Node => ({
  id,
  type: 'recipeStepNode',
  position,
  draggable: true,
  selectable: true,
  connectable: true,
  style: { width: 320, height: 140 },
  data: {
    title: 'New Step',
    description: '',
    duration: '',
    icon: '🍳',
    sectionId: null,
  },
})

export const createConditionNode = (id: string, position: { x: number; y: number }): Node => ({
  id,
  type: 'conditionNode',
  position,
  draggable: true,
  selectable: true,
  connectable: true,
  style: { width: 160, height: 160 },
  data: {
    title: 'Condition?',
    description: '',
    yesLabel: 'Yes',
    noLabel: 'No',
    sectionId: null,
  },
})

export const createNodeForType = (id: string, nodeType: string, position: { x: number; y: number }): Node =>
  nodeType === 'conditionNode'
    ? createConditionNode(id, position)
    : createRecipeStepNode(id, position)

export const createFreeNode = (id: string, nodeType: string, position: { x: number; y: number }): Node =>
  createNodeForType(id, nodeType, position)

export const serializeFlowData = (nodes: Node[], edges: Edge[]) => {
  const nodePayload = nodes
    .filter(n => n.type !== 'sectionNode')
    .map(n => ({
      id: n.id,
      type: n.type,
      position: n.position,
      data: {
        title: n.data?.title ?? '',
        description: n.data?.description ?? '',
        duration: n.data?.duration ?? null,
        icon: n.data?.icon ?? null,
        yesLabel: n.data?.yesLabel ?? null,
        noLabel: n.data?.noLabel ?? null,
      },
    }))

  const edgePayload = edges.map(e => ({
    id: e.id,
    source: e.source,
    target: e.target,
    sourceHandle: e.sourceHandle ?? null,
    targetHandle: e.targetHandle ?? null,
    label: e.label ?? null,
    type: e.type ?? 'smoothstep',
  }))

  return {
    meta: { name: 'Recipe Flow', exportedAt: new Date().toISOString(), version: '1.1' },
    nodes: nodePayload,
    edges: edgePayload,
  }
}
