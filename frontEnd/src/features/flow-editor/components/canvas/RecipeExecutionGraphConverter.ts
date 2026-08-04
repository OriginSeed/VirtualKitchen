import {
  normalizeStepNodeData,
  type FlowData,
  type FlowEdgePayload,
  type FlowNodePayload,
  type RecipeExecutionModel,
  type RecipeExecutionStep,
} from '../../../../types/recipeFlow'
import { FLOW_NODE_TYPES } from '../../model/flowNodeModel'

const STEP_NODE_WIDTH = 320
const STEP_NODE_HEIGHT = 190
const HORIZONTAL_GAP = 380
const VERTICAL_GAP = 260
const ORIGIN_X = 120
const ORIGIN_Y = 80

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === 'object' && value !== null

const toStringValue = (value: unknown) => {
  if (value == null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return ''
}

const toExecutionStep = (entry: unknown, index: number): RecipeExecutionStep | null => {
  if (!isRecord(entry)) return null

  return {
    id: toStringValue(entry.id).trim() || `step-${index + 1}`,
    action: toStringValue(entry.action),
    ingredientId: toStringValue(entry.ingredientId),
    quantity: toStringValue(entry.quantity),
    unit: toStringValue(entry.unit),
    style: toStringValue(entry.style),
    duration: toStringValue(entry.duration),
    flame: toStringValue(entry.flame),
    temperature: toStringValue(entry.temperature),
    notes: toStringValue(entry.notes),
  }
}

const buildTopologicalOrder = (steps: RecipeExecutionStep[], edges: FlowEdgePayload[]) => {
  const stepIds = steps.map((step) => step.id)
  const indegree = new Map<string, number>(stepIds.map((id) => [id, 0]))
  const adjacency = new Map<string, string[]>()

  edges.forEach((edge) => {
    if (!indegree.has(edge.source) || !indegree.has(edge.target)) return
    indegree.set(edge.target, (indegree.get(edge.target) ?? 0) + 1)
    const current = adjacency.get(edge.source)
    if (current) {
      current.push(edge.target)
    } else {
      adjacency.set(edge.source, [edge.target])
    }
  })

  const queue: string[] = []
  indegree.forEach((value, id) => {
    if (value === 0) queue.push(id)
  })

  const ordered: string[] = []
  while (queue.length > 0) {
    const id = queue.shift() as string
    ordered.push(id)

    const targets = adjacency.get(id) ?? []
    targets.forEach((target) => {
      const next = (indegree.get(target) ?? 0) - 1
      indegree.set(target, next)
      if (next === 0) queue.push(target)
    })
  }

  if (ordered.length < steps.length) {
    stepIds.forEach((id) => {
      if (!ordered.includes(id)) {
        ordered.push(id)
      }
    })
  }

  return ordered
}

const buildStepNodePayload = (step: RecipeExecutionStep, stepNumber: number, x: number, y: number): FlowNodePayload => {
  const normalized = normalizeStepNodeData({
    title: step.action,
    stepNumber,
    step: {
      action: step.action,
      ingredientId: step.ingredientId,
      customIngredientName: step.ingredientId,
      quantity: step.quantity,
      unitId: step.unit,
      customUnit: step.unit,
      unit: step.unit,
      preparationStyleId: step.style,
      customPreparationStyle: step.style,
      preparationStyle: step.style,
      flameLevelId: step.flame,
      customFlameLevel: step.flame,
      flameLevel: step.flame,
      temperature: step.temperature,
      duration: step.duration,
      notes: step.notes,
    },
  })

  return {
    id: step.id,
    type: FLOW_NODE_TYPES.recipeStep,
    position: { x, y },
    style: { width: STEP_NODE_WIDTH, height: STEP_NODE_HEIGHT },
    data: {
      ...normalized,
      stepNumber,
    },
  }
}

export const convertRecipeExecutionModelToFlowData = (executionModel: RecipeExecutionModel): FlowData => {
  const steps = (executionModel.steps ?? []).filter((step) => step && step.id.trim())

  const edges: FlowEdgePayload[] = (executionModel.edges ?? [])
    .filter((edge) => edge && edge.from.trim() && edge.to.trim())
    .map((edge, index) => ({
      id: `generated-edge-${index + 1}-${edge.from}-${edge.to}`,
      source: edge.from,
      target: edge.to,
    }))

  const orderedIds = buildTopologicalOrder(steps, edges)
  const stepById = new Map(steps.map((step) => [step.id, step]))
  const columns = Math.max(1, Math.ceil(Math.sqrt(Math.max(1, steps.length))))

  const nodes: FlowNodePayload[] = orderedIds
    .map((stepId) => stepById.get(stepId))
    .filter((step): step is RecipeExecutionStep => !!step)
    .map((step, index) => {
      const col = index % columns
      const row = Math.floor(index / columns)
      const x = ORIGIN_X + col * HORIZONTAL_GAP
      const y = ORIGIN_Y + row * VERTICAL_GAP
      return buildStepNodePayload(step, index + 1, x, y)
    })

  return {
    nodes,
    edges,
  }
}

export const normalizeRecipeExecutionModel = (value: unknown): RecipeExecutionModel => {
  if (!isRecord(value) || !Array.isArray(value.steps) || !Array.isArray(value.edges)) {
    throw new Error('Generated execution response is invalid.')
  }

  const steps = value.steps
    .map((entry, index) => toExecutionStep(entry, index))
    .filter((step): step is RecipeExecutionStep => !!step)

  const stepIdSet = new Set(steps.map((step) => step.id))

  const edges = value.edges
    .map((entry) => {
      if (!isRecord(entry)) return null
      const from = toStringValue(entry.from).trim()
      const to = toStringValue(entry.to).trim()
      if (!from || !to) return null
      if (!stepIdSet.has(from) || !stepIdSet.has(to)) return null
      return { from, to }
    })
    .filter((edge): edge is { from: string; to: string } => !!edge)

  return { steps, edges }
}
