/**
 * Converts an AI-generated recipe process structure (recipe/dto/Generated*.java via
 * RecipeProcessGenerationApi) into the same `Process[]` shape RecipeSessionContext already works
 * with — generating every React Flow presentation detail (node/edge ids, positions, dimensions)
 * the AI was deliberately never asked for. The recipe gets two processes: the MAIN process, and one
 * Preparation (a SUBPROCESS) holding every generated preparation as its own flow, whose final step's
 * Expected Output is the preparation's named output. The `ref` strings MAIN steps use resolve to that
 * step — `{ processId, stepId }` with the client-temporary ids this conversion assigns (real process ids
 * only start existing once RecipeSessionContext.saveAll() creates them). A reference that resolves to
 * nothing is reported in `problems`, never silently dropped: the backend validator should make that
 * impossible, so a problem means the result must not be applied.
 */

import { CONDITION_HANDLES, DEFAULT_NODE_SIZE, RECIPE_NODE_TYPES } from '../../model/recipeNodeTypes'
import { normalizeConditionNodeData, type ConditionNodeData } from '../../model/recipeConditionData'
import { normalizeRecipeStepNodeData, type RecipeStepActionOnProcess, type RecipeStepNodeData } from '../../model/recipeStepData'
import { PREPARATION_NAME } from '../../model/mergePreparations'
import { resolveGeneratedAnnotations, toPlainText } from '../../model/stepAnnotations'
import { ROW_LAYOUT, arrangeFlowGrid, arrangeRows, planFlowColumns, planRows } from '../model/processLayout'
import { parseDurationLabel } from '../../../../shared/catalog/stepFieldCatalog'
import type { Process, ProcessEdge, ProcessNode } from '../../../../types/process'
import type { GeneratedRecipeStep, RecipeProcessGenerationResult } from '../../../../types/recipe'

const STEP_SIZE = DEFAULT_NODE_SIZE.step
const CONDITION_SIZE = DEFAULT_NODE_SIZE.condition

const ORIGIN = { x: 120, y: 80 }
/**
 * Canvas width the generated rows are planned for — a typical editor canvas (3 cards per row). The
 * canvas re-arranges a freshly generated process to the real screen width once it is shown (see
 * RecipeProcessCanvas's Auto Arrange); this only matters for a subprocess not opened yet.
 */
const DEFAULT_CANVAS_WIDTH = 1280

/**
 * Roughly how tall RecipeStepNode will render this step — a freshly generated node hasn't been
 * measured yet, and its stored height is only a minimum the card grows past with content. Only used
 * to space the generated layout; the node's own size is left alone.
 */
const estimateStepHeight = (data: RecipeStepNodeData): number => {
  const { step } = data
  const actionOnCount = step.actionOn.ingredients.length + step.actionOn.processes.length + step.actionOn.steps.length
  const hasBadges = Boolean(step.temperatureValue || step.flameLevelId || step.durationValue || step.repeatIntervalValue)
  const height = 24 /* padding */ + 32 /* title row */ + 8
    + 14 + Math.min(actionOnCount, 3) * 16 + (actionOnCount > 3 ? 16 : 0)
    + (hasBadges ? 26 : 0)
    + (step.actionDescription.trim() ? 58 : 0)
    + (step.expectedOutput.trim() ? 58 : 0)
  return Math.min(560, Math.max(STEP_SIZE.height, height))
}

/**
 * Picks fresh, mutually-unique negative ids for the processes this generation is about to create
 * (a real Process id from the backend is always a positive sequence number, so negative ids can
 * never collide with one) — starting below the lowest id already present anywhere in the current
 * session, so two generation runs in the same visit (before either is saved) don't collide either.
 */
const allocateTempIds = (count: number, existingIds: number[]): number[] => {
  const lowest = existingIds.length > 0 ? Math.min(0, ...existingIds) : 0
  const start = lowest - 1
  return Array.from({ length: count }, (_, index) => start - index)
}

/** Collects a problem for every reference that resolves to nothing, keeping the ones that do resolve. */
const resolveRefs = <T>(refs: string[], lookup: Map<string, T>, describe: (ref: string) => string, problems: string[]): T[] =>
  refs.flatMap((ref) => {
    const resolved = lookup.get(ref)
    if (resolved == null) {
      problems.push(describe(ref))
      return []
    }
    return [resolved]
  })

export const buildStepNode = (
  step: GeneratedRecipeStep,
  nodeId: string,
  /** What each `uses` ref stands for: a Preparation step's output (or, from an AI edit, a process). */
  preparedByRef: Map<string, RecipeStepActionOnProcess>,
  stepIdToNodeId: Map<string, string>,
  x: number,
  y: number,
  problems: string[] = [],
  label = 'A step',
): ProcessNode => {
  const repeatInterval = parseDurationLabel(step.repeatInterval ?? '')
  const data: RecipeStepNodeData = normalizeRecipeStepNodeData({
    step: {
      action: step.action ?? '',
      customActionName: step.customActionName ?? '',
      actionOn: {
        ingredients: (step.actionOn?.ingredients ?? []).map((ingredient) => ({
          ingredientId: ingredient.ingredientId,
          customIngredientName: ingredient.customIngredientName ?? '',
          quantity: ingredient.quantity,
          unit: ingredient.unit ?? '',
          customUnit: ingredient.customUnit ?? '',
          notes: ingredient.notes ?? undefined,
          preparationStyleId: ingredient.preparationStyle ?? '',
          customPreparationStyle: ingredient.customPreparationStyle ?? '',
        })),
        processes: resolveRefs(step.actionOn?.processes ?? [], preparedByRef,
          (ref) => `${label} uses an unknown preparation "${ref}"`, problems),
        // Generated `stepId` slugs resolve to this process's freshly assigned node ids; the backend
        // validator already guaranteed each points at an earlier STEP with an Expected Output.
        steps: resolveRefs(step.actionOn?.steps ?? [], stepIdToNodeId,
          (ref) => `${label} uses the output of an unknown step "${ref}"`, problems)
          .map((stepId) => ({ stepId })),
      },
      actionDescription: resolveGeneratedAnnotations(step.actionDescription ?? '', preparedByRef, stepIdToNodeId),
      expectedOutput: step.expectedOutput ?? '',
      title: step.title ?? '',
      summary: step.summary ?? '',
      flameLevelId: step.flameLevel ?? '',
      customFlameLevel: '',
      // normalizeRecipeStepFields falls back to parsing the legacy free-text `temperature` when no value is given.
      temperatureValue: step.temperatureValue ?? '',
      temperatureUnit: step.temperatureUnit ?? '',
      temperature: step.temperature ?? '',
      ...parseDurationLabel(step.duration ?? ''),
      repeatIntervalValue: repeatInterval.durationValue,
      repeatIntervalUnit: repeatInterval.durationUnit,
    },
  })

  return {
    id: nodeId,
    kind: 'STEP',
    type: RECIPE_NODE_TYPES.step,
    data: data as unknown as Record<string, unknown>,
    position: { x, y },
    width: STEP_SIZE.width,
    height: STEP_SIZE.height,
    measured: STEP_SIZE,
    draggable: true,
    selectable: true,
    deletable: true,
  }
}

export const buildConditionNode = (step: GeneratedRecipeStep, nodeId: string, x: number, y: number): ProcessNode => {
  // A check's text is read, not linked: annotations become plain words.
  const notes = [toPlainText(step.actionDescription), step.expectedOutput ? `Expected: ${step.expectedOutput}` : '']
    .filter(Boolean)
    .join(' ')
  const expectedResult = step.expectedResult === 'failure' ? 'failure' : 'success'

  const data: ConditionNodeData = normalizeConditionNodeData({
    title: step.title ?? '',
    condition: {
      question: step.title ?? '',
      expectedResult,
      successLabel: 'Yes',
      failureLabel: 'No',
      notes,
    },
    yesLabel: 'Yes',
    noLabel: 'No',
    description: notes,
  })

  return {
    id: nodeId,
    kind: 'CONDITION',
    type: RECIPE_NODE_TYPES.condition,
    data: data as unknown as Record<string, unknown>,
    position: { x, y },
    width: CONDITION_SIZE.width,
    height: CONDITION_SIZE.height,
    measured: CONDITION_SIZE,
    draggable: true,
    selectable: true,
    deletable: true,
  }
}

type UnplacedFlow = { nodes: ProcessNode[]; edges: ProcessEdge[] }

/**
 * Builds one flow's nodes and edges from its ordered step list. Steps are already in execution
 * order (the AI was asked for an ordered list, not a graph — see RecipeProcessGenerationPromptBuilder),
 * so consecutive steps are connected linearly. A CONDITION's YES branch goes to the next node and
 * its NO branch back to the step before it (a "repeat until" loop) — the AI doesn't generate branch
 * targets itself (asking for real graph topology is a substantially harder and more error-prone
 * generation task than an ordered step list), so the prompt fixes that convention instead.
 * Positions come later, from the layout of the whole process.
 */
const buildFlow = (
  steps: GeneratedRecipeStep[],
  preparedByRef: Map<string, RecipeStepActionOnProcess>,
  flowLabel: string,
  problems: string[],
  nodeIds: string[] = steps.map(() => crypto.randomUUID()),
): UnplacedFlow => {
  // Node ids are assigned up front so a step can reference an earlier step's output by id.
  const stepIdToNodeId = new Map<string, string>()
  steps.forEach((step, index) => {
    if (step.stepId) stepIdToNodeId.set(step.stepId, nodeIds[index])
  })

  const nodes = steps.map((step, index) => (step.nodeType === 'CONDITION'
    ? buildConditionNode(step, nodeIds[index], 0, 0)
    : buildStepNode(step, nodeIds[index], preparedByRef, stepIdToNodeId, 0, 0, problems, `${flowLabel}, step ${index + 1}`)))

  const edges: ProcessEdge[] = []
  nodes.forEach((source, i) => {
    const next = nodes[i + 1]
    if (source.kind !== 'CONDITION') {
      if (next) edges.push({ id: crypto.randomUUID(), source: source.id, target: next.id })
      return
    }
    // YES continues to the next node; NO loops back to the step being checked (the nearest STEP
    // before the condition) so it's repeated until the check passes. The prompt asks for every
    // condition to be phrased that way and placed right after the step it checks.
    const checkedStep = nodes.slice(0, i).reverse().find((node) => node.kind === 'STEP')
    if (next) edges.push({ id: crypto.randomUUID(), source: source.id, target: next.id, sourceHandle: CONDITION_HANDLES.yes, label: 'Yes' })
    const noTarget = checkedStep ?? next
    if (noTarget) edges.push({ id: crypto.randomUUID(), source: source.id, target: noTarget.id, sourceHandle: CONDITION_HANDLES.no, label: 'No' })
  })
  return { nodes, edges }
}

const layoutEntry = (node: ProcessNode) => ({
  id: node.id,
  kind: node.kind,
  height: node.kind === 'STEP' ? estimateStepHeight(node.data as unknown as RecipeStepNodeData) : node.height ?? CONDITION_SIZE.height,
})

/** Applies computed positions and sizes. */
const place = (nodes: ProcessNode[], layout: Map<string, { x: number; y: number; width: number; height: number }>) =>
  nodes.map((node) => {
    const placed = layout.get(node.id)
    if (!placed) return node
    const { x, y, width, height } = placed
    return { ...node, position: { x, y }, width, height, measured: { width, height } }
  })

/** Index of a flow's final STEP (-1 when it has none). */
const lastStepIndex = (steps: GeneratedRecipeStep[]) => {
  let last = -1
  steps.forEach((step, index) => {
    if (step.nodeType !== 'CONDITION') last = index
  })
  return last
}

/** Gives a flow's final step the preparation's output name as its Expected Output — the flow's named output. */
const withOutputName = (steps: GeneratedRecipeStep[], output: string): GeneratedRecipeStep[] => {
  const name = output.trim()
  const last = lastStepIndex(steps)
  if (!name || last < 0 || steps[last].expectedOutput?.trim() === name) return steps
  return steps.map((step, index) => (index === last ? { ...step, expectedOutput: name } : step))
}

export type ConvertedGeneration = {
  /** The generated MAIN process and (when there are preparations) the one Preparation, ready to load into RecipeSessionContext. */
  processes: Process[]
  /** The id (temporary, or the existing MAIN's real id when replacing it) to select/display after loading. */
  mainProcessId: number
  /** References that resolved to nothing. Non-empty means the result is inconsistent and must not be applied. */
  problems: string[]
}

/**
 * @param existingMain pass the recipe's current MAIN process to replace its content in place
 *   (keeping its real id) instead of creating a second MAIN — the caller decides whether that's
 *   appropriate (with the user's confirmation) before calling this.
 * @param existingSessionIds every process id already in the current session, so temporary ids
 *   never collide with an unsaved process from an earlier generation run.
 * @param existingPreparation the recipe's current Preparation, likewise replaced in place when the
 *   result has preparations (and left alone when it has none).
 */
export const convertGeneratedResultToProcesses = (
  recipeId: number,
  result: RecipeProcessGenerationResult,
  existingMain: Process | null,
  existingSessionIds: number[],
  existingPreparation: Process | null = null,
): ConvertedGeneration => {
  const preparations = result.subprocesses ?? []
  const tempIds = allocateTempIds(2, existingSessionIds)
  const mainProcessId = existingMain ? existingMain.id : tempIds[0]
  const preparationId = existingPreparation ? existingPreparation.id : tempIds[1]
  const problems: string[] = []

  // Each preparation becomes one flow of the Preparation; its ref stands for its final step's output.
  const preparedByRef = new Map<string, RecipeStepActionOnProcess>()
  const flows = preparations.map((preparation) => {
    const steps = withOutputName(preparation.steps ?? [], preparation.name ?? '')
    const nodeIds = steps.map(() => crypto.randomUUID())
    const output = lastStepIndex(steps)
    if (preparation.ref && output >= 0) preparedByRef.set(preparation.ref, { processId: preparationId, stepId: nodeIds[output] })
    return { steps, nodeIds, name: preparation.name || 'Preparation' }
  })
  // Preparation steps never use another preparation (flows are independent): there's nothing for them to resolve.
  const builtFlows = flows.map(({ steps, nodeIds, name }) => buildFlow(steps, new Map(), `Preparation "${name}"`, problems, nodeIds))

  const main = buildFlow(result.mainProcess.steps ?? [], preparedByRef, 'Main process', problems)
  const mainLayout = arrangeRows(main.nodes.map(layoutEntry), planRows(DEFAULT_CANVAS_WIDTH - 2 * ROW_LAYOUT.padding), ORIGIN)
  const processes: Process[] = [{
    id: mainProcessId,
    type: 'MAIN',
    recipeId,
    name: result.mainProcess.name || 'Main Process',
    nodes: place(main.nodes, mainLayout),
    edges: main.edges,
  }]

  if (builtFlows.length > 0) {
    const gridLayout = arrangeFlowGrid(
      builtFlows.map((flow) => flow.nodes.map(layoutEntry)),
      planFlowColumns(DEFAULT_CANVAS_WIDTH - 2 * ROW_LAYOUT.padding),
      ORIGIN,
    )
    processes.push({
      ...(existingPreparation ?? {}),
      id: preparationId,
      type: 'SUBPROCESS',
      recipeId,
      name: PREPARATION_NAME,
      nodes: place(builtFlows.flatMap((flow) => flow.nodes), gridLayout),
      edges: builtFlows.flatMap((flow) => flow.edges),
    })
  }

  return { processes, mainProcessId, problems }
}
