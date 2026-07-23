export const INGREDIENT_CATEGORY_ORDER = [
  'Liquid',
  'Pantry',
  'Produce',
  'Protein',
  'Dairy',
  'Other',
] as const

export type IngredientCategory = (typeof INGREDIENT_CATEGORY_ORDER)[number]

export const INGREDIENT_CATALOG = [
  { id: 'water', name: 'Water', category: 'Liquid', icon: 'WT', defaultUnit: 'ml' },
  { id: 'oil', name: 'Oil', category: 'Liquid', icon: 'OL', defaultUnit: 'tbsp' },
  { id: 'salt', name: 'Salt', category: 'Pantry', icon: 'SA', defaultUnit: 'tsp' },
  { id: 'sugar', name: 'Sugar', category: 'Pantry', icon: 'SG', defaultUnit: 'tsp' },
  { id: 'rice', name: 'Rice', category: 'Pantry', icon: 'RC', defaultUnit: 'cup' },
  { id: 'onion', name: 'Onion', category: 'Produce', icon: 'ON', defaultUnit: 'piece' },
  { id: 'tomato', name: 'Tomato', category: 'Produce', icon: 'TM', defaultUnit: 'piece' },
  { id: 'garlic', name: 'Garlic', category: 'Produce', icon: 'GC', defaultUnit: 'clove' },
  { id: 'ginger', name: 'Ginger', category: 'Produce', icon: 'GI', defaultUnit: 'inch' },
  { id: 'chili', name: 'Chili', category: 'Produce', icon: 'CH', defaultUnit: 'piece' },
  { id: 'potato', name: 'Potato', category: 'Produce', icon: 'PT', defaultUnit: 'piece' },
  { id: 'carrot', name: 'Carrot', category: 'Produce', icon: 'CR', defaultUnit: 'piece' },
  { id: 'capsicum', name: 'Capsicum', category: 'Produce', icon: 'CP', defaultUnit: 'piece' },
  { id: 'egg', name: 'Egg', category: 'Protein', icon: 'EG', defaultUnit: 'piece' },
  { id: 'milk', name: 'Milk', category: 'Dairy', icon: 'MK', defaultUnit: 'ml' },
  { id: 'butter', name: 'Butter', category: 'Dairy', icon: 'BT', defaultUnit: 'tbsp' },
  { id: 'chicken', name: 'Chicken', category: 'Protein', icon: 'CK', defaultUnit: 'g' },
  { id: 'custom', name: 'Custom Ingredient', category: 'Other', icon: 'CU', defaultUnit: 'unit' },
] as const

export type IngredientId = (typeof INGREDIENT_CATALOG)[number]['id']

export type IngredientDefinition = {
  id: IngredientId
  name: string
  category: IngredientCategory
  icon: string
  defaultUnit: string
}

const ingredientById = new Map<IngredientId, IngredientDefinition>(
  INGREDIENT_CATALOG.map((ingredient) => [ingredient.id, ingredient])
)

const normalizeText = (value: string) => value.trim().toLowerCase().replace(/\s+/g, ' ')

const nameLookup = new Map<string, IngredientId>()
for (const ingredient of INGREDIENT_CATALOG) {
  nameLookup.set(normalizeText(ingredient.id), ingredient.id)
  nameLookup.set(normalizeText(ingredient.name), ingredient.id)
}

export const INGREDIENTS_BY_CATEGORY: Readonly<Record<IngredientCategory, readonly IngredientDefinition[]>> =
  INGREDIENT_CATEGORY_ORDER.reduce((accumulator, category) => {
    accumulator[category] = INGREDIENT_CATALOG.filter((ingredient) => ingredient.category === category)
    return accumulator
  }, {} as Record<IngredientCategory, readonly IngredientDefinition[]>)

export const CUSTOM_INGREDIENT_ID: IngredientId = 'custom'

export const isIngredientId = (value: unknown): value is IngredientId =>
  typeof value === 'string' && ingredientById.has(value as IngredientId)

export const getIngredientById = (id: IngredientId): IngredientDefinition =>
  ingredientById.get(id) as IngredientDefinition

export const resolveIngredientId = (value: unknown): IngredientId | '' => {
  if (typeof value !== 'string') return ''
  const normalized = normalizeText(value)
  if (!normalized) return ''
  return nameLookup.get(normalized) ?? ''
}

export const resolveIngredientInput = (value: string): { ingredientId: IngredientId | ''; customIngredientName: string } => {
  const normalized = normalizeText(value)
  if (!normalized) {
    return { ingredientId: '', customIngredientName: '' }
  }

  const match = nameLookup.get(normalized)
  if (match) {
    if (match === CUSTOM_INGREDIENT_ID) {
      return { ingredientId: CUSTOM_INGREDIENT_ID, customIngredientName: '' }
    }
    return { ingredientId: match, customIngredientName: '' }
  }

  return {
    ingredientId: CUSTOM_INGREDIENT_ID,
    customIngredientName: value.trim(),
  }
}

export const getIngredientDisplayName = (ingredientId: IngredientId | '', customIngredientName = '') => {
  if (!ingredientId) return ''
  if (ingredientId === CUSTOM_INGREDIENT_ID) return customIngredientName.trim() || 'Custom Ingredient'
  return getIngredientById(ingredientId).name
}

export const getIngredientDefaultUnit = (ingredientId: IngredientId | '') => {
  if (!ingredientId || ingredientId === CUSTOM_INGREDIENT_ID) return ''
  return getIngredientById(ingredientId).defaultUnit
}

export const getIngredientSearchValue = (ingredientId: IngredientId | '', customIngredientName = '') =>
  getIngredientDisplayName(ingredientId, customIngredientName)
