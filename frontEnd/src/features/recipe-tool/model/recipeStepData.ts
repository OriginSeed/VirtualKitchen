/**
 * Recipe STEP node data model of the Recipe Process (Recipe -> Process ->
 * ProcessNode): an Action plus its "Action On" targets — a step's action can
 * apply to *multiple* ingredients (from the database ingredient catalog,
 * loaded by catalog/ingredientCatalog.ts) and/or
 * *multiple* subprocess references — and its cooking properties (flame,
 * temperature, duration, repeat interval). Which of those fields apply is
 * decided per action by the shared catalog (catalog/actionSchemaCatalog.ts).
 *
 * This is stored as free-form JSON inside `ProcessNode.data` (a
 * `Map<String,Object>` on the backend — see Process.java). Data saved before
 * catalog v2 (Process `UnitType` units, free-text temperature) is mapped onto
 * the new shape on read, and written back in the new shape on the next save.
 */

import { CUSTOM_ACTION_ID, getActionDisplayName, resolveStepActionId, type StepActionId } from '../../../shared/catalog/actionCatalog'
import { STEP_LEVEL_FIELD_KEYS, isStepFieldEnabled, type StepSchemaFieldKey } from '../../../shared/catalog/actionSchemaCatalog'
import {
  CUSTOM_INGREDIENT_ID,
  getIngredientDefaultUnit,
  getIngredientDisplayName,
  isIngredientCatalogLoaded,
  isIngredientId,
  resolveIngredientId,
  type IngredientId,
} from '../../../shared/catalog/ingredientCatalog'
import {
  resolvePreparationStyleId,
  type PreparationStyleId,
} from '../../../shared/catalog/preparationStyleCatalog'
import { resolveFlameLevelId, type FlameLevelId } from '../../../shared/catalog/flameLevelCatalog'
import {
  CUSTOM_INGREDIENT_PREFIX,
  ingredientAnnotationId,
  parsePreparedAnnotationId,
  preparedAnnotationId,
  type Annotation,
  type AnnotationLabelResolver,
  type StepAnnotationTargets,
  annotationsOf,
  sameAnnotationTarget,
  unwrapAnnotations,
} from './stepAnnotations'
import { deriveStepTitle } from './stepTitle'
import { CUSTOM_UNIT_ID, formatQuantityWithUnit, isQuantifiableUnit, resolveUnitId, type UnitId } from '../../../shared/catalog/unitCatalog'
import {
  buildDurationLabel,
  buildTemperatureLabel,
  isDurationUnitOption,
  parseTemperatureLabel,
  resolveTemperatureUnit,
  type DurationUnitOption,
  type TemperatureUnitOption,
} from '../../../shared/catalog/stepFieldCatalog'

/**
 * One "On:" ingredient target of a step's Action On — a database ingredient
 * (catalog/ingredientCatalog.ts), identified by its id as a string (e.g.
 * "42"), or "custom" with a free-text name. Stored as an opaque field inside
 * the STEP's data bag. Older data used the static catalog's slugs ("onion");
 * those are mapped to the database id on read (see resolveStoredIngredientId).
 *
 * Preparation style is per-ingredient (e.g. Cut → onion: medium, tomato:
 * large), not a step-level field — the same action can be applied
 * differently to each of its Action On ingredients.
 */
export type ActionOnIngredient = {
  ingredientId: IngredientId
  /** Only meaningful when `ingredientId` is the catalog's "custom" entry. */
  customIngredientName?: string
  /** Null when no amount applies (not stated, or a non-numeric unit such as "to-taste"). */
  quantity: number | null
  /** A catalog unit id (unitCatalog.ts); '' when none. */
  unit: UnitId | ''
  /** The unit's own name when `unit` is the catalog's "custom" entry (e.g. "knob"). */
  customUnit?: string
  notes?: string
  preparationStyleId?: PreparationStyleId | ''
  customPreparationStyle?: string
}

/**
 * A prepared component the step uses: one step's output in the recipe's Preparation process (`stepId` — a flow's
 * final step names the flow's output), or, in data saved before the single Preparation, a whole subprocess.
 */
export type RecipeStepActionOnProcess = {
  processId: number
  stepId?: string
}

/**
 * A reference to an earlier STEP's Expected Output in the same process, by that step's node id —
 * never by the output text, so editing the source step's Expected Output (e.g. "boiled eggs" ->
 * "soft-boiled eggs") is reflected everywhere it's used. Which steps are valid sources is decided
 * by the process graph (see recipeStepOutputs.ts).
 */
export type RecipeStepActionOnStep = {
  stepId: string
}

export type RecipeStepActionOn = {
  ingredients: ActionOnIngredient[]
  processes: RecipeStepActionOnProcess[]
  steps: RecipeStepActionOnStep[]
}

export type RecipeStepFields = {
  action: StepActionId | ''
  customActionName: string
  actionOn: RecipeStepActionOn
  /** Natural-language description of what this step does — a core, always-shown Step property (not an Advanced Option). */
  actionDescription: string
  /** The expected result/state after this step completes — a core, always-shown Step property. */
  expectedOutput: string
  /** Optional short title; empty means the title is derived from the action and its targets (see getRecipeStepTitle). */
  title: string
  /** The step explained as a guide would say it, continuing from the step before — shown to readers and spoken by narration. */
  summary: string
  /** Advanced Options (optional, action-specific — see actionSchemaCatalog). */
  flameLevelId: FlameLevelId | ''
  customFlameLevel: string
  /** Numeric text; what it measures (oven/oil/liquid/…) comes from the action's temperatureContext. */
  temperatureValue: string
  temperatureUnit: TemperatureUnitOption | ''
  durationValue: string
  durationUnit: DurationUnitOption | ''
  /** How often the action repeats during the step (e.g. stir every 2 minutes). */
  repeatIntervalValue: string
  repeatIntervalUnit: DurationUnitOption | ''
}

export type RecipeStepVisualizationStatus = 'not_generated' | 'generated'

/**
 * A STEP's generated visualization image, one per step (never per-ingredient/per-operation — see
 * the V1 "one step -> one image" contract).
 */
export type RecipeStepVisualizationData = {
  assetId?: number
  imagePrompt?: string
  imageUrl?: string
  status: RecipeStepVisualizationStatus
}

export type RecipeStepNodeData = {
  title: string
  step: RecipeStepFields
  sectionId?: string | null
  stepNumber?: number
  visualization?: RecipeStepVisualizationData
}

const createDefaultRecipeStepFields = (): RecipeStepFields => ({
  action: '',
  customActionName: '',
  actionOn: { ingredients: [], processes: [], steps: [] },
  actionDescription: '',
  expectedOutput: '',
  title: '',
  summary: '',
  flameLevelId: '',
  customFlameLevel: '',
  temperatureValue: '',
  temperatureUnit: '',
  durationValue: '',
  durationUnit: '',
  repeatIntervalValue: '',
  repeatIntervalUnit: '',
})

const asRecord = (value: unknown): Record<string, unknown> => (value && typeof value === 'object' ? (value as Record<string, unknown>) : {})

const asString = (value: unknown): string => (typeof value === 'string' ? value : '')

const asNumber = (value: unknown): number | null => {
  if (typeof value === 'number' && Number.isFinite(value)) return value
  if (typeof value === 'string' && value.trim()) {
    const parsed = Number(value)
    if (Number.isFinite(parsed)) return parsed
  }
  return null
}

/**
 * A stored ingredient reference as a catalog id: the database id (string, or a number from older
 * writers), else a legacy static-catalog slug ("onion" — e.g. from a local draft or recovery
 * snapshot saved before the database catalog) resolved through the entry's aliases. Null when it
 * names nothing the catalog knows. Before the catalog has loaded nothing can be checked, so a
 * non-empty reference is kept as-is rather than dropped (and later saved without it).
 */
const resolveStoredIngredientId = (value: unknown): IngredientId | null => {
  const id = typeof value === 'number' && Number.isFinite(value) ? String(value) : value
  if (typeof id !== 'string' || !id.trim()) return null
  if (!isIngredientCatalogLoaded()) return id
  if (isIngredientId(id)) return id
  return resolveIngredientId(id) || null
}

// Only an unknown ingredient drops the entry — a missing quantity or an unrecognized unit is kept
// (as null / '') rather than silently discarding the ingredient, as the pre-catalog normalizer did.
const normalizeActionOnIngredient = (value: unknown): ActionOnIngredient | null => {
  const raw = asRecord(value)
  const ingredientId = resolveStoredIngredientId(raw.ingredientId)
  if (ingredientId == null) return null

  // resolveUnitId also maps legacy Process UnitType values (COUNT/GRAM/KG/ML/LITER) via unit aliases.
  const unit = resolveUnitId(raw.unit)
  return {
    ingredientId,
    customIngredientName: ingredientId === CUSTOM_INGREDIENT_ID ? asString(raw.customIngredientName) : undefined,
    quantity: asNumber(raw.quantity),
    unit,
    customUnit: unit === CUSTOM_UNIT_ID ? asString(raw.customUnit) : undefined,
    notes: typeof raw.notes === 'string' && raw.notes.trim() ? raw.notes : undefined,
    preparationStyleId: resolvePreparationStyleId(raw.preparationStyleId),
    customPreparationStyle: asString(raw.customPreparationStyle),
  }
}

export const getActionOnIngredientDisplayName = (entry: Pick<ActionOnIngredient, 'ingredientId' | 'customIngredientName'>) =>
  getIngredientDisplayName(entry.ingredientId, entry.customIngredientName ?? '')

const normalizeActionOnProcess = (value: unknown): RecipeStepActionOnProcess | null => {
  const raw = asRecord(value)
  const processId = asNumber(raw.processId)
  if (processId == null) return null
  const stepId = asString(raw.stepId).trim()
  return stepId ? { processId, stepId } : { processId }
}

const normalizeActionOn = (value: unknown): RecipeStepActionOn => {
  const raw = asRecord(value)
  const ingredients = Array.isArray(raw.ingredients)
    ? raw.ingredients.map(normalizeActionOnIngredient).filter((entry): entry is ActionOnIngredient => entry != null)
    : []
  const processes = Array.isArray(raw.processes)
    ? raw.processes.map(normalizeActionOnProcess).filter((entry): entry is RecipeStepActionOnProcess => entry != null)
    : []
  // Absent on steps saved before step-output references existed — reads as an empty list.
  const stepIds = Array.isArray(raw.steps)
    ? raw.steps.map((entry) => asString(asRecord(entry).stepId).trim()).filter(Boolean)
    : []
  const steps = [...new Set(stepIds)].map((stepId) => ({ stepId }))
  return { ingredients, processes, steps }
}

/** temperatureValue/temperatureUnit, else a legacy free-text `temperature` ("180 C") parsed into them. */
const normalizeTemperature = (raw: Record<string, unknown>): Pick<RecipeStepFields, 'temperatureValue' | 'temperatureUnit'> => {
  const value = asString(raw.temperatureValue) || (typeof raw.temperatureValue === 'number' ? String(raw.temperatureValue) : '')
  if (value) return { temperatureValue: value, temperatureUnit: resolveTemperatureUnit(raw.temperatureUnit) }
  return parseTemperatureLabel(asString(raw.temperature))
}

export const normalizeRecipeStepFields = (value: unknown): RecipeStepFields => {
  const raw = asRecord(value)
  return {
    action: resolveStepActionId(raw.action),
    customActionName: asString(raw.customActionName),
    actionOn: normalizeActionOn(raw.actionOn),
    actionDescription: asString(raw.actionDescription),
    expectedOutput: asString(raw.expectedOutput),
    title: asString(raw.title),
    summary: asString(raw.summary),
    flameLevelId: resolveFlameLevelId(raw.flameLevelId),
    customFlameLevel: asString(raw.customFlameLevel),
    ...normalizeTemperature(raw),
    durationValue: asString(raw.durationValue),
    durationUnit: isDurationUnitOption(raw.durationUnit) ? raw.durationUnit : '',
    repeatIntervalValue: asString(raw.repeatIntervalValue),
    repeatIntervalUnit: isDurationUnitOption(raw.repeatIntervalUnit) ? raw.repeatIntervalUnit : '',
  }
}

/** The step fields each step-level schema key controls — cleared when a newly chosen action doesn't declare that key. */
const STEP_FIELDS_BY_SCHEMA_KEY: Partial<Record<StepSchemaFieldKey, readonly (keyof RecipeStepFields)[]>> = {
  temperature: ['temperatureValue', 'temperatureUnit'],
  flameLevelId: ['flameLevelId', 'customFlameLevel'],
  duration: ['durationValue', 'durationUnit'],
  repeatInterval: ['repeatIntervalValue', 'repeatIntervalUnit'],
}

const clearFieldsNotDeclaredByAction = (step: RecipeStepFields): RecipeStepFields => {
  if (!step.action || step.action === CUSTOM_ACTION_ID) return step
  const cleared: RecipeStepFields = { ...step }
  for (const key of STEP_LEVEL_FIELD_KEYS) {
    if (isStepFieldEnabled(step.action, key)) continue
    for (const field of STEP_FIELDS_BY_SCHEMA_KEY[key] ?? []) Object.assign(cleared, { [field]: '' })
  }
  return cleared
}

/**
 * Reads a STEP's visualization state — nested under `visualization` when set by
 * `withRecipeStepVisualization`, or (the backend's own write shape, see
 * `RecipeProcessVisualizationService.attachResultsAndSave`) flat sibling fields
 * `visualizationAssetId`/`imagePrompt`/`imageUrl` directly on the node's data.
 */
const normalizeRecipeStepVisualization = (raw: Record<string, unknown>): RecipeStepVisualizationData | undefined => {
  const nested = asRecord(raw.visualization)
  const assetId = asNumber(nested.assetId) ?? asNumber(raw.visualizationAssetId)
  const imageUrl = asString(nested.imageUrl) || asString(raw.imageUrl)
  const imagePrompt = asString(nested.imagePrompt) || asString(raw.imagePrompt)
  if (assetId == null && !imageUrl && !imagePrompt) return undefined

  return {
    assetId: assetId ?? undefined,
    imagePrompt: imagePrompt || undefined,
    imageUrl: imageUrl || undefined,
    status: imageUrl ? 'generated' : 'not_generated',
  }
}

export const normalizeRecipeStepNodeData = (value: unknown): RecipeStepNodeData => {
  const raw = asRecord(value)
  const step = normalizeRecipeStepFields(raw.step)
  return {
    title: getRecipeStepTitle(step),
    step,
    sectionId: typeof raw.sectionId === 'string' || raw.sectionId === null ? (raw.sectionId as string | null) : null,
    stepNumber: typeof raw.stepNumber === 'number' ? raw.stepNumber : undefined,
    visualization: normalizeRecipeStepVisualization(raw),
  }
}

export const createDefaultRecipeStepNodeData = (): RecipeStepNodeData => ({
  title: getRecipeStepTitle(createDefaultRecipeStepFields()),
  step: createDefaultRecipeStepFields(),
  sectionId: null,
})

/** The action's display name ("Mix / Combine", a custom action's name, or "Select Action" when none is chosen). */
export const getRecipeStepActionName = (step: Pick<RecipeStepFields, 'action' | 'customActionName'>) =>
  getActionDisplayName(step.action, step.customActionName)

/** Names that live outside the step: prepared outputs (other processes) and earlier steps' results. */
export type RecipeStepNameResolver = {
  preparedName: (reference: RecipeStepActionOnProcess) => string
  stepOutputLabel: (stepId: string) => string
}

/** Catalog ingredient names read in lower case inside a heading or sentence; custom names are kept as typed. */
const ingredientSubject = (entry: ActionOnIngredient) =>
  (entry.ingredientId === CUSTOM_INGREDIENT_ID ? getActionOnIngredientDisplayName(entry) : getActionOnIngredientDisplayName(entry).toLowerCase())

/**
 * Live names for a description's @ annotations — prepared outputs and earlier results, which can be renamed, when
 * `resolve` is given. An ingredient reads as the words it was written with ("onions"): its id is stable, and the
 * instruction's own wording reads better in a sentence than the catalog's name. Anything unresolved falls back to
 * the annotation's label.
 */
export const stepAnnotationLabelResolver = (resolve?: Partial<RecipeStepNameResolver>): AnnotationLabelResolver => (annotation) => {
  if (annotation.kind === 'ingredient') return null
  if (annotation.kind === 'prepared') {
    const reference = parsePreparedAnnotationId(annotation.id)
    return reference && resolve?.preparedName ? resolve.preparedName(reference) : null
  }
  return resolve?.stepOutputLabel ? resolve.stepOutputLabel(annotation.id) : null
}

/**
 * The step's derived title — "Chop onions", "Heat Marinated Chicken" (see stepTitle.ts). Without `resolve`,
 * prepared outputs and earlier results are named only by their annotations' snapshot labels.
 */
export function getRecipeStepTitle(step: RecipeStepFields, resolve?: Partial<RecipeStepNameResolver>): string {
  if (step.title.trim()) return step.title.trim()
  const subjects = [
    ...(resolve?.stepOutputLabel ? step.actionOn.steps.map((entry) => resolve.stepOutputLabel!(entry.stepId)) : []),
    ...step.actionOn.ingredients.map(ingredientSubject),
    ...(resolve?.preparedName ? step.actionOn.processes.map((entry) => resolve.preparedName!(entry)) : []),
  ]
  return deriveStepTitle({
    actionName: step.action ? getRecipeStepActionName(step) : '',
    description: step.actionDescription,
    subjects,
    resolveLabel: stepAnnotationLabelResolver(resolve),
  })
}

/** Effective duration label ("5 minutes"), derived rather than stored redundantly. */
export const getRecipeStepDurationLabel = (step: RecipeStepFields) =>
  buildDurationLabel(step.durationValue, step.durationUnit)

/** "every 2 minutes", or '' when unset. */
export const getRecipeStepRepeatIntervalLabel = (step: RecipeStepFields) => {
  const label = buildDurationLabel(step.repeatIntervalValue, step.repeatIntervalUnit)
  return label ? `every ${label}` : ''
}

/** "180 °C", or '' when unset. */
export const getRecipeStepTemperatureLabel = (step: RecipeStepFields) =>
  buildTemperatureLabel(step.temperatureValue, step.temperatureUnit)

/**
 * Applies a single unprefixed step field update (e.g. `action`, `temperature`) to a
 * RecipeStepNodeData value (the STEP counterpart of adapters/canvasFlow.ts's
 * `applyConditionFieldUpdate`) — `actionOn` itself is updated separately (see
 * `withRecipeStepActionOnIngredients`/`withRecipeStepActionOnProcesses` below), not through this
 * generic field setter, since it's a structured list rather than a scalar.
 */
export const applyRecipeStepFieldUpdate = (data: unknown, field: string, value: string): RecipeStepNodeData => {
  const normalized = normalizeRecipeStepNodeData(data)
  const mergedStep: RecipeStepFields = { ...normalized.step, [field]: value }

  if (field === 'action' && value !== 'custom') {
    mergedStep.customActionName = ''
  }
  if (field === 'flameLevelId' && value !== 'custom') {
    mergedStep.customFlameLevel = ''
  }

  const normalizedStep = normalizeRecipeStepFields(mergedStep)
  // Switching action drops advanced values the new action doesn't declare (e.g. a temperature
  // left over from Bake after changing to Chop), so hidden fields never linger in saved data.
  const reNormalized = field === 'action' ? clearFieldsNotDeclaredByAction(normalizedStep) : normalizedStep
  return { ...normalized, step: reNormalized, title: getRecipeStepTitle(reNormalized) }
}

/** The node data with a new step — and the title that follows from it (the stored title is always derived). */
const withStep = (normalized: RecipeStepNodeData, step: RecipeStepFields): RecipeStepNodeData =>
  ({ ...normalized, step, title: getRecipeStepTitle(step) })

export const withRecipeStepActionOnIngredients = (data: unknown, ingredients: ActionOnIngredient[]): RecipeStepNodeData => {
  const normalized = normalizeRecipeStepNodeData(data)
  return withStep(normalized, { ...normalized.step, actionOn: { ...normalized.step.actionOn, ingredients } })
}

export const withRecipeStepActionOnProcesses = (data: unknown, references: RecipeStepActionOnProcess[]): RecipeStepNodeData => {
  const normalized = normalizeRecipeStepNodeData(data)
  return withStep(normalized, {
    ...normalized.step,
    actionOn: {
      ...normalized.step.actionOn,
      processes: references.map(({ processId, stepId }) => (stepId ? { processId, stepId } : { processId })),
    },
  })
}

export const withRecipeStepActionOnSteps = (data: unknown, stepIds: string[]): RecipeStepNodeData => {
  const normalized = normalizeRecipeStepNodeData(data)
  return withStep(normalized, { ...normalized.step, actionOn: { ...normalized.step.actionOn, steps: stepIds.map((stepId) => ({ stepId })) } })
}

/**
 * Human-readable one-liner of a step, built from the same action display name and Action On
 * target names the rest of the UI shows — e.g. "Fry marinated chicken + fried onions". Subprocess
 * and step-output names come from the caller (they live in other documents / other nodes).
 */
export const getRecipeStepActionSummary = (step: RecipeStepFields, resolve: RecipeStepNameResolver) => {
  const targets = [
    ...step.actionOn.steps.map((entry) => resolve.stepOutputLabel(entry.stepId)),
    ...step.actionOn.ingredients.map(getActionOnIngredientDisplayName),
    ...step.actionOn.processes.map((entry) => resolve.preparedName(entry)),
  ].filter(Boolean)
  const action = getRecipeStepActionName(step)
  return targets.length > 0 ? `${action} ${targets.join(' + ')}` : action
}

/** The annotation ids of a step's own targets — what its description's @ annotations may point at. */
export const getStepAnnotationTargets = (step: RecipeStepFields): StepAnnotationTargets => ({
  ingredient: step.actionOn.ingredients.map(ingredientAnnotationId).filter(Boolean),
  prepared: step.actionOn.processes.map(preparedAnnotationId),
  step: step.actionOn.steps.map((entry) => entry.stepId),
})

/** The index of the Action On ingredient an ingredient annotation points at (-1 when none). */
export const findAnnotatedIngredientIndex = (step: RecipeStepFields, annotationId: string) =>
  step.actionOn.ingredients.findIndex((entry) => ingredientAnnotationId(entry).toLowerCase() === annotationId.toLowerCase())

/**
 * How an ingredient annotation reads with its amount, taken from the step's Action On entry (amounts are never stored
 * in the text): "2 onions", "1 cup water", "salt to taste". Just the words when no amount is stated.
 */
export const annotatedIngredientText = (step: RecipeStepFields, annotationId: string, words: string) => {
  const entry = step.actionOn.ingredients[findAnnotatedIngredientIndex(step, annotationId)]
  const amount = entry ? formatQuantityWithUnit(entry.quantity, entry.unit, entry.customUnit) : ''
  if (!amount) return words
  return isQuantifiableUnit(entry.unit) ? `${amount} ${words}` : `${words} ${amount}`
}

/**
 * The step with an annotation's target in its Action On — added (without an amount, in the ingredient's default
 * unit) when it isn't there yet. The Action On entry stays the one place amounts live; an annotation only points at it.
 */
export const withAnnotationTarget = (data: unknown, annotation: Annotation): RecipeStepNodeData => {
  const normalized = normalizeRecipeStepNodeData(data)
  const { actionOn } = normalized.step
  if (annotation.kind === 'ingredient') {
    if (findAnnotatedIngredientIndex(normalized.step, annotation.id) >= 0) return normalized
    const custom = annotation.id.startsWith(CUSTOM_INGREDIENT_PREFIX)
    const ingredientId = custom ? CUSTOM_INGREDIENT_ID : resolveIngredientId(annotation.id)
    if (!ingredientId) return normalized
    const entry: ActionOnIngredient = custom
      ? { ingredientId: CUSTOM_INGREDIENT_ID, customIngredientName: annotation.id.slice(CUSTOM_INGREDIENT_PREFIX.length).trim() || annotation.label, quantity: null, unit: '' }
      : { ingredientId, quantity: null, unit: getIngredientDefaultUnit(ingredientId) }
    return withRecipeStepActionOnIngredients(normalized, [...actionOn.ingredients, entry])
  }
  if (annotation.kind === 'prepared') {
    const reference = parsePreparedAnnotationId(annotation.id)
    if (!reference || actionOn.processes.some((entry) => preparedAnnotationId(entry) === annotation.id)) return normalized
    return withRecipeStepActionOnProcesses(normalized, [...actionOn.processes, reference])
  }
  if (actionOn.steps.some((entry) => entry.stepId === annotation.id)) return normalized
  return withRecipeStepActionOnSteps(normalized, [...actionOn.steps.map((entry) => entry.stepId), annotation.id])
}

/**
 * After annotations were deleted from a step's description (e.g. a chip removed with Backspace), the targets only
 * they named leave the step's Action On too — so "Used in this step" follows the text. A target the description
 * never annotated (added from the panel) is left alone, and so is one still annotated elsewhere in the text.
 */
export const withoutTargetsOfRemovedAnnotations = (previous: RecipeStepFields, next: RecipeStepNodeData): RecipeStepNodeData => {
  const remaining = annotationsOf(next.step.actionDescription)
  const removed = annotationsOf(previous.actionDescription).filter((annotation) =>
    !remaining.some((other) => sameAnnotationTarget(other, annotation.kind, annotation.id)))
  if (removed.length === 0) return next
  const isRemoved = (kind: Annotation['kind'], id: string) => removed.some((annotation) => sameAnnotationTarget(annotation, kind, id))
  const { actionOn } = next.step
  return withStep(next, {
    ...next.step,
    actionOn: {
      ingredients: actionOn.ingredients.filter((entry) => !isRemoved('ingredient', ingredientAnnotationId(entry))),
      processes: actionOn.processes.filter((entry) => !isRemoved('prepared', preparedAnnotationId(entry))),
      steps: actionOn.steps.filter((entry) => !isRemoved('step', entry.stepId)),
    },
  })
}

/**
 * After targets were removed from a step's Action On (in the editor), its annotations of them read as plain words
 * again — the prose stays, only the link goes.
 */
export const withoutAnnotationsOfRemovedTargets = (previous: RecipeStepFields, next: RecipeStepNodeData): RecipeStepNodeData => {
  const before = getStepAnnotationTargets(previous)
  const after = getStepAnnotationTargets(next.step)
  const removed = (Object.keys(before) as Array<keyof StepAnnotationTargets>).flatMap((kind) =>
    before[kind].filter((id) => !after[kind].includes(id)).map((id) => ({ kind, id })))
  if (removed.length === 0) return next
  const actionDescription = unwrapAnnotations(next.step.actionDescription, (annotation) =>
    removed.some((target) => sameAnnotationTarget(annotation, target.kind, target.id)))
  return actionDescription === next.step.actionDescription ? next : withStep(next, { ...next.step, actionDescription })
}

/** Applies a freshly generated visualization result (from a visualization job's step result) onto a STEP's data. */
export const withRecipeStepVisualization = (data: unknown, visualization: RecipeStepVisualizationData): RecipeStepNodeData => {
  const normalized = normalizeRecipeStepNodeData(data)
  return { ...normalized, visualization }
}
