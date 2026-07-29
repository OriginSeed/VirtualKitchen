import { getActionDisplayName, type StepActionId } from './actionCatalog'
import type { StepNodeStructuredFields } from '../../../types/recipeFlow'

export type StepContextFieldKey =
  | 'cutType'
  | 'specification'
  | 'temperature'
  | 'flame'
  | 'duration'
  | 'repeatInterval'

export type StepContextFieldConfig = {
  key: StepContextFieldKey
  label: string
}

type StepActionPresentation = {
  amountLabel: string
  unitLabel: string
  detailFields: readonly StepContextFieldConfig[]
}

const DEFAULT_PRESENTATION: StepActionPresentation = {
  amountLabel: 'Quantity',
  unitLabel: 'Unit',
  detailFields: [],
}

const CUT_DETAIL_FIELDS: readonly StepContextFieldConfig[] = [
  { key: 'cutType', label: 'Cut Type' },
  { key: 'specification', label: 'Cut Size' },
]

const COOKING_DETAIL_FIELDS: readonly StepContextFieldConfig[] = [
  { key: 'duration', label: 'Cooking Time' },
  { key: 'temperature', label: 'Flame Temperature' },
]

const ACTION_PRESENTATION_OVERRIDES: Partial<Record<StepActionId, StepActionPresentation>> = {
  wash: {
    amountLabel: 'Water Amount',
    unitLabel: 'Unit',
    detailFields: [{ key: 'temperature', label: 'Water Temperature' }],
  },
  cut: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: CUT_DETAIL_FIELDS,
  },
  chop: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: CUT_DETAIL_FIELDS,
  },
  slice: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: CUT_DETAIL_FIELDS,
  },
  dice: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: CUT_DETAIL_FIELDS,
  },
  grate: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: CUT_DETAIL_FIELDS,
  },
  crush: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: CUT_DETAIL_FIELDS,
  },
  grind: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: CUT_DETAIL_FIELDS,
  },
  fry: {
    amountLabel: 'Oil Amount',
    unitLabel: 'Unit',
    detailFields: [
      { key: 'flame', label: 'Flame Level' },
      { key: 'duration', label: 'Fry Duration' },
      { key: 'repeatInterval', label: 'Stir Frequency' },
    ],
  },
  'deep-fry': {
    amountLabel: 'Oil Amount',
    unitLabel: 'Unit',
    detailFields: [
      { key: 'flame', label: 'Flame Level' },
      { key: 'duration', label: 'Fry Duration' },
      { key: 'repeatInterval', label: 'Stir Frequency' },
    ],
  },
  heat: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: COOKING_DETAIL_FIELDS,
  },
  boil: {
    amountLabel: 'Water Amount',
    unitLabel: 'Unit',
    detailFields: COOKING_DETAIL_FIELDS,
  },
  steam: {
    amountLabel: 'Water Amount',
    unitLabel: 'Unit',
    detailFields: COOKING_DETAIL_FIELDS,
  },
  bake: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: COOKING_DETAIL_FIELDS,
  },
  roast: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: COOKING_DETAIL_FIELDS,
  },
  grill: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: COOKING_DETAIL_FIELDS,
  },
  simmer: {
    amountLabel: 'Water Amount',
    unitLabel: 'Unit',
    detailFields: [
      { key: 'duration', label: 'Cooking Time' },
      { key: 'temperature', label: 'Flame Temperature' },
      { key: 'repeatInterval', label: 'Stir Frequency' },
    ],
  },
  'pressure-cook': {
    amountLabel: 'Water Amount',
    unitLabel: 'Unit',
    detailFields: COOKING_DETAIL_FIELDS,
  },
  stir: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: [{ key: 'repeatInterval', label: 'Stir Frequency' }],
  },
  mix: {
    amountLabel: 'Quantity',
    unitLabel: 'Unit',
    detailFields: [{ key: 'repeatInterval', label: 'Mix Frequency' }],
  },
}

export const getStepActionPresentation = (action: StepActionId | ''): StepActionPresentation =>
  (action ? ACTION_PRESENTATION_OVERRIDES[action] : undefined) ?? DEFAULT_PRESENTATION

const getStepContextValue = (step: StepNodeStructuredFields, key: StepContextFieldKey) => {
  switch (key) {
    case 'cutType':
      return step.action ? getActionDisplayName(step.action) : ''
    case 'specification':
      return step.specification.trim()
    case 'temperature':
      return step.temperature.trim()
    case 'flame':
      return step.flame && step.flame !== 'None' ? step.flame.trim() : ''
    case 'duration':
      return step.duration.trim()
    case 'repeatInterval':
      return step.repeatInterval.trim()
    default:
      return ''
  }
}

export const getVisibleStepDetailRows = (step: StepNodeStructuredFields) =>
  getStepActionPresentation(step.action).detailFields
    .map((field) => ({
      ...field,
      value: getStepContextValue(step, field.key),
    }))
    .filter((field) => field.value)