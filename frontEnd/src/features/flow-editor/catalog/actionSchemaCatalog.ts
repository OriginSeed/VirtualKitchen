import {
  getStepActionById,
  type ActionCategory,
  type StepActionId,
} from './actionCatalog'
import type { StepNodeStructuredFields } from '../../../types/recipeFlow'

export type StepSchemaFieldKey =
  | 'ingredientId'
  | 'quantity'
  | 'unitId'
  | 'preparationStyleId'
  | 'temperature'
  | 'flameLevelId'
  | 'duration'
  | 'repeatInterval'
  | 'notes'

export type StepSchemaFieldConfig = {
  key: StepSchemaFieldKey
  label: string
}

export type StepActionSchema = {
  category: ActionCategory
  amountLabel: string
  unitLabel: string
  fields: readonly StepSchemaFieldConfig[]
}

const CATEGORY_SCHEMAS: Record<ActionCategory, StepActionSchema> = {
  'Ingredient Operations': {
    category: 'Ingredient Operations',
    amountLabel: 'Amount',
    unitLabel: 'Unit',
    fields: [
      { key: 'ingredientId', label: 'Ingredient' },
      { key: 'quantity', label: 'Amount' },
      { key: 'unitId', label: 'Unit' },
      { key: 'notes', label: 'Notes' },
    ],
  },
  'Preparation Operations': {
    category: 'Preparation Operations',
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    fields: [
      { key: 'ingredientId', label: 'Ingredient' },
      { key: 'quantity', label: 'Quantity' },
      { key: 'unitId', label: 'Unit' },
      { key: 'preparationStyleId', label: 'Preparation Style' },
      { key: 'notes', label: 'Notes' },
    ],
  },
  'Cooking Operations': {
    category: 'Cooking Operations',
    amountLabel: 'Amount',
    unitLabel: 'Unit',
    fields: [
      { key: 'ingredientId', label: 'Ingredient' },
      { key: 'quantity', label: 'Amount' },
      { key: 'unitId', label: 'Unit' },
      { key: 'flameLevelId', label: 'Flame Level' },
      { key: 'temperature', label: 'Temperature' },
      { key: 'duration', label: 'Duration' },
      { key: 'repeatInterval', label: 'Repeat Interval' },
      { key: 'notes', label: 'Notes' },
    ],
  },
  'Mixing Operations': {
    category: 'Mixing Operations',
    amountLabel: 'Amount',
    unitLabel: 'Unit',
    fields: [
      { key: 'ingredientId', label: 'Ingredient' },
      { key: 'quantity', label: 'Amount' },
      { key: 'unitId', label: 'Unit' },
      { key: 'duration', label: 'Duration' },
      { key: 'repeatInterval', label: 'Repeat Interval' },
      { key: 'notes', label: 'Notes' },
    ],
  },
  'Waiting Operations': {
    category: 'Waiting Operations',
    amountLabel: 'Amount',
    unitLabel: 'Unit',
    fields: [
      { key: 'duration', label: 'Duration' },
      { key: 'notes', label: 'Notes' },
    ],
  },
  'Finish Operations': {
    category: 'Finish Operations',
    amountLabel: 'Amount',
    unitLabel: 'Unit',
    fields: [
      { key: 'ingredientId', label: 'Ingredient' },
      { key: 'quantity', label: 'Amount' },
      { key: 'unitId', label: 'Unit' },
      { key: 'notes', label: 'Notes' },
    ],
  },
}

const COMMON_RESET_STEP_FIELDS: StepNodeStructuredFields = {
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
}

const clearIngredientFields = (step: StepNodeStructuredFields) => {
  step.ingredientId = ''
  step.customIngredientName = ''
}

const clearAmountFields = (step: StepNodeStructuredFields) => {
  step.quantity = ''
}

const clearUnitFields = (step: StepNodeStructuredFields) => {
  step.unitId = ''
  step.customUnit = ''
  step.unit = ''
}

const clearPreparationFields = (step: StepNodeStructuredFields) => {
  step.preparationStyleId = ''
  step.customPreparationStyle = ''
  step.preparationStyle = ''
}

const clearFlameFields = (step: StepNodeStructuredFields) => {
  step.flameLevelId = ''
  step.customFlameLevel = ''
  step.flameLevel = ''
}

const clearTemperatureField = (step: StepNodeStructuredFields) => {
  step.temperature = ''
}

const clearDurationFields = (step: StepNodeStructuredFields) => {
  step.durationValue = ''
  step.durationUnit = ''
  step.duration = ''
}

const clearRepeatFields = (step: StepNodeStructuredFields) => {
  step.repeatAction = ''
  step.repeatEveryValue = ''
  step.repeatEveryUnit = ''
  step.repeatInterval = ''
}

const clearNotesField = (step: StepNodeStructuredFields) => {
  step.notes = ''
}

const FIELD_CLEARERS: Record<StepSchemaFieldKey, (step: StepNodeStructuredFields) => void> = {
  ingredientId: clearIngredientFields,
  quantity: clearAmountFields,
  unitId: clearUnitFields,
  preparationStyleId: clearPreparationFields,
  flameLevelId: clearFlameFields,
  temperature: clearTemperatureField,
  duration: clearDurationFields,
  repeatInterval: clearRepeatFields,
  notes: clearNotesField,
}

const SCHEMA_FIELD_KEYS: readonly StepSchemaFieldKey[] = [
  'ingredientId',
  'quantity',
  'unitId',
  'preparationStyleId',
  'flameLevelId',
  'temperature',
  'duration',
  'repeatInterval',
  'notes',
]

export const getStepActionSchema = (action: StepActionId | ''): StepActionSchema => {
  if (!action) return CATEGORY_SCHEMAS['Ingredient Operations']
  const definition = getStepActionById(action)
  return CATEGORY_SCHEMAS[definition.category]
}

export const isStepFieldEnabled = (action: StepActionId | '', field: StepSchemaFieldKey) =>
  getStepActionSchema(action).fields.some((config) => config.key === field)

export const pruneStepFieldsByActionSchema = (step: StepNodeStructuredFields): StepNodeStructuredFields => {
  const schema = getStepActionSchema(step.action)
  const enabled = new Set(schema.fields.map((field) => field.key))

  const pruned: StepNodeStructuredFields = {
    ...COMMON_RESET_STEP_FIELDS,
    ...step,
  }

  SCHEMA_FIELD_KEYS.forEach((fieldKey) => {
    if (!enabled.has(fieldKey)) {
      FIELD_CLEARERS[fieldKey](pruned)
    }
  })

  return pruned
}

const getValueForSummaryField = (step: StepNodeStructuredFields, field: StepSchemaFieldKey): string => {
  switch (field) {
    case 'ingredientId':
      return ''
    case 'quantity':
      return step.quantity.trim()
    case 'unitId':
      return step.unit.trim()
    case 'preparationStyleId':
      return step.preparationStyle.trim()
    case 'temperature':
      return step.temperature.trim()
    case 'flameLevelId':
      return step.flameLevel.trim()
    case 'duration':
      return step.duration.trim()
    case 'repeatInterval':
      return step.repeatInterval.trim()
    case 'notes':
      return step.notes.trim()
    default:
      return ''
  }
}

export const getVisibleStepDetailRows = (step: StepNodeStructuredFields) =>
  getStepActionSchema(step.action).fields
    .filter((field) => !['ingredientId', 'quantity', 'unitId', 'notes'].includes(field.key))
    .map((field) => ({
      ...field,
      value: getValueForSummaryField(step, field.key),
    }))
    .filter((field) => field.value)
