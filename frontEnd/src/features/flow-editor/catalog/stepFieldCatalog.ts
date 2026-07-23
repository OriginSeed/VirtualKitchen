export const SPECIFICATION_OPTIONS = [
  'Fine',
  'Medium',
  'Large',
  'Thin Slice',
  'Thick Slice',
  'Julienne',
  'Diced',
  'Chopped',
  'Custom',
] as const

export const UNIT_OPTIONS = [
  'Piece',
  'g',
  'kg',
  'ml',
  'L',
  'tsp',
  'tbsp',
  'Cup',
  'Pinch',
  'Custom',
] as const

export const FLAME_OPTIONS = ['None', 'Low', 'Medium', 'High'] as const

export const DURATION_UNIT_OPTIONS = ['seconds', 'minutes', 'hours'] as const

export const REPEAT_INTERVAL_UNIT_OPTIONS = ['seconds', 'minutes', 'hours'] as const

export type SpecificationOption = (typeof SPECIFICATION_OPTIONS)[number]
export type UnitOption = (typeof UNIT_OPTIONS)[number]
export type FlameOption = (typeof FLAME_OPTIONS)[number]
export type DurationUnitOption = (typeof DURATION_UNIT_OPTIONS)[number]
export type RepeatIntervalUnitOption = (typeof REPEAT_INTERVAL_UNIT_OPTIONS)[number]

const normalizeText = (value: string) => value.trim().toLowerCase()

const normalizeDurationUnit = (value: string): DurationUnitOption | '' => {
  const normalized = normalizeText(value)
  if (!normalized) return ''

  if (normalized === 'second' || normalized === 'seconds' || normalized === 'sec' || normalized === 's') return 'seconds'
  if (normalized === 'minute' || normalized === 'minutes' || normalized === 'min' || normalized === 'm') return 'minutes'
  if (normalized === 'hour' || normalized === 'hours' || normalized === 'hr' || normalized === 'h') return 'hours'
  return ''
}

export const toCatalogOption = <T extends readonly string[]>(value: unknown, options: T, fallback = ''): T[number] | '' => {
  if (typeof value !== 'string') return fallback as T[number] | ''
  const normalized = normalizeText(value)
  if (!normalized) return fallback as T[number] | ''

  const matched = options.find((option) => normalizeText(option) === normalized)
  return matched ?? (fallback as T[number] | '')
}

export const buildDurationLabel = (durationValue: string, durationUnit: DurationUnitOption | '') => {
  const value = durationValue.trim()
  if (!value || !durationUnit) return ''
  return `${value} ${durationUnit}`
}

export const parseDurationLabel = (value: string): { durationValue: string; durationUnit: DurationUnitOption | '' } => {
  const text = value.trim()
  if (!text) return { durationValue: '', durationUnit: '' }

  const match = text.match(/^(\d+(?:\.\d+)?)\s*([a-zA-Z]+)/)
  if (!match) return { durationValue: '', durationUnit: '' }

  const parsedUnit = normalizeDurationUnit(match[2])
  if (!parsedUnit) return { durationValue: '', durationUnit: '' }

  return {
    durationValue: match[1],
    durationUnit: parsedUnit,
  }
}

export const buildRepeatIntervalLabel = (actionLabel: string, repeatEveryValue: string, repeatEveryUnit: RepeatIntervalUnitOption | '') => {
  const value = repeatEveryValue.trim()
  if (!value || !repeatEveryUnit) return ''

  const prefix = actionLabel.trim() || 'Repeat'
  return `${prefix} every ${value} ${repeatEveryUnit}`
}

export const parseRepeatIntervalLabel = (value: string) => {
  const text = value.trim()
  if (!text) {
    return { repeatPrefix: '', repeatEveryValue: '', repeatEveryUnit: '' as RepeatIntervalUnitOption | '' }
  }

  const match = text.match(/^(.*?)\s+every\s+(\d+(?:\.\d+)?)\s+([a-zA-Z]+)/i)
  if (!match) {
    return { repeatPrefix: '', repeatEveryValue: '', repeatEveryUnit: '' as RepeatIntervalUnitOption | '' }
  }

  return {
    repeatPrefix: match[1].trim(),
    repeatEveryValue: match[2].trim(),
    repeatEveryUnit: normalizeDurationUnit(match[3]),
  }
}
