export const STEP_ACTION_OPTIONS = [
  'Prep',
  'Chop',
  'Slice',
  'Mix',
  'Marinate',
  'Boil',
  'Saute',
  'Fry',
  'Bake',
  'Steam',
  'Season',
  'Serve',
] as const

export type StepAction = (typeof STEP_ACTION_OPTIONS)[number]

export type StepNodeStructuredFields = {
  action: StepAction | ''
  ingredient: string
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

export const STEP_ACTION_ICONS: Record<StepAction, string> = {
  Prep: 'PR',
  Chop: 'CH',
  Slice: 'SL',
  Mix: 'MX',
  Marinate: 'MR',
  Boil: 'BL',
  Saute: 'ST',
  Fry: 'FR',
  Bake: 'BK',
  Steam: 'SM',
  Season: 'SN',
  Serve: 'SV',
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

const normalizeAction = (value: unknown): StepAction | '' => {
  const text = toStringValue(value).trim()
  if (!text) return ''
  const directMatch = STEP_ACTION_OPTIONS.find((option) => option === text)
  if (directMatch) return directMatch

  const lower = text.toLowerCase()
  const caseInsensitiveMatch = STEP_ACTION_OPTIONS.find((option) => option.toLowerCase() === lower)
  return caseInsensitiveMatch ?? ''
}

export const getStepNodeTitle = (action: StepAction | '') => action || 'Select Action'

export const getStepNodeIcon = (action: StepAction | '') => (action ? STEP_ACTION_ICONS[action] : 'ST')

export const createDefaultStepFields = (): StepNodeStructuredFields => ({
  action: '',
  ingredient: '',
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

  const step: StepNodeStructuredFields = {
    action,
    ingredient: toStringValue(rawStep.ingredient),
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
