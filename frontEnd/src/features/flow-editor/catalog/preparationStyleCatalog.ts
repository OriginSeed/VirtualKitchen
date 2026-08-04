export const PREPARATION_STYLE_CATALOG = [
  { id: 'fine', label: 'Fine' },
  { id: 'medium', label: 'Medium' },
  { id: 'large', label: 'Large' },
  { id: 'thin-slice', label: 'Thin Slice' },
  { id: 'thick-slice', label: 'Thick Slice' },
  { id: 'julienne', label: 'Julienne' },
  { id: 'rough-chop', label: 'Rough Chop' },
  { id: 'custom', label: 'Custom Style' },
] as const

export type PreparationStyleId = (typeof PREPARATION_STYLE_CATALOG)[number]['id']

export type PreparationStyleDefinition = {
  id: PreparationStyleId
  label: string
}

export const CUSTOM_PREPARATION_STYLE_ID: PreparationStyleId = 'custom'

const styleById = new Map<PreparationStyleId, PreparationStyleDefinition>(
  PREPARATION_STYLE_CATALOG.map((style) => [style.id, style])
)

const normalizeText = (value: string) => value.trim().toLowerCase().replace(/\s+/g, ' ')

const styleAliasLookup = new Map<string, PreparationStyleId>()
for (const style of PREPARATION_STYLE_CATALOG) {
  styleAliasLookup.set(normalizeText(style.id), style.id)
  styleAliasLookup.set(normalizeText(style.label), style.id)
}

export const resolvePreparationStyleId = (value: unknown): PreparationStyleId | '' => {
  if (typeof value !== 'string') return ''
  const normalized = normalizeText(value)
  if (!normalized) return ''
  return styleAliasLookup.get(normalized) ?? ''
}

export const getPreparationStyleById = (id: PreparationStyleId): PreparationStyleDefinition =>
  styleById.get(id) as PreparationStyleDefinition

export const getPreparationStyleDisplayName = (styleId: PreparationStyleId | '', customStyle = '') => {
  if (!styleId) return ''
  if (styleId === CUSTOM_PREPARATION_STYLE_ID) return customStyle.trim()
  return getPreparationStyleById(styleId).label
}
