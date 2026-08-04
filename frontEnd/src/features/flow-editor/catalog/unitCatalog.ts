export const UNIT_CATEGORY_ORDER = ['Volume', 'Weight', 'Count', 'Spoon', 'Other'] as const

export type UnitCategory = (typeof UNIT_CATEGORY_ORDER)[number]

export const UNIT_CATALOG = [
  { id: 'ml', label: 'Milliliter (ml)', shortLabel: 'ml', category: 'Volume' },
  { id: 'l', label: 'Liter (L)', shortLabel: 'L', category: 'Volume' },
  { id: 'cup', label: 'Cup', shortLabel: 'cup', category: 'Volume' },
  { id: 'g', label: 'Gram (g)', shortLabel: 'g', category: 'Weight' },
  { id: 'kg', label: 'Kilogram (kg)', shortLabel: 'kg', category: 'Weight' },
  { id: 'piece', label: 'Piece', shortLabel: 'piece', category: 'Count' },
  { id: 'tsp', label: 'Teaspoon (tsp)', shortLabel: 'tsp', category: 'Spoon' },
  { id: 'tbsp', label: 'Tablespoon (tbsp)', shortLabel: 'tbsp', category: 'Spoon' },
  { id: 'pinch', label: 'Pinch', shortLabel: 'pinch', category: 'Other' },
  { id: 'custom', label: 'Custom Unit', shortLabel: 'custom', category: 'Other' },
] as const

export type UnitId = (typeof UNIT_CATALOG)[number]['id']

export type UnitDefinition = {
  id: UnitId
  label: string
  shortLabel: string
  category: UnitCategory
}

export const CUSTOM_UNIT_ID: UnitId = 'custom'

const unitById = new Map<UnitId, UnitDefinition>(UNIT_CATALOG.map((unit) => [unit.id, unit]))

const normalizeText = (value: string) => value.trim().toLowerCase().replace(/\s+/g, ' ')

const unitAliasLookup = new Map<string, UnitId>()
for (const unit of UNIT_CATALOG) {
  unitAliasLookup.set(normalizeText(unit.id), unit.id)
  unitAliasLookup.set(normalizeText(unit.label), unit.id)
  unitAliasLookup.set(normalizeText(unit.shortLabel), unit.id)
}

export const UNITS_BY_CATEGORY: Readonly<Record<UnitCategory, readonly UnitDefinition[]>> =
  UNIT_CATEGORY_ORDER.reduce((accumulator, category) => {
    accumulator[category] = UNIT_CATALOG.filter((unit) => unit.category === category)
    return accumulator
  }, {} as Record<UnitCategory, readonly UnitDefinition[]>)

export const isUnitId = (value: unknown): value is UnitId =>
  typeof value === 'string' && unitById.has(value as UnitId)

export const getUnitById = (unitId: UnitId): UnitDefinition =>
  unitById.get(unitId) as UnitDefinition

export const resolveUnitId = (value: unknown): UnitId | '' => {
  if (typeof value !== 'string') return ''
  const normalized = normalizeText(value)
  if (!normalized) return ''
  return unitAliasLookup.get(normalized) ?? ''
}

export const getUnitDisplayValue = (unitId: UnitId | '', customUnit = '') => {
  if (!unitId) return ''
  if (unitId === CUSTOM_UNIT_ID) return customUnit.trim()
  return getUnitById(unitId).shortLabel
}
