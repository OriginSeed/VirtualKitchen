export const FLAME_LEVEL_CATALOG = [
  { id: 'low', label: 'Low' },
  { id: 'medium', label: 'Medium' },
  { id: 'high', label: 'High' },
  { id: 'custom', label: 'Custom Level' },
] as const

export type FlameLevelId = (typeof FLAME_LEVEL_CATALOG)[number]['id']

export type FlameLevelDefinition = {
  id: FlameLevelId
  label: string
}

export const CUSTOM_FLAME_LEVEL_ID: FlameLevelId = 'custom'

const flameById = new Map<FlameLevelId, FlameLevelDefinition>(FLAME_LEVEL_CATALOG.map((level) => [level.id, level]))

const normalizeText = (value: string) => value.trim().toLowerCase().replace(/\s+/g, ' ')

const flameAliasLookup = new Map<string, FlameLevelId>()
for (const level of FLAME_LEVEL_CATALOG) {
  flameAliasLookup.set(normalizeText(level.id), level.id)
  flameAliasLookup.set(normalizeText(level.label), level.id)
}

export const resolveFlameLevelId = (value: unknown): FlameLevelId | '' => {
  if (typeof value !== 'string') return ''
  const normalized = normalizeText(value)
  if (!normalized) return ''
  return flameAliasLookup.get(normalized) ?? ''
}

export const getFlameLevelById = (id: FlameLevelId): FlameLevelDefinition =>
  flameById.get(id) as FlameLevelDefinition

export const getFlameLevelDisplayName = (levelId: FlameLevelId | '', customLevel = '') => {
  if (!levelId) return ''
  if (levelId === CUSTOM_FLAME_LEVEL_ID) return customLevel.trim()
  return getFlameLevelById(levelId).label
}
