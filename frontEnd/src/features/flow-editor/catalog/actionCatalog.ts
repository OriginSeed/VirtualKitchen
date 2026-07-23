export const ACTION_CATEGORY_ORDER = [
  'Preparation',
  'Cooking',
  'Ingredient',
  'Timing',
  'Finish',
  'Other',
] as const

export type ActionCategory = (typeof ACTION_CATEGORY_ORDER)[number]

export const STEP_ACTION_CATALOG = [
  { id: 'wash', displayName: 'Wash', icon: 'WA', category: 'Preparation' },
  { id: 'peel', displayName: 'Peel', icon: 'PL', category: 'Preparation' },
  { id: 'cut', displayName: 'Cut', icon: 'CT', category: 'Preparation' },
  { id: 'chop', displayName: 'Chop', icon: 'CH', category: 'Preparation' },
  { id: 'slice', displayName: 'Slice', icon: 'SL', category: 'Preparation' },
  { id: 'dice', displayName: 'Dice', icon: 'DC', category: 'Preparation' },
  { id: 'grate', displayName: 'Grate', icon: 'GR', category: 'Preparation' },
  { id: 'crush', displayName: 'Crush', icon: 'CR', category: 'Preparation' },
  { id: 'grind', displayName: 'Grind', icon: 'GD', category: 'Preparation' },
  { id: 'marinate', displayName: 'Marinate', icon: 'MR', category: 'Preparation' },

  { id: 'heat', displayName: 'Heat', icon: 'HT', category: 'Cooking' },
  { id: 'boil', displayName: 'Boil', icon: 'BL', category: 'Cooking' },
  { id: 'fry', displayName: 'Fry', icon: 'FR', category: 'Cooking' },
  { id: 'deep-fry', displayName: 'Deep Fry', icon: 'DF', category: 'Cooking' },
  { id: 'steam', displayName: 'Steam', icon: 'SM', category: 'Cooking' },
  { id: 'bake', displayName: 'Bake', icon: 'BK', category: 'Cooking' },
  { id: 'roast', displayName: 'Roast', icon: 'RS', category: 'Cooking' },
  { id: 'grill', displayName: 'Grill', icon: 'GL', category: 'Cooking' },
  { id: 'simmer', displayName: 'Simmer', icon: 'SI', category: 'Cooking' },
  { id: 'pressure-cook', displayName: 'Pressure Cook', icon: 'PC', category: 'Cooking' },

  { id: 'add', displayName: 'Add', icon: 'AD', category: 'Ingredient' },
  { id: 'pour', displayName: 'Pour', icon: 'PO', category: 'Ingredient' },
  { id: 'mix', displayName: 'Mix', icon: 'MX', category: 'Ingredient' },
  { id: 'stir', displayName: 'Stir', icon: 'ST', category: 'Ingredient' },
  { id: 'coat', displayName: 'Coat', icon: 'CO', category: 'Ingredient' },
  { id: 'season', displayName: 'Season', icon: 'SN', category: 'Ingredient' },
  { id: 'garnish', displayName: 'Garnish', icon: 'GN', category: 'Ingredient' },

  { id: 'wait', displayName: 'Wait', icon: 'WT', category: 'Timing' },
  { id: 'rest', displayName: 'Rest', icon: 'RE', category: 'Timing' },

  { id: 'taste', displayName: 'Taste', icon: 'TS', category: 'Finish' },
  { id: 'serve', displayName: 'Serve', icon: 'SV', category: 'Finish' },

  { id: 'custom', displayName: 'Custom', icon: 'CU', category: 'Other' },
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
