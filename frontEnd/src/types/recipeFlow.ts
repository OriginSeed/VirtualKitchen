import {
  getActionDisplayName,
  getActionIcon,
  resolveStepActionId,
  type StepActionId,
} from '../features/flow-editor/catalog/actionCatalog'
import {
  CUSTOM_INGREDIENT_ID,
  getIngredientDisplayName,
  resolveIngredientId,
  type IngredientId,
} from '../features/flow-editor/catalog/ingredientCatalog'

export type StepAction = StepActionId

export type StepNodeStructuredFields = {
  action: StepAction | ''
  ingredientId: IngredientId | ''
  customIngredientName: string
  quantity: string
  unit: string
  specification: string
  flame: string
  temperature: string
  duration: string
  repeatInterval: string
  notes: string
}

export type RecipeStepNodeData = {
  title: string
  icon?: string
  step: StepNodeStructuredFields
  stepNumber?: number
  sectionId?: string | null
  description?: string
  duration?: string
}

export type ConditionNodeData = {
  title: string
  description?: string
  yesLabel?: string
  noLabel?: string
  sectionId?: string | null
}

export type FlowNodeData = RecipeStepNodeData | ConditionNodeData | Record<string, unknown>

export type FlowNodePayload = {
  id: string
  type?: string
  position?: { x: number; y: number }
  data?: FlowNodeData
  measured?: unknown
  parentId?: string
  extent?: unknown
  draggable?: boolean
  selectable?: boolean
  deletable?: boolean
  style?: Record<string, unknown>
}

export type FlowEdgePayload = {
  id: string
  source: string
  target: string
  sourceHandle?: string | null
  targetHandle?: string | null
  type?: string
  animated?: boolean
  style?: Record<string, unknown>
  data?: Record<string, unknown>
  label?: string | null
}

export interface FlowData {
  nodes: FlowNodePayload[]
  edges: FlowEdgePayload[]
}

export interface FlowDraftStorage {
  version: '2.0'
  recipeId: number | string
  updatedAt: string
  data: FlowData
}

const toStringValue = (value: unknown): string => {
  if (value == null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  return ''
}

const asRecord = (value: unknown): Record<string, unknown> => {
  if (typeof value === 'object' && value != null) return value as Record<string, unknown>
  return {}
}

const normalizeAction = (value: unknown): StepAction | '' => resolveStepActionId(value)

export const getStepNodeTitle = (action: StepAction | '') => getActionDisplayName(action)

export const getStepNodeIcon = (action: StepAction | '') => getActionIcon(action)

export const createDefaultStepFields = (): StepNodeStructuredFields => ({
  action: '',
  ingredientId: '',
  customIngredientName: '',
  quantity: '',
  unit: '',
  specification: '',
  flame: '',
  temperature: '',
  duration: '',
  repeatInterval: '',
  notes: '',
})

export const normalizeStepNodeData = (value: unknown): RecipeStepNodeData => {
  const raw = asRecord(value)
  const legacyTitle = toStringValue(raw.title)
  const legacyDuration = toStringValue(raw.duration)
  const legacyDescription = toStringValue(raw.description)

  const rawStep = asRecord(raw.step)
  const action = normalizeAction(rawStep.action ?? legacyTitle)
  const rawIngredientId = resolveIngredientId(rawStep.ingredientId)
  const rawIngredientName = toStringValue(rawStep.ingredient)
  const rawCustomIngredientName = toStringValue(rawStep.customIngredientName)

  let ingredientId: IngredientId | '' = rawIngredientId
  let customIngredientName = rawCustomIngredientName

  if (!ingredientId && rawIngredientName) {
    const resolvedFromName = resolveIngredientId(rawIngredientName)
    if (resolvedFromName) {
      ingredientId = resolvedFromName
    } else {
      ingredientId = CUSTOM_INGREDIENT_ID
      customIngredientName = rawIngredientName
    }
  }

  if (ingredientId === CUSTOM_INGREDIENT_ID && !customIngredientName && rawIngredientName) {
    customIngredientName = rawIngredientName
  }

  const step: StepNodeStructuredFields = {
    action,
    ingredientId,
    customIngredientName,
    quantity: toStringValue(rawStep.quantity),
    unit: toStringValue(rawStep.unit),
    specification: toStringValue(rawStep.specification),
    flame: toStringValue(rawStep.flame),
    temperature: toStringValue(rawStep.temperature),
    duration: toStringValue(rawStep.duration ?? legacyDuration),
    repeatInterval: toStringValue(rawStep.repeatInterval),
    notes: toStringValue(rawStep.notes ?? legacyDescription),
  }

  return {
    title: getStepNodeTitle(step.action),
    icon: getStepNodeIcon(step.action),
    step,
    stepNumber: typeof raw.stepNumber === 'number' ? raw.stepNumber : undefined,
    sectionId: typeof raw.sectionId === 'string' || raw.sectionId === null ? raw.sectionId : null,
    description: step.notes,
    duration: step.duration,
  }
}

export const getStepIngredientName = (step: StepNodeStructuredFields) =>
  getIngredientDisplayName(step.ingredientId, step.customIngredientName)
