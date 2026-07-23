import ConditionNode from './ConditionNode'
import ParallelEndNode from './ParallelEndNode'
import ParallelStartNode from './ParallelStartNode'
import RecipeStepNode from './RecipeStepNode'
import StartNode from './StartNode'

export const nodeTypes = {
  startNode: StartNode,
  recipeStepNode: RecipeStepNode,
  conditionNode: ConditionNode,
  parallelStartNode: ParallelStartNode,
  parallelEndNode: ParallelEndNode,
}