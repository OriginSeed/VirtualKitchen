/**
 * Applies a validated AI edit (RecipeProcessEditResult.operations, see recipe/dto/ProcessEditOperationDTO.java)
 * to one process of the working session. Only the nodes an operation names are touched: every other
 * node keeps its id, position, size, image and data exactly as it was, and edges are rewired only
 * around inserted/removed nodes (recipeProcessEditGraph.ts). New nodes are built with the same
 * builders AI generation uses (recipeProcessGenerationConverter.ts).
 *
 * All-or-nothing: if the process changed while the AI was working so that an operation's node is
 * gone, nothing is applied and a ProcessEditConflictError is thrown instead.
 */

import type { Process, ProcessNode } from '../../../../types/process'
import type { GeneratedActionOnIngredient, GeneratedRecipeStep, ProcessEditOperation } from '../../../../types/recipe'
import { getActionDisplayName } from '../../../../shared/catalog/actionCatalog'
import { getFlameLevelDisplayName } from '../../../../shared/catalog/flameLevelCatalog'
import { getIngredientDisplayName } from '../../../../shared/catalog/ingredientCatalog'
import { getPreparationStyleDisplayName } from '../../../../shared/catalog/preparationStyleCatalog'
import { parseDurationLabel } from '../../../../shared/catalog/stepFieldCatalog'
import { normalizeConditionNodeData } from '../../model/recipeConditionData'
import {
  getRecipeStepTitle,
  normalizeRecipeStepFields,
  normalizeRecipeStepNodeData,
  type ActionOnIngredient,
  type RecipeStepFields,
} from '../../model/recipeStepData'
import { getProcessReadingOrder } from '../../model/processReadingOrder'
import { decodePreparedReference } from '../../model/preparationFlows'
import { remapAnnotationIds } from '../../model/stepAnnotations'
import { buildConditionNode, buildStepNode } from './recipeProcessGenerationConverter'
import { EDIT_NEW_FLOW, EDIT_START, detachNode, findFreePosition, insertNodeAfter, preferredInsertPosition, type ProcessGraph } from './recipeProcessEditGraph'
import { DEFAULT_NODE_SIZE } from '../../model/recipeNodeTypes'

const STEP_SIZE = DEFAULT_NODE_SIZE.step

export class ProcessEditConflictError extends Error {
  constructor() {
    super('The flow changed while the AI was working on it, so the change was not applied. Please ask again.')
    this.name = 'ProcessEditConflictError'
  }
}

export type AppliedProcessEdit = {
  process: Process
  /** Nodes added, changed or moved — for a short highlight on the canvas. */
  changedNodeIds: string[]
}

const newId = () => crypto.randomUUID()

const toActionOnIngredient = (ingredient: GeneratedActionOnIngredient): ActionOnIngredient => ({
  ingredientId: ingredient.ingredientId,
  customIngredientName: ingredient.customIngredientName ?? '',
  quantity: ingredient.quantity ?? null,
  unit: ingredient.unit ?? '',
  customUnit: ingredient.customUnit ?? '',
  notes: ingredient.notes ?? undefined,
  preparationStyleId: ingredient.preparationStyle ?? '',
  customPreparationStyle: ingredient.customPreparationStyle ?? '',
})

/** Overwrites every field the patch carries; actionOn.steps/processes replace the whole list. */
const patchStepFields = (
  fields: RecipeStepFields, patch: Partial<GeneratedRecipeStep>, clear: string[], resolveNode: (ref: string) => string,
): RecipeStepFields => {
  const raw: Record<string, unknown> = { ...fields, actionOn: { ...fields.actionOn } }
  const actionOn = raw.actionOn as Record<string, unknown>
  if (patch.action != null) raw.action = patch.action
  if (patch.customActionName != null) raw.customActionName = patch.customActionName
  // Its annotations of steps added earlier in this edit ("new-1") point at the nodes made for them.
  if (patch.actionDescription != null) {
    raw.actionDescription = remapAnnotationIds(patch.actionDescription, (annotation) =>
      (annotation.kind === 'step' ? resolveNode(annotation.id) : undefined))
  }
  if (patch.expectedOutput != null) raw.expectedOutput = patch.expectedOutput
  if (patch.title != null) raw.title = patch.title
  if (patch.summary != null) raw.summary = patch.summary
  if (patch.flameLevel != null) Object.assign(raw, { flameLevelId: patch.flameLevel, customFlameLevel: '' })
  if (patch.temperatureValue != null) raw.temperatureValue = String(patch.temperatureValue)
  if (patch.temperatureUnit != null) raw.temperatureUnit = patch.temperatureUnit
  if (patch.duration != null) Object.assign(raw, parseDurationLabel(patch.duration))
  if (patch.repeatInterval != null) {
    const parsed = parseDurationLabel(patch.repeatInterval)
    Object.assign(raw, { repeatIntervalValue: parsed.durationValue, repeatIntervalUnit: parsed.durationUnit })
  }
  if (patch.actionOn?.steps != null) actionOn.steps = patch.actionOn.steps.map((ref) => ({ stepId: resolveNode(ref) }))
  if (patch.actionOn?.processes != null) {
    actionOn.processes = patch.actionOn.processes.flatMap((ref) => decodePreparedReference(ref) ?? [])
  }

  for (const field of clear) {
    switch (field) {
      case 'customActionName': raw.customActionName = ''; break
      case 'expectedOutput': raw.expectedOutput = ''; break
      case 'temperature': Object.assign(raw, { temperatureValue: '', temperatureUnit: '' }); break
      case 'flameLevel': Object.assign(raw, { flameLevelId: '', customFlameLevel: '' }); break
      case 'duration': Object.assign(raw, { durationValue: '', durationUnit: '' }); break
      case 'repeatInterval': Object.assign(raw, { repeatIntervalValue: '', repeatIntervalUnit: '' }); break
      case 'fromSteps': actionOn.steps = []; break
      case 'processes': actionOn.processes = []; break
      default: break
    }
  }
  return normalizeRecipeStepFields(raw)
}

/** A STEP node with new fields — every other key of its data (image, section, ...) is kept. */
const withStepFields = (node: ProcessNode, fields: RecipeStepFields): ProcessNode => ({
  ...node,
  data: { ...node.data, step: fields, title: getRecipeStepTitle(fields) },
})

export const applyProcessEdit = (process: Process, operations: ProcessEditOperation[]): AppliedProcessEdit => {
  let graph: ProcessGraph = { nodes: process.nodes, edges: process.edges }
  const nodeIdByRef = new Map<string, string>()
  const changed = new Set<string>()

  const resolve = (ref: string | null | undefined): string => {
    if (!ref) throw new ProcessEditConflictError()
    if (ref === EDIT_START || ref === EDIT_NEW_FLOW) return ref
    const id = nodeIdByRef.get(ref) ?? ref
    if (!graph.nodes.some((node) => node.id === id)) throw new ProcessEditConflictError()
    return id
  }
  const nodeOf = (id: string) => graph.nodes.find((node) => node.id === id) as ProcessNode
  const replaceNode = (next: ProcessNode) => {
    graph = { ...graph, nodes: graph.nodes.map((node) => (node.id === next.id ? next : node)) }
    changed.add(next.id)
  }
  const stepFieldsOf = (id: string) => {
    const node = nodeOf(id)
    if (node.kind !== 'STEP') throw new ProcessEditConflictError()
    return normalizeRecipeStepNodeData(node.data).step
  }
  const firstNodeId = () => getProcessReadingOrder({ ...process, nodes: graph.nodes, edges: graph.edges })[0]?.id ?? null

  /** Inserts `node` after `anchor`, placed in free space between the anchor and what followed it. */
  const insert = (node: ProcessNode, anchor: string) => {
    const first = firstNodeId()
    const size = { width: node.width ?? STEP_SIZE.width, height: node.height ?? STEP_SIZE.height }
    const others = graph.nodes.filter((candidate) => candidate.id !== node.id)
    const position = findFreePosition(others, preferredInsertPosition(graph, anchor, first), size)
    graph = insertNodeAfter(graph, { ...node, position }, anchor, first, newId)
    changed.add(node.id)
  }

  for (const operation of operations) {
    switch (operation.op) {
      case 'ADD_STEP':
      case 'ADD_CONDITION': {
        const anchor = resolve(operation.after)
        const step = operation.step as GeneratedRecipeStep
        const id = newId()
        if (operation.ref) nodeIdByRef.set(operation.ref, id)
        const node = operation.op === 'ADD_CONDITION'
          ? buildConditionNode({ ...step, nodeType: 'CONDITION' }, id, 0, 0)
          : buildStepNode(
            { ...step, nodeType: 'STEP' },
            id,
            new Map((step.actionOn?.processes ?? []).flatMap((ref) => {
              const reference = decodePreparedReference(ref)
              return reference ? [[ref, reference] as const] : []
            })),
            new Map((step.actionOn?.steps ?? []).map((ref) => [ref, resolve(ref)])),
            0,
            0,
          )
        insert(node, anchor)
        break
      }
      case 'UPDATE_STEP': {
        const id = resolve(operation.target)
        replaceNode(withStepFields(nodeOf(id), patchStepFields(stepFieldsOf(id), operation.step ?? {}, operation.clear ?? [], resolve)))
        break
      }
      case 'UPDATE_CONDITION': {
        const id = resolve(operation.target)
        const node = nodeOf(id)
        if (node.kind !== 'CONDITION') throw new ProcessEditConflictError()
        const current = normalizeConditionNodeData(node.data)
        const patch = operation.step ?? {}
        const condition = {
          ...current.condition,
          ...(patch.title != null ? { question: patch.title } : {}),
          ...(patch.actionDescription != null ? { notes: patch.actionDescription } : {}),
          ...(patch.expectedResult != null ? { expectedResult: patch.expectedResult } : {}),
        }
        const normalized = normalizeConditionNodeData({ ...current, condition, title: condition.question, description: condition.notes })
        replaceNode({ ...node, data: { ...node.data, ...normalized } })
        break
      }
      case 'ADD_INGREDIENT':
      case 'UPDATE_INGREDIENT':
      case 'REMOVE_INGREDIENT':
      case 'REPLACE_INGREDIENT': {
        const id = resolve(operation.target)
        const fields = stepFieldsOf(id)
        const ingredients = [...fields.actionOn.ingredients]
        const index = ingredients.findIndex((entry) => entry.ingredientId === operation.ingredientId)
        if (operation.op === 'ADD_INGREDIENT') {
          ingredients.push(toActionOnIngredient(operation.ingredient as GeneratedActionOnIngredient))
        } else {
          if (index === -1) throw new ProcessEditConflictError()
          if (operation.op === 'REMOVE_INGREDIENT') {
            ingredients.splice(index, 1)
          } else if (operation.op === 'REPLACE_INGREDIENT') {
            ingredients[index] = toActionOnIngredient(operation.ingredient as GeneratedActionOnIngredient)
          } else {
            const values = operation.ingredient as GeneratedActionOnIngredient
            const current = ingredients[index]
            ingredients[index] = {
              ...current,
              ...(values.quantity != null ? { quantity: values.quantity } : {}),
              ...(values.unit != null ? { unit: values.unit, customUnit: values.customUnit ?? '' } : {}),
              ...(values.preparationStyle != null ? { preparationStyleId: values.preparationStyle, customPreparationStyle: values.customPreparationStyle ?? '' } : {}),
              ...(values.customIngredientName != null ? { customIngredientName: values.customIngredientName } : {}),
            }
          }
        }
        const next = normalizeRecipeStepFields({ ...fields, actionOn: { ...fields.actionOn, ingredients } })
        replaceNode(withStepFields(nodeOf(id), next))
        break
      }
      case 'DELETE_NODE': {
        const id = resolve(operation.target)
        graph = detachNode(graph, id, newId)
        changed.delete(id)
        // Nothing may keep using the removed step's output.
        graph = {
          ...graph,
          nodes: graph.nodes.map((node) => {
            if (node.kind !== 'STEP') return node
            const fields = normalizeRecipeStepNodeData(node.data).step
            if (!fields.actionOn.steps.some((entry) => entry.stepId === id)) return node
            return withStepFields(node, { ...fields, actionOn: { ...fields.actionOn, steps: fields.actionOn.steps.filter((entry) => entry.stepId !== id) } })
          }),
        }
        break
      }
      case 'MOVE_NODE': {
        const id = resolve(operation.target)
        const node = nodeOf(id)
        graph = detachNode(graph, id, newId)
        insert(node, resolve(operation.after))
        break
      }
      default:
        throw new ProcessEditConflictError()
    }
  }

  return {
    process: { ...process, nodes: graph.nodes, edges: graph.edges },
    changedNodeIds: [...changed].filter((id) => graph.nodes.some((node) => node.id === id)),
  }
}

// --- human-readable preview ------------------------------------------------------------------

/** "step 3 (Boil)", "the check “Is it done?”", or "the new step" — how a node reference reads in a preview. */
const describeNodeRef = (process: Process, ref: string | null | undefined, newTitles: Map<string, string>): string => {
  if (!ref) return 'a step'
  if (ref === EDIT_START) return 'the start'
  if (ref === EDIT_NEW_FLOW) return 'a new flow'
  const added = newTitles.get(ref)
  if (added) return `the new “${added}” step`
  const node = process.nodes.find((candidate) => candidate.id === ref)
  if (!node) return 'a step'
  if (node.kind === 'CONDITION') return `the check “${normalizeConditionNodeData(node.data).title}”`
  const stepNodes = process.nodes.filter((candidate) => candidate.kind === 'STEP')
  return `step ${stepNodes.indexOf(node) + 1} (${normalizeRecipeStepNodeData(node.data).title})`
}

const describeIngredient = (ingredient: GeneratedActionOnIngredient | null | undefined, fallbackId?: string | null) => {
  const id = ingredient?.ingredientId ?? fallbackId ?? ''
  const name = getIngredientDisplayName(id, ingredient?.customIngredientName ?? '') || id
  const amount = ingredient?.quantity != null ? `${ingredient.quantity}${ingredient.unit ? ` ${ingredient.unit}` : ''} ` : ''
  return `${amount}${name}`
}

/** "amount → 1 tsp, style → Finely Chopped" — only what an UPDATE_INGREDIENT changes. */
const describeIngredientChange = (values: GeneratedActionOnIngredient | null | undefined): string => {
  if (!values) return 'details updated'
  const parts: string[] = []
  if (values.quantity != null || values.unit) parts.push(`amount → ${[values.quantity, values.unit].filter((part) => part != null && part !== '').join(' ')}`)
  if (values.preparationStyle) parts.push(`style → ${getPreparationStyleDisplayName(values.preparationStyle) || values.preparationStyle}`)
  return parts.join(', ') || 'details updated'
}

const describePatch = (patch: Partial<GeneratedRecipeStep>, clear: string[]): string => {
  const parts: string[] = []
  if (patch.action) parts.push(`action → ${getActionDisplayName(patch.action, patch.customActionName ?? '')}`)
  if (patch.duration) parts.push(`time → ${patch.duration}`)
  if (patch.flameLevel) parts.push(`flame → ${getFlameLevelDisplayName(patch.flameLevel) || patch.flameLevel}`)
  if (patch.temperatureValue != null) parts.push(`temperature → ${patch.temperatureValue}${patch.temperatureUnit ? ` °${patch.temperatureUnit}` : ''}`)
  if (patch.repeatInterval) parts.push(`repeat every ${patch.repeatInterval}`)
  if (patch.title) parts.push(`question → “${patch.title}”`)
  if (patch.expectedResult) parts.push(`expected result → ${patch.expectedResult}`)
  if (patch.expectedOutput) parts.push(`expected output → “${patch.expectedOutput}”`)
  if (patch.actionDescription) parts.push('description updated')
  if (patch.summary) parts.push('summary updated')
  if (patch.actionOn?.steps) parts.push('uses different earlier steps')
  if (patch.actionOn?.processes) parts.push('uses different subprocesses')
  if (clear.length > 0) parts.push(`cleared ${clear.join(', ')}`)
  return parts.join(', ') || 'details updated'
}

/** One line per operation, in cooking terms, for the user to review before applying. */
export const describeProcessEdit = (process: Process, operations: ProcessEditOperation[]): string[] => {
  const newTitles = new Map<string, string>()
  return operations.map((operation) => {
    const target = describeNodeRef(process, operation.target, newTitles)
    switch (operation.op) {
      case 'ADD_STEP': {
        const title = getActionDisplayName(operation.step?.action ?? '', operation.step?.customActionName ?? '') || 'step'
        if (operation.ref) newTitles.set(operation.ref, title)
        const ingredients = (operation.step?.actionOn?.ingredients ?? []).map((entry) => describeIngredient(entry)).join(', ')
        return `Add “${title}”${ingredients ? ` (${ingredients})` : ''} after ${describeNodeRef(process, operation.after, newTitles)}`
      }
      case 'ADD_CONDITION':
        if (operation.ref) newTitles.set(operation.ref, operation.step?.title ?? 'check')
        return `Add the check “${operation.step?.title ?? ''}” after ${describeNodeRef(process, operation.after, newTitles)}`
      case 'UPDATE_STEP':
      case 'UPDATE_CONDITION':
        return `Change ${target}: ${describePatch(operation.step ?? {}, operation.clear ?? [])}`
      case 'ADD_INGREDIENT':
        return `Add ${describeIngredient(operation.ingredient)} to ${target}`
      case 'UPDATE_INGREDIENT':
        return `Change ${describeIngredient(null, operation.ingredientId)} in ${target}: ${describeIngredientChange(operation.ingredient)}`
      case 'REMOVE_INGREDIENT':
        return `Remove ${describeIngredient(null, operation.ingredientId)} from ${target}`
      case 'REPLACE_INGREDIENT':
        return `Replace ${describeIngredient(null, operation.ingredientId)} with ${describeIngredient(operation.ingredient)} in ${target}`
      case 'DELETE_NODE':
        return `Remove ${target}`
      case 'MOVE_NODE':
        return `Move ${target} to after ${describeNodeRef(process, operation.after, newTitles)}`
      default:
        return 'Unknown change'
    }
  })
}
