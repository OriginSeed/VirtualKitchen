/**
 * Serialises a process of the working session into the shape an AI EDIT request sends
 * (EditTargetProcess, recipe/dto/EditTargetProcessDTO.java): its nodes in reading order, each with
 * its real node id, the step number the editor shows, its semantic content in the same shape AI
 * generation returns (the reverse of recipeProcessGenerationConverter.ts's buildStepNode /
 * buildConditionNode) and its outgoing connections. The backend replaces the ids with short aliases
 * before the model sees them.
 */

import type { Process, ProcessNode } from '../../../../types/process'
import type { EditTargetNode, EditTargetProcess, GeneratedRecipeStep } from '../../../../types/recipe'
import { CUSTOM_FLAME_LEVEL_ID } from '../../../../shared/catalog/flameLevelCatalog'
import { buildDurationLabel } from '../../../../shared/catalog/stepFieldCatalog'
import { normalizeConditionNodeData } from '../../model/recipeConditionData'
import { normalizeRecipeStepNodeData } from '../../model/recipeStepData'
import { getProcessReadingOrder } from '../../model/processReadingOrder'
import { encodePreparedReference, getPreparedOutputs } from '../../model/preparationFlows'
import { CONDITION_HANDLES } from '../../model/recipeNodeTypes'

const YES_HANDLE = CONDITION_HANDLES.yes
const NO_HANDLE = CONDITION_HANDLES.no

const orNull = (value: string) => value.trim() || null

const stepContent = (node: ProcessNode): GeneratedRecipeStep => {
  const { step } = normalizeRecipeStepNodeData(node.data)
  const temperature = Number.parseFloat(step.temperatureValue)
  const customFlame = step.flameLevelId === CUSTOM_FLAME_LEVEL_ID
  return {
    nodeType: 'STEP',
    action: orNull(step.action),
    customActionName: orNull(step.customActionName),
    actionOn: {
      ingredients: step.actionOn.ingredients.map((ingredient) => ({
        ingredientId: ingredient.ingredientId,
        quantity: ingredient.quantity,
        unit: orNull(ingredient.unit),
        customUnit: orNull(ingredient.customUnit ?? ''),
        preparationStyle: orNull(ingredient.preparationStyleId ?? ''),
        customPreparationStyle: orNull(ingredient.customPreparationStyle ?? ''),
        customIngredientName: orNull(ingredient.customIngredientName ?? ''),
        notes: orNull(ingredient.notes ?? ''),
      })),
      processes: step.actionOn.processes.map(encodePreparedReference),
      steps: step.actionOn.steps.map((entry) => entry.stepId),
    },
    // A custom heat level has no catalog id; its wording lives in the description instead.
    actionDescription: customFlame && step.customFlameLevel
      ? `${step.actionDescription} (heat: ${step.customFlameLevel})`.trim()
      : step.actionDescription,
    expectedOutput: step.expectedOutput,
    title: orNull(step.title),
    summary: orNull(step.summary),
    temperatureValue: Number.isFinite(temperature) ? temperature : null,
    temperatureUnit: Number.isFinite(temperature) ? orNull(step.temperatureUnit) : null,
    flameLevel: customFlame ? null : orNull(step.flameLevelId),
    duration: orNull(buildDurationLabel(step.durationValue, step.durationUnit)),
    repeatInterval: orNull(buildDurationLabel(step.repeatIntervalValue, step.repeatIntervalUnit)),
  }
}

const conditionContent = (node: ProcessNode): GeneratedRecipeStep => {
  const data = normalizeConditionNodeData(node.data)
  return {
    nodeType: 'CONDITION',
    title: data.condition.question || data.title,
    expectedResult: data.condition.expectedResult,
    actionDescription: data.condition.notes,
    expectedOutput: '',
  }
}

export const serializeProcessForEdit = (process: Process, allProcesses: Process[]): EditTargetProcess => {
  const stepNumbers = new Map(process.nodes.filter((node) => node.kind === 'STEP').map((node, index) => [node.id, index + 1]))

  const nodes: EditTargetNode[] = getProcessReadingOrder(process).map((node) => {
    const outgoing = process.edges.filter((edge) => edge.source === node.id)
    if (node.kind === 'CONDITION') {
      return {
        nodeId: node.id,
        content: conditionContent(node),
        yesNodeId: outgoing.find((edge) => edge.sourceHandle === YES_HANDLE)?.target ?? null,
        noNodeId: outgoing.find((edge) => edge.sourceHandle === NO_HANDLE)?.target ?? null,
      }
    }
    return {
      nodeId: node.id,
      stepNumber: stepNumbers.get(node.id) ?? null,
      content: stepContent(node),
      nextNodeIds: outgoing.filter((edge) => edge.sourceHandle !== NO_HANDLE).map((edge) => edge.target),
    }
  })

  return {
    processId: process.id,
    name: process.name,
    nodes,
    // What a main step can use: each Preparation step output, by name. Preparation steps use nothing.
    subprocesses: process.type === 'MAIN'
      ? getPreparedOutputs(allProcesses).map((output) => ({ processId: output.processId, stepId: output.stepId, name: output.label }))
      : [],
  }
}
