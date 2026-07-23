import type { Node } from '@xyflow/react'

export const FLOW_NODE_TYPES = {
  recipeStep: 'recipeStepNode',
  condition: 'conditionNode',
  parallelStart: 'parallelStartNode',
  parallelEnd: 'parallelEndNode',
  section: 'sectionNode',
} as const

export type FlowNodeType = (typeof FLOW_NODE_TYPES)[keyof typeof FLOW_NODE_TYPES]

export const isRecipeStepNode = (node: Pick<Node, 'type'>) => node.type === FLOW_NODE_TYPES.recipeStep
export const isConditionNode = (node: Pick<Node, 'type'>) => node.type === FLOW_NODE_TYPES.condition
export const isParallelStartNode = (node: Pick<Node, 'type'>) => node.type === FLOW_NODE_TYPES.parallelStart
export const isParallelEndNode = (node: Pick<Node, 'type'>) => node.type === FLOW_NODE_TYPES.parallelEnd
export const isParallelNode = (node: Pick<Node, 'type'>) =>
  isParallelStartNode(node) || isParallelEndNode(node)

export const getFlowNodeDisplayLabel = (node: Pick<Node, 'type' | 'data'>) => {
  const title = typeof node.data?.title === 'string' ? node.data.title.trim() : ''
  if (title) return title

  if (isRecipeStepNode(node)) return 'Step'
  if (isConditionNode(node)) return 'Condition'
  if (isParallelStartNode(node)) return 'Parallel Start'
  if (isParallelEndNode(node)) return 'Parallel End'
  return 'Untitled'
}

export const getFlowMetaCounts = (nodes: Node[]) => ({
  stepCount: nodes.filter(isRecipeStepNode).length,
  conditionCount: nodes.filter(isConditionNode).length,
  parallelCount: nodes.filter(isParallelNode).length,
})
