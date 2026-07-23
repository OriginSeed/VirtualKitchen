import type { Edge, Node } from '@xyflow/react'
import {
  FLOW_NODE_TYPES,
  type FlowNodeType,
  isConditionNode,
  isParallelEndNode,
  isParallelStartNode,
  isRecipeStepNode,
} from '../../model/flowNodeModel'
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
  type: FLOW_NODE_TYPES.recipeStep,
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
  type: FLOW_NODE_TYPES.condition,
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
  type: FLOW_NODE_TYPES.parallelStart,
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
  type: FLOW_NODE_TYPES.parallelEnd,
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

export const createNodeForType = (id: string, nodeType: FlowNodeType, position: { x: number; y: number }): Node =>
  nodeType === FLOW_NODE_TYPES.condition
    ? createConditionNode(id, position)
    : nodeType === FLOW_NODE_TYPES.parallelStart
      ? createParallelStartNode(id, position)
      : nodeType === FLOW_NODE_TYPES.parallelEnd
        ? createParallelEndNode(id, position)
        : createRecipeStepNode(id, position)

export const createFreeNode = (id: string, nodeType: FlowNodeType, position: { x: number; y: number }): Node =>
  createNodeForType(id, nodeType, position)

const normalizeNodeDataForSerialize = (node: Node) => {
  if (isRecipeStepNode(node)) {
    return normalizeStepNodeData(node.data)
  }

  if (isConditionNode(node)) {
    return normalizeConditionNodeData(node.data)
  }

  if (isParallelStartNode(node)) {
    return normalizeParallelNodeData(node.data, 'start')
  }

  if (isParallelEndNode(node)) {
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
    .filter(n => n.type !== FLOW_NODE_TYPES.section)
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
  const normalizedData = node.type === FLOW_NODE_TYPES.recipeStep
    ? normalizeStepNodeData(baseData)
    : node.type === FLOW_NODE_TYPES.condition
      ? normalizeConditionNodeData(baseData)
      : node.type === FLOW_NODE_TYPES.parallelStart
        ? normalizeParallelNodeData(baseData, 'start')
        : node.type === FLOW_NODE_TYPES.parallelEnd
          ? normalizeParallelNodeData(baseData, 'end')
      : baseData

  return {
    id: node.id,
    type: node.type ?? FLOW_NODE_TYPES.recipeStep,
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
    data: node.type === FLOW_NODE_TYPES.recipeStep
      ? normalizeStepNodeData(node.data)
      : node.type === FLOW_NODE_TYPES.condition
        ? normalizeConditionNodeData(node.data)
        : node.type === FLOW_NODE_TYPES.parallelStart
          ? normalizeParallelNodeData(node.data, 'start')
          : node.type === FLOW_NODE_TYPES.parallelEnd
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

export const normalizeFlowEdges = (edges: FlowData['edges']): Edge[] =>
  (edges ?? []).map((edge) => ({
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
