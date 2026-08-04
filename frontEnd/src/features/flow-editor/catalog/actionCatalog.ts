export const ACTION_CATEGORY_ORDER = [
  'Ingredient Operations',
  'Preparation Operations',
  'Cooking Operations',
  'Mixing Operations',
  'Waiting Operations',
  'Finish Operations',
] as const

export type ActionCategory = (typeof ACTION_CATEGORY_ORDER)[number]

export const STEP_ACTION_CATALOG = [
  { id: 'add', displayName: 'Add', icon: 'AD', category: 'Ingredient Operations' },
  { id: 'remove', displayName: 'Remove', icon: 'RM', category: 'Ingredient Operations' },
  { id: 'pour', displayName: 'Pour', icon: 'PO', category: 'Ingredient Operations' },
  { id: 'season', displayName: 'Season', icon: 'SN', category: 'Ingredient Operations' },

  { id: 'cut', displayName: 'Cut', icon: 'CT', category: 'Preparation Operations' },
  { id: 'chop', displayName: 'Chop', icon: 'CH', category: 'Preparation Operations' },
  { id: 'slice', displayName: 'Slice', icon: 'SL', category: 'Preparation Operations' },
  { id: 'dice', displayName: 'Dice', icon: 'DC', category: 'Preparation Operations' },

  { id: 'heat', displayName: 'Heat', icon: 'HT', category: 'Cooking Operations' },
  { id: 'boil', displayName: 'Boil', icon: 'BL', category: 'Cooking Operations' },
  { id: 'fry', displayName: 'Fry', icon: 'FR', category: 'Cooking Operations' },
  { id: 'bake', displayName: 'Bake', icon: 'BK', category: 'Cooking Operations' },

  { id: 'stir', displayName: 'Stir', icon: 'ST', category: 'Mixing Operations' },
  { id: 'mix', displayName: 'Mix', icon: 'MX', category: 'Mixing Operations' },
  { id: 'whisk', displayName: 'Whisk', icon: 'WK', category: 'Mixing Operations' },

  { id: 'wait', displayName: 'Wait', icon: 'WT', category: 'Waiting Operations' },
  { id: 'rest', displayName: 'Rest', icon: 'RE', category: 'Waiting Operations' },

  { id: 'serve', displayName: 'Serve', icon: 'SV', category: 'Finish Operations' },
  { id: 'garnish', displayName: 'Garnish', icon: 'GN', category: 'Finish Operations' },
] as const

export type StepActionId = (typeof STEP_ACTION_CATALOG)[number]['id']

export type StepActionDefinition = {
  id: StepActionId
  displayName: string
  icon: string
  category: ActionCategory
}

const catalogById = new Map<StepActionId, StepActionDefinition>(
  STEP_ACTION_CATALOG.map((entry) => [entry.id, entry])
)

const normalizeText = (value: string) => value.trim().toLowerCase().replace(/\s+/g, ' ')

const actionAliasLookup = new Map<string, StepActionId>()
for (const action of STEP_ACTION_CATALOG) {
  actionAliasLookup.set(normalizeText(action.id), action.id)
  actionAliasLookup.set(normalizeText(action.displayName), action.id)
}

export const ACTIONS_BY_CATEGORY: Readonly<Record<ActionCategory, readonly StepActionDefinition[]>> =
  ACTION_CATEGORY_ORDER.reduce((accumulator, category) => {
    accumulator[category] = STEP_ACTION_CATALOG.filter((action) => action.category === category)
    return accumulator
  }, {} as Record<ActionCategory, readonly StepActionDefinition[]>)

export const isStepActionId = (value: unknown): value is StepActionId =>
  typeof value === 'string' && catalogById.has(value as StepActionId)

export const getStepActionById = (id: StepActionId): StepActionDefinition =>
  catalogById.get(id) as StepActionDefinition

export const resolveStepActionId = (value: unknown): StepActionId | '' => {
  if (typeof value !== 'string') return ''
  const normalized = normalizeText(value)
  if (!normalized) return ''

  return actionAliasLookup.get(normalized) ?? ''
}

export const getActionDisplayName = (id: StepActionId | '') => (id ? getStepActionById(id).displayName : 'Select Action')

export const getActionIcon = (id: StepActionId | '') => (id ? getStepActionById(id).icon : 'ST')
