import type { Edge, Node } from '@xyflow/react'
import {
  createDefaultConditionFields,
  createDefaultParallelFields,
  createDefaultStepFields,
  getConditionNodeTitle,
  getParallelNodeTitle,
  getStepNodeIcon,
  getStepNodeTitle,
  normalizeConditionNodeData,
  normalizeParallelNodeData,
  normalizeStepNodeData,
  type FlowData,
  type FlowEdgePayload,
  type FlowNodePayload,
} from '../../../../types/recipeFlow'

export type EdgeKind = 'step' | 'yes' | 'no' | 'parallel'

export const createRecipeStepNode = (id: string, position: { x: number; y: number }): Node => ({
  id,
  type: 'recipeStepNode',
  position,
  draggable: true,
  selectable: true,
  connectable: true,
  style: { width: 320, height: 190 },
  data: {
    title: getStepNodeTitle(''),
    icon: getStepNodeIcon(''),
    step: createDefaultStepFields(),
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
    title: getConditionNodeTitle(''),
    condition: createDefaultConditionFields(),
    sectionId: null,
  },
})

export const createParallelStartNode = (id: string, position: { x: number; y: number }): Node => ({
  id,
  type: 'parallelStartNode',
  position,
  draggable: true,
  selectable: true,
  connectable: true,
  style: { width: 180, height: 92 },
  data: {
    title: getParallelNodeTitle('start', ''),
    parallel: createDefaultParallelFields('start'),
    sectionId: null,
  },
})

export const createParallelEndNode = (id: string, position: { x: number; y: number }): Node => ({
  id,
  type: 'parallelEndNode',
  position,
  draggable: true,
  selectable: true,
  connectable: true,
  style: { width: 180, height: 92 },
  data: {
    title: getParallelNodeTitle('end', ''),
    parallel: createDefaultParallelFields('end'),
    sectionId: null,
  },
})

export const createNodeForType = (id: string, nodeType: string, position: { x: number; y: number }): Node =>
  nodeType === 'conditionNode'
    ? createConditionNode(id, position)
    : nodeType === 'parallelStartNode'
      ? createParallelStartNode(id, position)
      : nodeType === 'parallelEndNode'
        ? createParallelEndNode(id, position)
        : createRecipeStepNode(id, position)

export const createFreeNode = (id: string, nodeType: string, position: { x: number; y: number }): Node =>
  createNodeForType(id, nodeType, position)

const normalizeNodeDataForSerialize = (node: Node) => {
  if (node.type === 'recipeStepNode') {
    return normalizeStepNodeData(node.data)
  }

  if (node.type === 'conditionNode') {
    return normalizeConditionNodeData(node.data)
  }

  if (node.type === 'parallelStartNode') {
    return normalizeParallelNodeData(node.data, 'start')
  }

  if (node.type === 'parallelEndNode') {
    return normalizeParallelNodeData(node.data, 'end')
  }

  return {
    title: node.data?.title ?? '',
    description: node.data?.description ?? '',
    yesLabel: node.data?.yesLabel ?? null,
    noLabel: node.data?.noLabel ?? null,
  }
}

export const serializeFlowData = (nodes: Node[], edges: Edge[]) => {
  const nodePayload = nodes
    .filter(n => n.type !== 'sectionNode')
    .map(n => ({
      id: n.id,
      type: n.type,
      position: n.position,
      data: normalizeNodeDataForSerialize(n),
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
    meta: { name: 'Recipe Flow', exportedAt: new Date().toISOString(), version: '2.0' },
    nodes: nodePayload,
    edges: edgePayload,
  }
}

export const normalizeFlowNode = (node: FlowNodePayload): Node => {
  const baseData = node.data ?? {}
  const normalizedData = node.type === 'recipeStepNode'
    ? normalizeStepNodeData(baseData)
    : node.type === 'conditionNode'
      ? normalizeConditionNodeData(baseData)
      : node.type === 'parallelStartNode'
        ? normalizeParallelNodeData(baseData, 'start')
        : node.type === 'parallelEndNode'
          ? normalizeParallelNodeData(baseData, 'end')
      : baseData

  return {
    id: node.id,
    type: node.type ?? 'recipeStepNode',
    position: node.position ?? { x: 0, y: 0 },
    data: normalizedData,
    measured: node.measured as Node['measured'],
    parentId: node.parentId,
    extent: node.extent as Node['extent'],
    draggable: node.draggable,
    selectable: node.selectable,
    deletable: node.deletable,
    style: node.style as Node['style'],
  }
}

export const createFlowDataPayload = (nodes: Node[], edges: Edge[]): FlowData => {
  const normalizedNodes: FlowNodePayload[] = nodes.map((node) => ({
    id: node.id,
    type: node.type,
    position: node.position,
    measured: node.measured,
    parentId: node.parentId,
    extent: node.extent,
    draggable: node.draggable,
    selectable: node.selectable,
    deletable: node.deletable,
    style: node.style as Record<string, unknown>,
    data: node.type === 'recipeStepNode'
      ? normalizeStepNodeData(node.data)
      : node.type === 'conditionNode'
        ? normalizeConditionNodeData(node.data)
        : node.type === 'parallelStartNode'
          ? normalizeParallelNodeData(node.data, 'start')
          : node.type === 'parallelEndNode'
            ? normalizeParallelNodeData(node.data, 'end')
        : (node.data as Record<string, unknown>),
  }))

  const normalizedEdges: FlowEdgePayload[] = edges.map((edge) => ({
    id: edge.id,
    source: edge.source,
    target: edge.target,
    sourceHandle: edge.sourceHandle,
    targetHandle: edge.targetHandle,
    type: edge.type,
    animated: edge.animated,
    style: edge.style as Record<string, unknown>,
    data: edge.data as Record<string, unknown>,
    label: typeof edge.label === 'string' ? edge.label : null,
  }))

  return {
    nodes: normalizedNodes,
    edges: normalizedEdges,
  }
}
