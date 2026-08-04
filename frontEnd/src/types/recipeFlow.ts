import {
  getActionDisplayName,
  getActionIcon,
  getStepActionById,
  resolveStepActionId,
  type StepActionId,
} from '../features/flow-editor/catalog/actionCatalog'
import { pruneStepFieldsByActionSchema } from '../features/flow-editor/catalog/actionSchemaCatalog'
import {
  CUSTOM_INGREDIENT_ID,
  getIngredientDisplayName,
  resolveIngredientId,
  type IngredientId,
} from '../features/flow-editor/catalog/ingredientCatalog'
import {
  buildDurationLabel,
  buildRepeatIntervalLabel,
  parseDurationLabel,
  parseRepeatIntervalLabel,
  type DurationUnitOption,
  type RepeatIntervalUnitOption,
} from '../features/flow-editor/catalog/stepFieldCatalog'
import {
  CUSTOM_FLAME_LEVEL_ID,
  getFlameLevelDisplayName,
  resolveFlameLevelId,
  type FlameLevelId,
} from '../features/flow-editor/catalog/flameLevelCatalog'
import {
  CUSTOM_PREPARATION_STYLE_ID,
  getPreparationStyleDisplayName,
  resolvePreparationStyleId,
  type PreparationStyleId,
} from '../features/flow-editor/catalog/preparationStyleCatalog'
import {
  CUSTOM_UNIT_ID,
  getUnitDisplayValue,
  resolveUnitId,
  type UnitId,
} from '../features/flow-editor/catalog/unitCatalog'

export type StepAction = StepActionId

export type StepNodeStructuredFields = {
  action: StepAction | ''
  ingredientId: IngredientId | ''
  customIngredientName: string
  quantity: string
  unitId: UnitId | ''
  customUnit: string
  unit: string
  preparationStyleId: PreparationStyleId | ''
  customPreparationStyle: string
  preparationStyle: string
  flameLevelId: FlameLevelId | ''
  customFlameLevel: string
  flameLevel: string
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

export type ParallelNodeKind = 'start' | 'end'

export type ParallelNodeStructuredFields = {
  kind: ParallelNodeKind
  label: string
  notes: string
}

export type ParallelNodeData = {
  title: string
  parallel: ParallelNodeStructuredFields
  description?: string
  sectionId?: string | null
}

export type FlowNodeData = RecipeStepNodeData | ConditionNodeData | ParallelNodeData | Record<string, unknown>

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

const normalizeDurationUnitOption = (value: unknown): DurationUnitOption | '' => {
  if (typeof value !== 'string') return ''
  const normalized = value.trim().toLowerCase()
  if (normalized === 'seconds' || normalized === 'second' || normalized === 'sec' || normalized === 's') return 'seconds'
  if (normalized === 'minutes' || normalized === 'minute' || normalized === 'min' || normalized === 'm') return 'minutes'
  if (normalized === 'hours' || normalized === 'hour' || normalized === 'hr' || normalized === 'h') return 'hours'
  return ''
}

const normalizeRepeatIntervalUnitOption = (value: unknown): RepeatIntervalUnitOption | '' =>
  normalizeDurationUnitOption(value)

export const getStepNodeTitle = (action: StepAction | '') => getActionDisplayName(action)

export const getStepNodeIcon = (action: StepAction | '') => getActionIcon(action)

export const createDefaultStepFields = (): StepNodeStructuredFields => ({
  action: '',
  ingredientId: '',
  customIngredientName: '',
  quantity: '',
  unitId: '',
  customUnit: '',
  unit: '',
  preparationStyleId: '',
  customPreparationStyle: '',
  preparationStyle: '',
  flameLevelId: '',
  customFlameLevel: '',
  flameLevel: '',
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

export const createDefaultParallelFields = (kind: ParallelNodeKind): ParallelNodeStructuredFields => ({
  kind,
  label: kind === 'start' ? 'Parallel Start' : 'Parallel End',
  notes: '',
})

export const getConditionNodeTitle = (question: string) => question.trim() || 'Condition?'

export const getParallelNodeTitle = (kind: ParallelNodeKind, label: string) => {
  const trimmed = label.trim()
  if (trimmed) return trimmed
  return kind === 'start' ? 'Parallel Start' : 'Parallel End'
}

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

  const rawUnit = toStringValue(rawStep.unit)
  const legacyUnitOption = toStringValue(rawStep.unitOption)
  const rawCustomUnit = toStringValue(rawStep.customUnit)
  const unitIdCandidate = resolveUnitId(rawStep.unitId) || resolveUnitId(legacyUnitOption) || resolveUnitId(rawUnit)
  const resolvedUnitId: UnitId | '' = unitIdCandidate
  const resolvedCustomUnit =
    rawCustomUnit ||
    (legacyUnitOption.toLowerCase() === 'custom' ? rawUnit : '') ||
    (resolvedUnitId === CUSTOM_UNIT_ID ? rawUnit : '')
  const resolvedUnit = getUnitDisplayValue(resolvedUnitId, resolvedCustomUnit)

  const rawPreparationStyle =
    toStringValue(rawStep.preparationStyle) ||
    toStringValue(rawStep.specification)
  const legacyPreparationStyleOption = toStringValue(rawStep.specificationOption)
  const rawCustomPreparationStyle =
    toStringValue(rawStep.customPreparationStyle) ||
    toStringValue(rawStep.customSpecification)
  const preparationStyleIdCandidate =
    resolvePreparationStyleId(rawStep.preparationStyleId) ||
    resolvePreparationStyleId(legacyPreparationStyleOption) ||
    resolvePreparationStyleId(rawPreparationStyle)
  const resolvedPreparationStyleId: PreparationStyleId | '' = preparationStyleIdCandidate
  const resolvedCustomPreparationStyle =
    rawCustomPreparationStyle ||
    (legacyPreparationStyleOption.toLowerCase() === 'custom' ? rawPreparationStyle : '') ||
    (resolvedPreparationStyleId === CUSTOM_PREPARATION_STYLE_ID ? rawPreparationStyle : '')
  const resolvedPreparationStyle = getPreparationStyleDisplayName(
    resolvedPreparationStyleId,
    resolvedCustomPreparationStyle,
  )

  const rawFlameLevel = toStringValue(rawStep.flameLevel) || toStringValue(rawStep.flame)
  const rawCustomFlameLevel = toStringValue(rawStep.customFlameLevel)
  const flameLevelIdCandidate = resolveFlameLevelId(rawStep.flameLevelId) || resolveFlameLevelId(rawFlameLevel)
  const resolvedFlameLevelId: FlameLevelId | '' = flameLevelIdCandidate
  const resolvedCustomFlameLevel =
    rawCustomFlameLevel ||
    (resolvedFlameLevelId === CUSTOM_FLAME_LEVEL_ID ? rawFlameLevel : '')
  const resolvedFlameLevel = getFlameLevelDisplayName(resolvedFlameLevelId, resolvedCustomFlameLevel)

  const durationValue = toStringValue(rawStep.durationValue)
  const durationUnit = normalizeDurationUnitOption(rawStep.durationUnit)
  const rawDurationLabel = toStringValue(rawStep.duration ?? legacyDuration)
  const parsedLegacyDuration = parseDurationLabel(rawDurationLabel)
  const resolvedDurationValue = durationValue || parsedLegacyDuration.durationValue
  const resolvedDurationUnit = durationUnit || parsedLegacyDuration.durationUnit
  const resolvedDuration = buildDurationLabel(resolvedDurationValue, resolvedDurationUnit)

  const repeatAction = normalizeAction(rawStep.repeatAction)
  const repeatEveryValue = toStringValue(rawStep.repeatEveryValue)
  const repeatEveryUnit = normalizeRepeatIntervalUnitOption(rawStep.repeatEveryUnit)
  const parsedLegacyRepeat = parseRepeatIntervalLabel(toStringValue(rawStep.repeatInterval))
  const resolvedRepeatAction = repeatAction || (normalizeAction(parsedLegacyRepeat.repeatPrefix) || action)
  const resolvedRepeatEveryValue = repeatEveryValue || parsedLegacyRepeat.repeatEveryValue
  const resolvedRepeatEveryUnit = repeatEveryUnit || parsedLegacyRepeat.repeatEveryUnit
  const repeatActionLabel = resolvedRepeatAction ? getStepActionById(resolvedRepeatAction).displayName : ''
  const resolvedRepeatInterval = buildRepeatIntervalLabel(repeatActionLabel, resolvedRepeatEveryValue, resolvedRepeatEveryUnit)

  const mergedStep: StepNodeStructuredFields = {
    action,
    ingredientId,
    customIngredientName,
    quantity: toStringValue(rawStep.quantity),
    unitId: resolvedUnitId,
    customUnit: resolvedCustomUnit,
    unit: resolvedUnit,
    preparationStyleId: resolvedPreparationStyleId,
    customPreparationStyle: resolvedCustomPreparationStyle,
    preparationStyle: resolvedPreparationStyle,
    flameLevelId: resolvedFlameLevelId,
    customFlameLevel: resolvedCustomFlameLevel,
    flameLevel: resolvedFlameLevel,
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

  const step = pruneStepFieldsByActionSchema(mergedStep)

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

export const normalizeParallelNodeData = (value: unknown, fallbackKind: ParallelNodeKind = 'start'): ParallelNodeData => {
  const raw = asRecord(value)
  const rawParallel = asRecord(raw.parallel)

  const rawKind = toStringValue(rawParallel.kind).toLowerCase()
  const kind: ParallelNodeKind = rawKind === 'end' ? 'end' : rawKind === 'start' ? 'start' : fallbackKind
  const label = toStringValue(rawParallel.label || raw.title)
  const notes = toStringValue(rawParallel.notes || raw.description)

  return {
    title: getParallelNodeTitle(kind, label),
    parallel: {
      kind,
      label: label.trim() || (kind === 'start' ? 'Parallel Start' : 'Parallel End'),
      notes,
    },
    description: notes,
    sectionId: typeof raw.sectionId === 'string' || raw.sectionId === null ? raw.sectionId : null,
  }
}
