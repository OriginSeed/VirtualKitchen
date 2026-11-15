/**
 * The bench run file (bench.json): which recipes to run, with which model combinations, as which
 * account. Credentials are never stored here: the file only names the environment variables that
 * hold them.
 */

import { readFileSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { readFlowDoc, type FlowDoc } from './flowDoc.ts'

export type Stage = 'text' | 'image' | 'speech'
export const ALL_STAGES: Stage[] = ['text', 'image', 'speech']

/** bench.json modality name -> backend AiCapability. */
export const CAPABILITY: Record<Stage, string> = {
  text: 'TEXT_TO_TEXT',
  image: 'TEXT_TO_IMAGE',
  speech: 'TEXT_TO_SPEECH',
}

export type AccountRef = {
  /** Env var holding the account's email. */
  emailEnv?: string
  /** Env var holding the account's password. */
  passwordEnv?: string
  /** Env var holding a ready JWT (skips login). */
  tokenEnv?: string
}

export type RecipeInput = {
  id: string
  text: string
  source: string
  /** A saved flow to build the recipe from instead of generating one: no text model is ever called for it. */
  flow?: FlowDoc
}

/**
 * Image settings a combo can name directly; each is an admin setting of the "images" section, applied
 * while the combo runs. Omitted = whatever the server has.
 */
export const IMAGE_SETTINGS = {
  /** One empty-scene image per recipe, sent as a reference with the cooking steps. */
  anchor: 'sceneAnchor.enabled',
  /** Each cooking step drawn against the previous step's image. Part of the focused view. */
  reference: 'referenceChain.enabled',
  /** The Focused Cooking View; false makes images as they were before it existed. */
  focusedView: 'focusedView.enabled',
  /** Steps the prompt writer marks as looking like the step before reuse its image instead of a new one. */
  skipLookAlike: 'lookAlike.skip',
} as const
/** How many Preparation steps share one generated image (1, 2 or 4), as a combo's "stepsPerImage". */
export const STEPS_PER_IMAGE_SETTING = 'preparation.stepsPerImage'
export const STEPS_PER_IMAGE_VALUES = [1, 2, 4] as const
export const IMAGE_SECTION = 'images'

export type Combo = {
  name: string
  /** Generates the flow. Omitted/null in a group: the combo reuses the group's flow. */
  text?: string
  /** Omitted/null: no images. */
  image?: string
  /** Omitted/null: no narration. */
  speech?: string
  /** Index of the combo's group in bench.json's "combos". */
  group: number
  /**
   * True for combos listed together in an inner list: they share one flow per recipe and text model,
   * and one narration per flow and speech model. A combo on its own regenerates for every run.
   */
  shared: boolean
  /** The text model whose flow this combo runs on: its own, else the first one named in its group. */
  flowText?: string
  /**
   * Admin runtime config to apply while this combo runs, by section then key, e.g.
   * {"images": {"sceneAnchor.enabled": "false"}}. Reverted before the next combo and at the end.
   */
  adminConfig?: Record<string, Record<string, string>>
}

export type BenchConfig = {
  baseUrl: string
  auth: { user: AccountRef; admin?: AccountRef }
  recipes: RecipeInput[]
  /** Every combo, in running order (the groups flattened). */
  combos: Combo[]
  repeats: number
  /** Parallel runs inside one combo (combos always run one after another: they share the user's model preferences). */
  concurrency: number
  /** Credits the admin grants the user before the run (0 = none). */
  grantCredits: number
  /** Let the admin temporarily enable/make user-selectable any model a combo names; restored afterwards. */
  allowAdminOverrides: boolean
  failOnFallback: boolean
  cleanup: 'keep' | 'delete'
  downloadAssets: boolean
  /** Per-stage polling timeouts in seconds. */
  timeouts: Record<Stage, number>
  configDir: string
}

const fail = (message: string): never => {
  throw new Error(`bench config: ${message}`)
}

const asString = (value: unknown, field: string): string => {
  if (typeof value !== 'string' || value.trim() === '') fail(`"${field}" must be a non-empty string`)
  return (value as string).trim()
}

const optionalString = (value: unknown, field: string): string | undefined =>
  value === undefined || value === null || value === '' ? undefined : asString(value, field)

const account = (raw: unknown, field: string): AccountRef => {
  if (!raw || typeof raw !== 'object') fail(`"${field}" must be an object`)
  const value = raw as Record<string, unknown>
  for (const key of Object.keys(value)) {
    if (!['emailEnv', 'passwordEnv', 'tokenEnv'].includes(key)) {
      fail(`"${field}.${key}" is not allowed: put credentials in environment variables and name them with emailEnv/passwordEnv/tokenEnv`)
    }
  }
  const ref: AccountRef = {
    emailEnv: optionalString(value.emailEnv, `${field}.emailEnv`),
    passwordEnv: optionalString(value.passwordEnv, `${field}.passwordEnv`),
    tokenEnv: optionalString(value.tokenEnv, `${field}.tokenEnv`),
  }
  if (!ref.tokenEnv && !(ref.emailEnv && ref.passwordEnv)) fail(`"${field}" needs tokenEnv, or emailEnv and passwordEnv`)
  return ref
}

/** {section: {key: value}}; booleans and numbers are accepted and sent as strings (the API's format). */
const adminConfig = (raw: unknown, field: string): Combo['adminConfig'] => {
  if (raw === undefined || raw === null) return undefined
  if (typeof raw !== 'object' || Array.isArray(raw)) return fail(`"${field}" must be an object of sections`)
  const result: Record<string, Record<string, string>> = {}
  for (const [section, values] of Object.entries(raw as Record<string, unknown>)) {
    if (!values || typeof values !== 'object' || Array.isArray(values)) fail(`"${field}.${section}" must be an object of settings`)
    result[section] = {}
    for (const [key, value] of Object.entries(values as Record<string, unknown>)) {
      if (!['string', 'boolean', 'number'].includes(typeof value)) fail(`"${field}.${section}.${key}" must be a string, number or boolean`)
      result[section][key] = String(value)
    }
  }
  return Object.keys(result).length > 0 ? result : undefined
}

/**
 * Folds a combo's image settings (the booleans anchor, reference, focusedView, skipLookAlike, and stepsPerImage)
 * into its adminConfig. Naming the same setting both ways is an error rather than a silent winner.
 */
const withImageSettings = (value: Record<string, unknown>, config: Combo['adminConfig'], field: string): Combo['adminConfig'] => {
  const images: Record<string, string> = { ...(config?.[IMAGE_SECTION] ?? {}) }
  let any = false
  for (const [name, key] of Object.entries(IMAGE_SETTINGS)) {
    const setting = value[name]
    if (setting === undefined || setting === null) continue
    if (typeof setting !== 'boolean') fail(`"${field}.${name}" must be true or false`)
    if (key in images) fail(`"${field}" sets ${IMAGE_SECTION}.${key} twice: as "${name}" and in "adminConfig"`)
    images[key] = String(setting)
    any = true
  }
  const stepsPerImage = value.stepsPerImage
  if (stepsPerImage !== undefined && stepsPerImage !== null) {
    if (!STEPS_PER_IMAGE_VALUES.includes(stepsPerImage as 1 | 2 | 4)) {
      fail(`"${field}.stepsPerImage" must be one of ${STEPS_PER_IMAGE_VALUES.join(', ')}`)
    }
    if (STEPS_PER_IMAGE_SETTING in images) {
      fail(`"${field}" sets ${IMAGE_SECTION}.${STEPS_PER_IMAGE_SETTING} twice: as "stepsPerImage" and in "adminConfig"`)
    }
    images[STEPS_PER_IMAGE_SETTING] = String(stepsPerImage)
    any = true
  }
  if (images[IMAGE_SETTINGS.reference] === 'true' && images[IMAGE_SETTINGS.focusedView] === 'false') {
    fail(`"${field}" has "reference": true with "focusedView": false: drawing against the previous step is part of the focused view, so it would not happen`)
  }
  return any ? { ...config, [IMAGE_SECTION]: images } : config
}

/**
 * Works out which flow each combo runs on, and fails when one would have none: a combo without a
 * "text" model needs either an earlier combo in its group that generates the flow, or recipes that
 * all come from flow files. Call again after filtering combos or recipes.
 */
export function resolveFlowSources(combos: Combo[], recipes: RecipeInput[]): void {
  const needsGeneration = recipes.some((recipe) => !recipe.flow)
  const groupText = new Map<number, string>()
  for (const combo of combos) {
    if (combo.text && combo.shared && !groupText.has(combo.group)) groupText.set(combo.group, combo.text)
    combo.flowText = combo.text ?? (combo.shared ? groupText.get(combo.group) : undefined)
    if (combo.flowText || !needsGeneration) continue
    fail(combo.shared
      ? `combo "${combo.name}" has no "text" model and no combo before it in its group generates the flow: put a combo with "text" first, or give every recipe a "flow" file`
      : `combo "${combo.name}" needs a "text" model (or put it in a group after a combo that has one, or give every recipe a "flow" file)`)
  }
}

export function loadConfig(path: string): BenchConfig {
  const file = resolve(path)
  const configDir = dirname(file)
  let raw: Record<string, unknown>
  try {
    raw = JSON.parse(readFileSync(file, 'utf8'))
  } catch (error) {
    return fail(`cannot read ${file}: ${(error as Error).message}`)
  }

  const auth = (raw.auth ?? fail('"auth" is required')) as Record<string, unknown>
  const recipesRaw = raw.recipes
  if (!Array.isArray(recipesRaw) || recipesRaw.length === 0) fail('"recipes" must be a non-empty array')
  const recipes = (recipesRaw as Record<string, unknown>[]).map((entry, index): RecipeInput => {
    const id = asString(entry.id, `recipes[${index}].id`)
    if (entry.flow) {
      const source = resolve(configDir, asString(entry.flow, `recipes[${index}].flow`))
      const flow = readFlowDoc(source)
      return { id, text: flow.recipeText, source, flow }
    }
    if (entry.file) {
      const source = resolve(configDir, asString(entry.file, `recipes[${index}].file`))
      const text = readFileSync(source, 'utf8').trim()
      if (!text) fail(`recipes[${index}] file ${source} is empty`)
      return { id, text, source }
    }
    return { id, text: asString(entry.text, `recipes[${index}].text`), source: 'inline' }
  })
  const recipeIds = new Set<string>()
  for (const recipe of recipes) {
    if (recipeIds.has(recipe.id)) fail(`duplicate recipe id "${recipe.id}"`)
    recipeIds.add(recipe.id)
  }

  const combosRaw = raw.combos
  if (!Array.isArray(combosRaw) || combosRaw.length === 0) fail('"combos" must be a non-empty array')
  // An inner list is a group of combos that share a flow; a combo on its own is a group of one.
  const combos = (combosRaw as unknown[]).flatMap((entry, group): Combo[] => {
    const members = Array.isArray(entry) ? entry : [entry]
    if (members.length === 0) fail(`combos[${group}] is an empty group`)
    return members.map((member, index): Combo => {
      const field = Array.isArray(entry) ? `combos[${group}][${index}]` : `combos[${group}]`
      if (!member || typeof member !== 'object' || Array.isArray(member)) fail(`"${field}" must be a combo object`)
      const value = member as Record<string, unknown>
      return {
        name: asString(value.name, `${field}.name`),
        text: optionalString(value.text, `${field}.text`),
        image: optionalString(value.image, `${field}.image`),
        speech: optionalString(value.speech, `${field}.speech`),
        group,
        shared: Array.isArray(entry) && members.length > 1,
        adminConfig: withImageSettings(value, adminConfig(value.adminConfig, `${field}.adminConfig`), field),
      }
    })
  })
  const comboNames = new Set<string>()
  for (const combo of combos) {
    if (comboNames.has(combo.name)) fail(`duplicate combo name "${combo.name}"`)
    comboNames.add(combo.name)
  }
  resolveFlowSources(combos, recipes)

  const int = (value: unknown, field: string, fallback: number, min: number) => {
    if (value === undefined) return fallback
    if (!Number.isInteger(value) || (value as number) < min) fail(`"${field}" must be an integer >= ${min}`)
    return value as number
  }
  const timeoutsRaw = (raw.timeouts ?? {}) as Record<string, unknown>

  const cleanup = raw.cleanup ?? 'keep'
  if (cleanup !== 'keep' && cleanup !== 'delete') fail('"cleanup" must be "keep" or "delete"')

  return {
    baseUrl: asString(raw.baseUrl ?? 'http://localhost:8080', 'baseUrl').replace(/\/+$/, ''),
    auth: {
      user: account(auth.user, 'auth.user'),
      admin: auth.admin ? account(auth.admin, 'auth.admin') : undefined,
    },
    recipes,
    combos,
    repeats: int(raw.repeats, 'repeats', 1, 1),
    concurrency: int(raw.concurrency, 'concurrency', 1, 1),
    grantCredits: int(raw.grantCredits, 'grantCredits', 0, 0),
    allowAdminOverrides: raw.allowAdminOverrides === true,
    failOnFallback: raw.failOnFallback === true,
    cleanup: cleanup as 'keep' | 'delete',
    downloadAssets: raw.downloadAssets !== false,
    timeouts: {
      text: int(timeoutsRaw.text, 'timeouts.text', 600, 10),
      image: int(timeoutsRaw.image, 'timeouts.image', 1200, 10),
      speech: int(timeoutsRaw.speech, 'timeouts.speech', 600, 10),
    },
    configDir,
  }
}

/** The combos of each group, in bench.json order. */
export const comboGroups = (combos: Combo[]): Combo[][] => {
  const groups = new Map<number, Combo[]>()
  for (const combo of combos) groups.set(combo.group, [...(groups.get(combo.group) ?? []), combo])
  return [...groups.values()]
}

/** The stages a combo runs, limited by --stages. */
export const comboStages = (combo: Combo, only: Stage[]): Stage[] =>
  ALL_STAGES.filter((stage) => only.includes(stage) && combo[stage])
