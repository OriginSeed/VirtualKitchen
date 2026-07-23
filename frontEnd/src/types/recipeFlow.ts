import {
  getActionDisplayName,
  getActionIcon,
  getStepActionById,
  resolveStepActionId,
  type StepActionId,
} from '../features/flow-editor/catalog/actionCatalog'
import {
  CUSTOM_INGREDIENT_ID,
  getIngredientDisplayName,
  resolveIngredientId,
  type IngredientId,
} from '../features/flow-editor/catalog/ingredientCatalog'
import {
  FLAME_OPTIONS,
  SPECIFICATION_OPTIONS,
  UNIT_OPTIONS,
  buildDurationLabel,
  buildRepeatIntervalLabel,
  parseDurationLabel,
  parseRepeatIntervalLabel,
  toCatalogOption,
  type DurationUnitOption,
  type FlameOption,
  type RepeatIntervalUnitOption,
  type SpecificationOption,
  type UnitOption,
} from '../features/flow-editor/catalog/stepFieldCatalog'

export type StepAction = StepActionId

export type StepNodeStructuredFields = {
  action: StepAction | ''
  ingredientId: IngredientId | ''
  customIngredientName: string
  quantity: string
  specificationOption: SpecificationOption | ''
  customSpecification: string
  unit: string
  unitOption: UnitOption | ''
  customUnit: string
  specification: string
  flame: FlameOption | ''
  temperature: string
  durationValue: string
  durationUnit: DurationUnitOption | ''
  duration: string
  repeatAction: StepAction | ''
  repeatEveryValue: string
  repeatEveryUnit: RepeatIntervalUnitOption | ''
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

export type ConditionExpectedResult = 'success' | 'failure'

export type ConditionNodeStructuredFields = {
  question: string
  expectedResult: ConditionExpectedResult
  successLabel: string
  failureLabel: string
  notes: string
}

export type ConditionNodeData = {
  title: string
  condition: ConditionNodeStructuredFields
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
  specificationOption: '',
  customSpecification: '',
  unit: '',
  unitOption: '',
  customUnit: '',
  specification: '',
  flame: 'None',
  temperature: '',
  durationValue: '',
  durationUnit: '',
  duration: '',
  repeatAction: '',
  repeatEveryValue: '',
  repeatEveryUnit: '',
  repeatInterval: '',
  notes: '',
})

export const createDefaultConditionFields = (): ConditionNodeStructuredFields => ({
  question: '',
  expectedResult: 'success',
  successLabel: 'Yes',
  failureLabel: 'No',
  notes: '',
})

export const getConditionNodeTitle = (question: string) => question.trim() || 'Condition?'

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

  const rawSpecification = toStringValue(rawStep.specification)
  const specificationOption = toCatalogOption(rawStep.specificationOption, SPECIFICATION_OPTIONS)
  const customSpecification = toStringValue(rawStep.customSpecification)
  const resolvedSpecificationOption = specificationOption || toCatalogOption(rawSpecification, SPECIFICATION_OPTIONS)
  const resolvedCustomSpecification = customSpecification || (resolvedSpecificationOption === 'Custom' ? rawSpecification : '')
  const resolvedSpecification = resolvedSpecificationOption === 'Custom'
    ? resolvedCustomSpecification
    : (resolvedSpecificationOption || rawSpecification)

  const rawUnit = toStringValue(rawStep.unit)
  const unitOption = toCatalogOption(rawStep.unitOption, UNIT_OPTIONS)
  const customUnit = toStringValue(rawStep.customUnit)
  const resolvedUnitOption = unitOption || toCatalogOption(rawUnit, UNIT_OPTIONS)
  const resolvedCustomUnit = customUnit || (resolvedUnitOption === 'Custom' ? rawUnit : '')
  const resolvedUnit = resolvedUnitOption === 'Custom' ? resolvedCustomUnit : (resolvedUnitOption || rawUnit)

  const flame = toCatalogOption(rawStep.flame, FLAME_OPTIONS, 'None')

  const durationValue = toStringValue(rawStep.durationValue)
  const durationUnit = toCatalogOption(rawStep.durationUnit, ['seconds', 'minutes', 'hours'] as const)
  const rawDurationLabel = toStringValue(rawStep.duration ?? legacyDuration)
  const parsedLegacyDuration = parseDurationLabel(rawDurationLabel)
  const resolvedDurationValue = durationValue || parsedLegacyDuration.durationValue
  const resolvedDurationUnit = durationUnit || parsedLegacyDuration.durationUnit
  const resolvedDuration = buildDurationLabel(resolvedDurationValue, resolvedDurationUnit)

  const repeatAction = normalizeAction(rawStep.repeatAction)
  const repeatEveryValue = toStringValue(rawStep.repeatEveryValue)
  const repeatEveryUnit = toCatalogOption(rawStep.repeatEveryUnit, ['seconds', 'minutes', 'hours'] as const)
  const parsedLegacyRepeat = parseRepeatIntervalLabel(toStringValue(rawStep.repeatInterval))
  const resolvedRepeatAction = repeatAction || (normalizeAction(parsedLegacyRepeat.repeatPrefix) || action)
  const resolvedRepeatEveryValue = repeatEveryValue || parsedLegacyRepeat.repeatEveryValue
  const resolvedRepeatEveryUnit = repeatEveryUnit || parsedLegacyRepeat.repeatEveryUnit
  const repeatActionLabel = resolvedRepeatAction ? getStepActionById(resolvedRepeatAction).displayName : ''
  const resolvedRepeatInterval = buildRepeatIntervalLabel(repeatActionLabel, resolvedRepeatEveryValue, resolvedRepeatEveryUnit)

  const step: StepNodeStructuredFields = {
    action,
    ingredientId,
    customIngredientName,
    quantity: toStringValue(rawStep.quantity),
    specificationOption: resolvedSpecificationOption,
    customSpecification: resolvedCustomSpecification,
    unit: resolvedUnit,
    unitOption: resolvedUnitOption,
    customUnit: resolvedCustomUnit,
    specification: resolvedSpecification,
    flame,
    temperature: toStringValue(rawStep.temperature),
    durationValue: resolvedDurationValue,
    durationUnit: resolvedDurationUnit,
    duration: resolvedDuration,
    repeatAction: resolvedRepeatAction,
    repeatEveryValue: resolvedRepeatEveryValue,
    repeatEveryUnit: resolvedRepeatEveryUnit,
    repeatInterval: resolvedRepeatInterval,
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

export const normalizeConditionNodeData = (value: unknown): ConditionNodeData => {
  const raw = asRecord(value)
  const rawCondition = asRecord(raw.condition)

  const question = toStringValue(rawCondition.question || raw.title)
  const expectedRaw = toStringValue(rawCondition.expectedResult).toLowerCase()
  const expectedResult: ConditionExpectedResult = expectedRaw === 'failure' ? 'failure' : 'success'

  const successLabel = toStringValue(rawCondition.successLabel || raw.yesLabel || 'Yes')
  const failureLabel = toStringValue(rawCondition.failureLabel || raw.noLabel || 'No')
  const notes = toStringValue(rawCondition.notes || raw.description)

  return {
    title: getConditionNodeTitle(question),
    condition: {
      question,
      expectedResult,
      successLabel,
      failureLabel,
      notes,
    },
    description: notes,
    yesLabel: successLabel,
    noLabel: failureLabel,
    sectionId: typeof raw.sectionId === 'string' || raw.sectionId === null ? raw.sectionId : null,
  }
}
