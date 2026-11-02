import type { ConfigField } from '../api/adminConfigApi.ts'

/** Pending edits: field key to the value the admin picked (secrets: the new clear value). */
export type ConfigDraft = Record<string, string>

export interface FieldGroup {
  name: string
  fields: ConfigField[]
}

/** Fields grouped under their headings, keeping the backend's order of both groups and fields. */
export function groupFields(fields: ConfigField[]): FieldGroup[] {
  const groups: FieldGroup[] = []
  const byName = new Map<string, FieldGroup>()
  for (const field of fields) {
    let group = byName.get(field.group)
    if (!group) {
      group = { name: field.group, fields: [] }
      byName.set(field.group, group)
      groups.push(group)
    }
    group.fields.push(field)
  }
  return groups
}

/** The value a field shows: the pending edit if any, else the live value. Secrets show nothing until typed. */
export function displayValue(field: ConfigField, draft: ConfigDraft): string {
  if (field.key in draft) return draft[field.key]
  return field.type === 'SECRET' ? '' : field.current
}

/**
 * What to send on save: only fields whose pending value differs from the live one. A blank secret means
 * "keep the current key" and is never sent.
 */
export function changedValues(fields: ConfigField[], draft: ConfigDraft): Record<string, string> {
  const changes: Record<string, string> = {}
  for (const field of fields) {
    if (!(field.key in draft)) continue
    const value = draft[field.key]
    if (field.type === 'SECRET') {
      if (value.trim() !== '') changes[field.key] = value.trim()
    } else if (field.type === 'TEXT') {
      // A blank text value is not a setting; to go back to the configured one, use "Reset to defaults".
      if (value.trim() !== '' && value.trim() !== field.current) changes[field.key] = value.trim()
    } else if (value !== field.current) {
      changes[field.key] = value
    }
  }
  return changes
}

export function isDirty(fields: ConfigField[], draft: ConfigDraft): boolean {
  return Object.keys(changedValues(fields, draft)).length > 0
}

/** Dropdown label for an option value: '' reads as "None", and the baseline is marked "(default)". */
export function optionLabel(field: ConfigField, value: string): string {
  const text = value === '' ? 'None' : value
  return value === field.baseline ? `${text} (default)` : text
}
