import ConditionNode from './ConditionNode'
import ParallelEndNode from './ParallelEndNode'
import ParallelStartNode from './ParallelStartNode'
import RecipeStepNode from './RecipeStepNode'
import StartNode from './StartNode'
import { FLOW_NODE_TYPES } from '../model/flowNodeModel'

export const nodeTypes = {
  startNode: StartNode,
  [FLOW_NODE_TYPES.recipeStep]: RecipeStepNode,
  [FLOW_NODE_TYPES.condition]: ConditionNode,
  [FLOW_NODE_TYPES.parallelStart]: ParallelStartNode,
  [FLOW_NODE_TYPES.parallelEnd]: ParallelEndNode,
}