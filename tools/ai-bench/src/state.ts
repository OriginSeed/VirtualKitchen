/**
 * What a run changes outside its own recipes — the user's saved model choices and any admin
 * runtime-config overrides — recorded to out/<runId>/restore.json *before* changing it, so that
 * `bench restore` can put it back even if the run was killed.
 */

import { existsSync, readFileSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import type { ApiClient } from './api'

/** Admin config values by section, then key (e.g. images → sceneAnchor.enabled → "true"). */
export type AdminValues = Record<string, Record<string, string>>

export type RestoreState = {
  baseUrl: string
  userId: number
  /** The user's own saved choices (not the effective ones) before the run. */
  preferences: Record<string, string | null> | null
  /** Admin config values before the run, for every key the run changed. */
  adminConfig: AdminValues
  /** Older restore files: models section only. */
  adminModelConfig?: Record<string, string>
  /** Recipes the run created (for `cleanup: delete`). */
  recipeIds: number[]
  restored: boolean
}

export type PreferencesView = {
  modelKeys: Record<string, string | null> | null
  effective: Record<string, string | null>
  staleChoice: boolean
}
type ConfigField = { key: string; current: string; options: string[]; type: string }
type SectionView = { id: string; fields: ConfigField[] }

export const PREFERENCES = '/api/v1/users/me/ai-preferences'
const sectionPath = (section: string) => `/api/v1/admin/config/${encodeURIComponent(section)}`

export class RunState {
  /** Keys the current combo's adminConfig set ("section\u0000key"), reverted before the next combo. */
  private comboKeys = new Set<string>()

  private constructor(private readonly file: string, readonly data: RestoreState) {
    if (data.adminModelConfig) {
      data.adminConfig ??= {}
      data.adminConfig.models = { ...data.adminModelConfig, ...(data.adminConfig.models ?? {}) }
      delete data.adminModelConfig
    }
    data.adminConfig ??= {}
  }

  static create(outDir: string, baseUrl: string, userId: number): RunState {
    const state = new RunState(join(outDir, 'restore.json'), {
      baseUrl, userId, preferences: null, adminConfig: {}, recipeIds: [], restored: false,
    })
    state.save()
    return state
  }

  static load(outDir: string): RunState {
    const file = join(outDir, 'restore.json')
    if (!existsSync(file)) throw new Error(`${file} not found`)
    return new RunState(file, JSON.parse(readFileSync(file, 'utf8')))
  }

  save() {
    writeFileSync(this.file, JSON.stringify(this.data, null, 2))
  }

  async snapshotPreferences(user: ApiClient) {
    const view = await user.get<PreferencesView>(PREFERENCES)
    this.data.preferences = { ...(view.modelKeys ?? {}) }
    this.save()
  }

  addRecipe(recipeId: number) {
    this.data.recipeIds.push(recipeId)
    this.save()
  }

  /**
   * Sets admin config values in one section, recording each key's value from before the run first.
   * Unknown keys or values the field doesn't accept fail before anything changes. Returns the keys
   * that actually changed.
   */
  async setAdminValues(admin: ApiClient, section: string, values: Record<string, string>): Promise<string[]> {
    const view = await admin.get<SectionView>(sectionPath(section))
    const fields = new Map(view.fields.map((field) => [field.key, field]))
    const changes: Record<string, string> = {}
    for (const [key, value] of Object.entries(values)) {
      const field = fields.get(key)
      if (!field) throw new Error(`admin config section "${section}" has no setting "${key}"`)
      if (field.options.length > 0 && !field.options.includes(value)) {
        throw new Error(`admin config ${section}.${key} can't be "${value}" (allowed: ${field.options.join(', ')})`)
      }
      if (field.current === value) continue
      const originals = this.data.adminConfig[section] ??= {}
      if (!(key in originals)) originals[key] = field.current
      changes[key] = value
    }
    if (Object.keys(changes).length === 0) return []
    this.save() // record the old values before changing anything
    await admin.put(sectionPath(section), { values: changes })
    return Object.keys(changes)
  }

  /** Makes sure every model in `modelKeys` is enabled and user-selectable. Returns the keys it changed. */
  async ensureSelectable(admin: ApiClient, modelKeys: string[]): Promise<string[]> {
    const values: Record<string, string> = {}
    for (const key of modelKeys) {
      values[`model.${key}.enabled`] = 'true'
      values[`model.${key}.userSelectable`] = 'true'
    }
    return this.setAdminValues(admin, 'models', values)
  }

  /**
   * Applies one combo's adminConfig: what the previous combo set and this one doesn't goes back to
   * its value from before the run; this combo's values are set. Returns a description of changes.
   */
  async applyComboConfig(admin: ApiClient | null, config: AdminValues | undefined): Promise<string[]> {
    const wanted = new Map<string, string>()
    for (const [section, values] of Object.entries(config ?? {})) {
      for (const [key, value] of Object.entries(values)) wanted.set(`${section}\u0000${key}`, value)
    }
    const bySection = new Map<string, Record<string, string>>()
    const put = (id: string, value: string) => {
      const [section, key] = id.split('\u0000')
      const values = bySection.get(section) ?? {}
      values[key] = value
      bySection.set(section, values)
    }
    for (const id of this.comboKeys) {
      const [section, key] = id.split('\u0000')
      const original = this.data.adminConfig[section]?.[key]
      // No recorded original means the previous combo didn't actually change it: nothing to revert.
      if (!wanted.has(id) && original !== undefined) put(id, original)
    }
    wanted.forEach((value, id) => put(id, value))
    if (bySection.size === 0) return []
    if (!admin) throw new Error('a combo sets "adminConfig" but no admin account is configured')

    const changed: string[] = []
    for (const [section, values] of bySection) {
      for (const key of await this.setAdminValues(admin, section, values)) changed.push(`${section}.${key}=${values[key]}`)
    }
    this.comboKeys = new Set(wanted.keys())
    return changed
  }

  /** Puts back the user's choices and the admin config. Safe to call twice. */
  async restore(user: ApiClient, admin: ApiClient | null, log: (line: string) => void) {
    const errors: string[] = []
    if (this.data.preferences) {
      try {
        await user.put(PREFERENCES, { modelKeys: this.data.preferences })
        log(`restored model preferences: ${JSON.stringify(this.data.preferences)}`)
      } catch (error) {
        errors.push(`preferences: ${(error as Error).message}`)
      }
    }
    for (const [section, values] of Object.entries(this.data.adminConfig)) {
      if (Object.keys(values).length === 0) continue
      if (!admin) {
        errors.push(`admin config "${section}" needs an admin session to restore: ${JSON.stringify(values)}`)
        continue
      }
      try {
        // Writing a field's baseline value removes the override (RuntimeConfigService.update).
        await admin.put(sectionPath(section), { values })
        log(`restored admin config ${section}: ${JSON.stringify(values)}`)
      } catch (error) {
        errors.push(`admin config ${section}: ${(error as Error).message}`)
      }
    }
    this.comboKeys.clear()
    this.data.restored = errors.length === 0
    this.save()
    if (errors.length > 0) throw new Error(`restore incomplete:\n  ${errors.join('\n  ')}`)
  }
}
