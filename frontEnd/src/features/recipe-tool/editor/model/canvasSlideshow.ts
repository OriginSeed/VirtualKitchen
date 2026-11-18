import type { Edge, Node } from '@xyflow/react'
import type { Process } from '../../../../types/process'
import { isRecipeStepNode } from '../../model/recipeNodeTypes'
import { getProcessReadingOrder } from '../../model/processReadingOrder'
import {
  getActionOnIngredientDisplayName,
  getRecipeStepDurationLabel,
  getRecipeStepTemperatureLabel,
  getRecipeStepTitle,
  normalizeRecipeStepNodeData,
  stepAnnotationLabelResolver,
} from '../../model/recipeStepData'
import { toPlainText } from '../../model/stepAnnotations'
import { getStepOutputLabel, type StepOutputGraph } from '../../model/recipeStepOutputs'
import { describePreparedReference } from '../../model/preparationFlows'
import type { SlideshowStep } from '../../walkthrough/RecipeVisualizationSlideshow'
import { flowToProcess } from '../adapters/canvasFlow'

/**
 * Walkthrough slides for the process on the canvas, in reading order — the same order the Recipe Process
 * page shows the steps in (not the order nodes were added). A step not visualized yet gets a text-only slide.
 */
export const buildCanvasSlideshowSteps = (
  process: Process,
  nodes: Node[],
  edges: Edge[],
  recipeProcesses: Process[],
  stepOutputGraph: StepOutputGraph,
): SlideshowStep[] => {
  const readingOrder = new Map(getProcessReadingOrder(flowToProcess(process, nodes, edges)).map((node, index) => [node.id, index]))
  const stepNodes = nodes.filter(isRecipeStepNode)
    .sort((a, b) => (readingOrder.get(a.id) ?? 0) - (readingOrder.get(b.id) ?? 0))
  return stepNodes.map((node, index) => {
    const normalized = normalizeRecipeStepNodeData(node.data)
    const { step } = normalized
    const names = {
      preparedName: (entry: { processId: number; stepId?: string }) => describePreparedReference(recipeProcesses, entry).label,
      stepOutputLabel: (stepId: string) => getStepOutputLabel(stepOutputGraph, stepId),
    }
    const ingredientNames = [
      ...step.actionOn.steps.map((entry) => getStepOutputLabel(stepOutputGraph, entry.stepId)).filter(Boolean),
      ...step.actionOn.ingredients.map(getActionOnIngredientDisplayName),
    ]
    const subprocessNames = step.actionOn.processes
      .map((entry) => describePreparedReference(recipeProcesses, entry).label)
      .filter(Boolean)
    // The action description is the slide's instruction text; the rest are supporting details.
    const descriptionParts = [
      step.summary.trim(),
      ingredientNames.length > 0 ? `On: ${ingredientNames.join(', ')}` : '',
      subprocessNames.length > 0 ? `Using: ${subprocessNames.join(', ')}` : '',
      getRecipeStepDurationLabel(step),
      getRecipeStepTemperatureLabel(step),
      step.expectedOutput ? `Expected: ${step.expectedOutput}` : '',
    ].filter(Boolean)

    return {
      id: node.id,
      title: getRecipeStepTitle(step, names),
      instruction: toPlainText(step.actionDescription, stepAnnotationLabelResolver(names)).trim() || undefined,
      description: descriptionParts.join(' · ') || undefined,
      imageUrl: normalized.visualization?.imageUrl,
      stepNumber: index + 1,
    }
  })
}
