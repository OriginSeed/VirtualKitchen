/**
 * Derives the human-readable Recipe Process (ingredients, ordered cooking steps, checks) from the
 * recipe's process graphs — the same MAIN/SUBPROCESS data the Recipe Editor edits, read from
 * RecipeSessionContext. Nothing here is stored: it's a pure, render-ready projection, so every
 * editor change (saved or not) shows up the next time it's derived.
 *
 * Graph concepts (nodes, edges, handles, ids) stop at this module; the presentation components
 * only ever see steps, checks, outcomes and sections expressed in cooking terms.
 */

import type { Process, ProcessNode } from '../../../../types/process'
import { getActionIcon, getStepActionById } from '../../../../shared/catalog/actionCatalog'
import { CUSTOM_FLAME_LEVEL_ID, getFlameLevelDisplayName } from '../../../../shared/catalog/flameLevelCatalog'
import { CUSTOM_INGREDIENT_ID, getIngredientById } from '../../../../shared/catalog/ingredientCatalog'
import { CUSTOM_PREPARATION_STYLE_ID, getPreparationStyleDisplayName } from '../../../../shared/catalog/preparationStyleCatalog'
import { CUSTOM_UNIT_ID, formatQuantityWithUnit, isQuantifiableUnit, PIECE_UNIT_ID } from '../../../../shared/catalog/unitCatalog'
import { normalizeConditionNodeData } from '../../model/recipeConditionData'
import { descriptionTail, restatesText } from './textOverlap'
import {
  getActionOnIngredientDisplayName,
  getRecipeStepTemperatureLabel,
  getRecipeStepActionName,
  normalizeRecipeStepNodeData,
  stepAnnotationLabelResolver,
  type ActionOnIngredient,
  type RecipeStepFields,
} from '../../model/recipeStepData'
import { orderProcesses, orderProcessNodes, type OrderedProcess } from '../../model/processReadingOrder'
import { CONDITION_HANDLES } from '../../model/recipeNodeTypes'
import { toPlainText, type AnnotationLabelResolver } from '../../model/stepAnnotations'
import { actionVerbOf, deriveStepTitle } from '../../model/stepTitle'
import { explainPreparationFlows, nodesContinuingPrevious, type ExplainedFlow } from './processExplanation'

// ---------------------------------------------------------------------------------------------
// Presentation types
// ---------------------------------------------------------------------------------------------

export type RecipeIngredientLine = {
  key: string
  name: string
  icon: string
  /** "200 g", "2", "to taste" — '' when no step states an amount. */
  amount: string
  preparations: string[]
}

/**
 * One run of a step's instruction sentence. Amounts are emphasised, and an earlier result
 * ("the marinated chicken") links to the step or stage that makes it.
 */
export type InstructionPart = {
  text: string
  kind?: 'amount' | 'product'
  /** For a product: where it's made, on this page. */
  anchorId?: string
  /** For a product: "from step 2" / "from Marinate chicken". */
  source?: string
}

/** A step or check another item points at ("go back to step 3", "back to the check …"). */
export type RecipeItemRef = {
  key: string
  anchorId: string
  /** The step's number; undefined for a check (checks are decisions, not numbered steps). */
  number?: number
  title: string
  isCheck: boolean
}

/** Where the cook goes next, only when it isn't simply "the next step". */
export type StepContinuation =
  | { kind: 'jump'; direction: 'forward' | 'back'; target: RecipeItemRef }
  | { kind: 'choice'; targets: RecipeItemRef[] }
  | { kind: 'end' }

export type RecipeBranchTag = { answer: string; question: string }


export type RecipeTimelineStep = {
  kind: 'step'
  key: string
  anchorId: string
  number: number
  /** Short heading ("Marinate chicken"), also used when other items refer to this step. */
  name: string
  /** False when the heading would only repeat the sentence (a step without an action). */
  showName: boolean
  /**
   * What to do, as one continuous sentence built from the step's own fields — the action, what it
   * works on (earlier results by name), its ingredients with their amounts, and heat, temperature
   * and time: "Fry the marinated chicken with 2 tbsp oil over medium heat for 10 min."
   */
  instruction: InstructionPart[]
  /** The same sentence as plain text. */
  instructionText: string
  /** The step's own description, only when it adds something the sentence doesn't already say. */
  explanation: string
  /** The expected result, only when the sentence and explanation don't already say it. */
  readyWhen: string
  icon: string
  imageUrl?: string
  branch?: RecipeBranchTag
  continuation?: StepContinuation
  /** Where the step lives, to open it in the editor. */
  processId: number
  nodeId: string
  /**
   * Main process: the step follows straight on from the one before (an edge leads from it) and introduces nothing
   * new — it carries on with what is already cooking.
   */
  continues: boolean
}

export type CheckOutcome = {
  key: string
  tone: 'yes' | 'no'
  answer: string
  /** 'next' = carry on in order, 'back' = repeat earlier steps, 'forward' = skip ahead. */
  direction: 'next' | 'back' | 'forward'
  target: RecipeItemRef
}

export type RecipeTimelineCheck = {
  kind: 'check'
  key: string
  anchorId: string
  question: string
  notes: string
  outcomes: CheckOutcome[]
  branch?: RecipeBranchTag
}

export type RecipeTimelineItem = RecipeTimelineStep | RecipeTimelineCheck

/**
 * One preparation flow, read as ONE recipe step: "Prepare Marinated Chicken", its steps as explanatory points in
 * order, and what it makes. Its points share the flow's step number.
 */
export type RecipeTimelineFlow = {
  key: string
  anchorId: string
  /** The flow's step number (counted like any other step). */
  number: number
  /** Where "Open in editor" goes: the step that makes the flow's output (else its first node). */
  processId: number
  nodeId: string
  /** The image stored on the step that makes the flow's output, if any (older recipes: one image for the whole flow). */
  imageUrl?: string
  /**
   * Whether the flow's steps have images of their own (drawn per step, or cut from a shared panel image) rather than
   * one image for the whole flow: then each point shows its own image.
   */
  stepImages: boolean
  /** "Prepare <output>" (or "Preparation flow N" while the flow has no named output). */
  title: string
  outputName: string
  stepCount: number
  items: RecipeTimelineItem[]
}

/** One stage of the recipe: a process (the Preparation, then the main process last). */
export type RecipeTimelineSection = {
  key: string
  anchorId: string
  title: string
  /** The process's own description, '' when it has none. */
  summary: string
  isMain: boolean
  /** Numbered steps in this stage (checks not counted; a preparation flow counts as one). */
  stepCount: number
  /** Every item, in order (for a Preparation: flow after flow). */
  items: RecipeTimelineItem[]
  /** A Preparation's flows, each shown as one card. */
  flows?: RecipeTimelineFlow[]
}

export type RecipePresentation = {
  sections: RecipeTimelineSection[]
  ingredients: RecipeIngredientLine[]
  stepCount: number
  /** Sum of every timed step, in minutes — undefined when no step has a duration. */
  totalMinutes?: number
  heroImageUrl?: string
}

// ---------------------------------------------------------------------------------------------
// Wording
// ---------------------------------------------------------------------------------------------

const joinNatural = (parts: string[]) => {
  if (parts.length <= 1) return parts.join('')
  return `${parts.slice(0, -1).join(', ')} and ${parts[parts.length - 1]}`
}

const withArticle = (text: string) => (/^(the|a|an|some|any|all|\d)/i.test(text) ? text : `the ${text}`)

const firstSentence = (text: string, max = 64) => {
  const sentence = text.trim().split(/(?<=[.!?])\s/)[0] ?? ''
  return sentence.length > max ? `${sentence.slice(0, max - 1).trimEnd()}…` : sentence.replace(/[.]$/, '')
}

/** Ingredient names read in lower case inside a sentence ("Chop the onion"); custom names are kept as typed. */
const ingredientNameInSentence = (entry: ActionOnIngredient) =>
  entry.ingredientId === CUSTOM_INGREDIENT_ID ? getActionOnIngredientDisplayName(entry) : getActionOnIngredientDisplayName(entry).toLowerCase()

/**
 * The basic ingredient behind a step's result: "chicken" for "the marinated chicken", followed
 * back through the steps that made it (marinated ← cut ← cleaned 500 g chicken).
 */
const rootIngredientName = (node: ProcessNode | undefined, stepById: Map<string, ProcessNode>, depth = 0): string => {
  if (node?.kind !== 'STEP' || depth > 40) return ''
  const { step } = normalizeRecipeStepNodeData(node.data)
  const from = step.actionOn.steps[0]
  if (from) return rootIngredientName(stepById.get(from.stepId), stepById, depth + 1)
  const first = step.actionOn.ingredients[0]
  return first ? ingredientNameInSentence(first) : ''
}

/**
 * A step's short heading — the action and what it's done to, plainly: "Clean chicken", "Cut
 * chicken", "Marinate chicken", "Chop onion and potato". No amounts or details; those are in its
 * sentence (see composeInstruction). Without an action, the first sentence of its description.
 */
const buildStepHeading = (step: RecipeStepFields, earlierBases: string[], resolveLabel: AnnotationLabelResolver) => {
  if (!step.action) {
    const description = toPlainText(step.actionDescription, resolveLabel).trim()
    return description ? firstSentence(description) : 'Next step'
  }
  const own = step.actionOn.ingredients.map(ingredientNameInSentence)
  const subjects = earlierBases.length > 0 ? earlierBases : worksOnAll(step) ? own : own.slice(0, 1)
  // What the instruction names (@ annotations) comes first; otherwise what the step works on.
  return deriveStepTitle({ actionName: getRecipeStepActionName(step), description: step.actionDescription, subjects, resolveLabel })
}

/** What a step produces, in words: its Expected Output, else what it worked on. */
const stepProductLabel = (node: ProcessNode | undefined) => {
  if (node?.kind !== 'STEP') return ''
  const { step } = normalizeRecipeStepNodeData(node.data)
  return step.expectedOutput.trim() || joinNatural(step.actionOn.ingredients.map(ingredientNameInSentence))
}

/** What a subprocess produces: its last step's Expected Output, else the subprocess's own name. */
const processProductLabel = (process: Process, ordered: OrderedProcess | undefined) => {
  const last = [...(ordered?.nodes ?? [])].reverse().find((node) => node.kind === 'STEP' && normalizeRecipeStepNodeData(node.data).step.expectedOutput.trim())
  return (last ? stepProductLabel(last) : '') || process.name.trim()
}

const SHORT_DURATION_UNITS: Record<string, string> = { seconds: 'sec', minutes: 'min', hours: 'hr' }
const MINUTES_PER_UNIT: Record<string, number> = { seconds: 1 / 60, minutes: 1, hours: 60 }

const durationLabel = (value: string, unit: string) => {
  const trimmed = value.trim()
  if (!trimmed || !unit) return ''
  return `${trimmed} ${SHORT_DURATION_UNITS[unit] ?? unit}`
}

const durationMinutes = (step: RecipeStepFields) => {
  const value = Number(step.durationValue)
  const factor = MINUTES_PER_UNIT[step.durationUnit]
  return Number.isFinite(value) && value > 0 && factor ? value * factor : 0
}

const heatLabel = (step: RecipeStepFields) => {
  const label = getFlameLevelDisplayName(step.flameLevelId, step.customFlameLevel)
  if (!label) return ''
  if (step.flameLevelId === CUSTOM_FLAME_LEVEL_ID || /heat|flame/i.test(label)) return label
  if (step.flameLevelId === 'off') return 'Heat off'
  return `${label} heat`
}

/** "Mix" for "Mix / Combine" — the catalog's first name reads as a verb in a sentence. */
const actionVerb = (step: RecipeStepFields) => actionVerbOf(getRecipeStepActionName(step))

/** "onions" for 2 onion — only for plain counts of a catalog ingredient whose name isn't already plural. */
const pluralize = (name: string) => {
  if (/s$/i.test(name)) return name
  if (/[^aeiou]y$/i.test(name)) return `${name.slice(0, -1)}ies`
  if (/(x|ch|sh|o)$/i.test(name)) return `${name}es`
  return `${name}s`
}

/** Preparation styles that read as a size or texture, said after the name: "1 onion (fine)". */
const PREPARATION_AFTER_NAME = new Set(['fine', 'medium', 'large', 'smooth', 'chunky'])

/** "chopped", "chopped"/"choped", "minced" — the ways a verb's past participle can be spelled. */
const participlesOf = (verb: string) => {
  const lower = verb.toLowerCase()
  return new Set([`${lower}ed`, `${lower}d`, `${lower}${lower.slice(-1)}ed`, /y$/.test(lower) ? `${lower.slice(0, -1)}ied` : ''].filter(Boolean))
}

/** How the step's action shapes the sentence (see composeInstruction and ingredientParts). */
type SentenceContext = { verb: string; isCut: boolean }

/**
 * One ingredient as it's said inside the sentence: "500 g chicken", "2 finely chopped onions",
 * "salt to taste". Amounts become their own (emphasised) part. A preparation style that only
 * repeats the action isn't said twice: "Chop 2 onions finely", not "Chop 2 finely chopped onions";
 * and a cut says what it makes: "Cut 1 potato into bite-sized pieces".
 */
const ingredientParts = (entry: ActionOnIngredient, context: SentenceContext): InstructionPart[] => {
  const quantifiable = isQuantifiableUnit(entry.unit)
  const amount = quantifiable ? formatQuantityWithUnit(entry.quantity, entry.unit, entry.customUnit) : ''
  const isPlainCount = quantifiable && (entry.unit === '' || entry.unit === PIECE_UNIT_ID) && (entry.quantity ?? 0) > 1
  const baseName = ingredientNameInSentence(entry)
  const name = isPlainCount && entry.ingredientId !== CUSTOM_INGREDIENT_ID ? pluralize(baseName) : baseName

  const styleId = entry.preparationStyleId ?? ''
  const style = getPreparationStyleDisplayName(styleId, entry.customPreparationStyle).trim()
  let before = ''
  let after = ''
  if (style) {
    const lower = style.toLowerCase()
    const words = lower.split(/\s+/)
    const repeatsVerb = participlesOf(context.verb).has(words[words.length - 1])
    if (repeatsVerb) after = words.length > 1 ? ` ${words.slice(0, -1).join(' ')}` : ''
    else if (styleId === CUSTOM_PREPARATION_STYLE_ID || PREPARATION_AFTER_NAME.has(styleId)) after = ` (${styleId === CUSTOM_PREPARATION_STYLE_ID ? style : lower})`
    else if (/ed$/.test(lower) || styleId === 'whole') before = `${lower} `
    else if (styleId === 'paste') after = ' as a paste'
    else after = ` ${context.isCut ? 'into' : 'in'} ${lower}`
  }
  const nonNumeric = !quantifiable && entry.unit !== CUSTOM_UNIT_ID ? ` ${formatQuantityWithUnit(entry.quantity, entry.unit).toLowerCase()}` : ''
  const notes = entry.notes?.trim() ? ` (${entry.notes.trim()})` : ''

  const parts: InstructionPart[] = []
  if (amount) parts.push({ text: amount, kind: 'amount' }, { text: ' ' })
  parts.push({ text: `${before}${name}${after}${nonNumeric}${notes}` })
  return parts
}

/** "a, b and c" over runs of parts. */
const joinPartLists = (lists: InstructionPart[][]): InstructionPart[] =>
  lists.flatMap((list, index) => {
    if (index === 0) return list
    return [{ text: index === lists.length - 1 ? ' and ' : ', ' }, ...list]
  })

const TEMPERATURE_PLACE: Record<string, string> = {
  oven: 'in the oven at',
  oil: 'with the oil at',
  liquid: 'with the liquid at',
}

/** " over medium heat in the oven at 180 °C for 5 min, every 2 min" — whatever the step states. */
const parametersText = (step: RecipeStepFields) => {
  const heat = heatLabel(step)
  const temperature = getRecipeStepTemperatureLabel(step)
  const context = step.action ? getStepActionById(step.action).temperatureContext : null
  const duration = durationLabel(step.durationValue, step.durationUnit)
  const repeat = durationLabel(step.repeatIntervalValue, step.repeatIntervalUnit)
  return [
    heat ? (step.flameLevelId === 'off' ? ' with the heat off' : ` over ${heat.toLowerCase()}`) : '',
    temperature ? ` ${(context && TEMPERATURE_PLACE[context]) || 'at'} ${temperature}` : '',
    duration ? ` for ${duration}` : '',
    repeat ? `, every ${repeat}` : '',
  ].join('')
}

/** Actions that work on everything they're given ("Chop 2 onions and 1 potato") rather than on one thing "with" the rest. */
const ALL_OBJECT_CATEGORIES = new Set(['cleaning', 'cutting', 'mixing', 'preservation', 'control'])
const WITH_HANDLING_ACTIONS = new Set(['season', 'stuff', 'layer', 'spread', 'skewer', 'wrap'])

const worksOnAll = (step: RecipeStepFields) => {
  if (!step.action) return false
  const category = getStepActionById(step.action).category
  return ALL_OBJECT_CATEGORIES.has(category) || (category === 'handling' && !WITH_HANDLING_ACTIONS.has(step.action))
}

/**
 * A step as one continuous sentence, from its own fields only — nothing is guessed:
 * - earlier results it works on come first, by what they've become: "Fry the marinated chicken
 *   with 2 tbsp oil";
 * - otherwise, for cooking-type actions its first ingredient is what it works on and the rest are
 *   "with": "Marinate 500 g chicken with 200 g curd and 1 tsp chilli powder"; cutting, cleaning,
 *   mixing and simple handling work on all of them: "Chop 2 onions and 1 potato";
 * - then heat, temperature and time.
 * Empty without an action (the step's description is used instead).
 */
const composeInstruction = (step: RecipeStepFields, products: InstructionPart[][]): InstructionPart[] => {
  const verb = actionVerb(step)
  if (!step.action || !verb) return []
  const category = getStepActionById(step.action).category
  const allObjects = worksOnAll(step)
  const ingredients = step.actionOn.ingredients.map((entry) => ingredientParts(entry, { verb, isCut: category === 'cutting' }))
  const objects = products.length > 0 ? products : allObjects ? ingredients : ingredients.slice(0, 1)
  const withs = products.length > 0 ? ingredients : allObjects ? [] : ingredients.slice(1)

  const parts: InstructionPart[] = [{ text: verb }]
  if (objects.length > 0) parts.push({ text: ' ' }, ...joinPartLists(objects))
  if (withs.length > 0) parts.push({ text: ' with ' }, ...joinPartLists(withs))
  const parameters = parametersText(step)
  if (parameters) parts.push({ text: parameters })
  parts.push({ text: '.' })
  return parts
}

/** An earlier result as the sentence's object: "the marinated chicken", linked to where it's made. */
const productParts = (label: string, anchorId: string | undefined, source: string | undefined): InstructionPart[] => {
  const withThe = withArticle(label)
  const article = withThe.slice(0, withThe.length - label.length)
  return [...(article ? [{ text: article }] : []), { text: label, kind: 'product' as const, anchorId, source }]
}

const instructionText = (parts: InstructionPart[]) => parts.map((part) => part.text).join('')

const GENERIC_MAIN_NAME = /^\s*main(\s+process)?\s*$/i

const sectionAnchorId = (processId: number) => `recipe-part-${processId}`

const itemAnchorId = (processId: number, nodeId: string) => `recipe-item-${processId}-${nodeId}`

const sectionTitleOf = (process: Process) => {
  const name = process.name.trim()
  return process.type === 'MAIN' && (!name || GENERIC_MAIN_NAME.test(name)) ? 'Bring it all together' : name || 'Preparation'
}

// ---------------------------------------------------------------------------------------------
// Builder
// ---------------------------------------------------------------------------------------------

/**
 * Builds the complete Recipe Process presentation from the recipe's processes. Step numbers run
 * continuously through the whole recipe, so "from step 3" means the same anywhere on the page;
 * checks aren't numbered (they're decisions, not things to do), so the numbers match the step count.
 */
export const buildRecipePresentation = (processes: Process[]): RecipePresentation => {
  const orderedById = new Map(processes.map((process) => [process.id, orderProcessNodes(process)]))
  // A Preparation reads flow by flow, each flow in cooking order (see processExplanation.ts).
  const flowsById = new Map<number, ExplainedFlow[]>()
  processes.filter((process) => process.type === 'SUBPROCESS').forEach((process) => {
    const ordered = orderedById.get(process.id) as OrderedProcess
    const flows = explainPreparationFlows(process, ordered.nodes.map((node) => node.id))
    const nodeById = new Map(ordered.nodes.map((node) => [node.id, node]))
    flowsById.set(process.id, flows)
    orderedById.set(process.id, { ...ordered, nodes: flows.flatMap((flow) => flow.nodeIds).map((id) => nodeById.get(id) as ProcessNode) })
  })
  const processById = new Map(processes.map((process) => [process.id, process]))
  const sectionProcesses = orderProcesses(processes, orderedById).filter((process) => process.nodes.length > 0)
  /** What a used prepared component is called: the Preparation step's output, or (legacy) a whole process's product. */
  const productOf = (entry: { processId: number; stepId?: string }) => {
    const process = processById.get(entry.processId)
    if (!process) return ''
    if (entry.stepId) return stepProductLabel(process.nodes.find((node) => node.id === entry.stepId && node.kind === 'STEP'))
    return processProductLabel(process, orderedById.get(entry.processId))
  }

  // Numbering first, so references (checks, jumps, "from step N") can resolve any step.
  const numberOf = new Map<string, number>()
  let counter = 0
  for (const process of sectionProcesses) {
    const flows = flowsById.get(process.id)
    if (flows) {
      // A preparation flow is one step of the recipe; every point in it carries that number.
      const nodeById = new Map(process.nodes.map((node) => [node.id, node]))
      for (const flow of flows) {
        const stepIds = flow.nodeIds.filter((id) => nodeById.get(id)?.kind === 'STEP')
        if (stepIds.length === 0) continue
        const number = ++counter
        stepIds.forEach((id) => numberOf.set(`${process.id}:${id}`, number))
      }
      continue
    }
    for (const node of orderedById.get(process.id)?.nodes ?? []) {
      if (node.kind === 'STEP') numberOf.set(`${process.id}:${node.id}`, ++counter)
    }
  }

  // Summed across every step, grouped by ingredient + unit; a step that mentions an ingredient
  // without an amount ("stir the onions") adds nothing to its total.
  const ingredientTotals = new Map<string, Omit<RecipeIngredientLine, 'amount'> & { quantity: number | null; unit: ActionOnIngredient['unit']; customUnit: string }>()
  let totalMinutes = 0
  let heroImageUrl: string | undefined
  let fallbackImageUrl: string | undefined

  const sections = sectionProcesses.map<RecipeTimelineSection>((process) => {
    const ordered = orderedById.get(process.id) as OrderedProcess
    const stepById = new Map(ordered.nodes.map((node) => [node.id, node]))
    const continuing = process.type === 'MAIN' ? nodesContinuingPrevious(process, ordered.nodes.map((node) => node.id)) : new Set<string>()
    const titles = new Map<string, string>()
    const indexOf = new Map(ordered.nodes.map((node, index) => [node.id, index]))
    const refOf = (nodeId: string): RecipeItemRef => ({
      key: `${process.id}:${nodeId}`,
      anchorId: itemAnchorId(process.id, nodeId),
      number: numberOf.get(`${process.id}:${nodeId}`),
      title: titles.get(nodeId) ?? '',
      isCheck: stepById.get(nodeId)?.kind === 'CONDITION',
    })

    // Annotations read as live names: prepared outputs and earlier results as this view names them.
    const resolveLabel = stepAnnotationLabelResolver({
      preparedName: productOf,
      stepOutputLabel: (stepId) => stepProductLabel(stepById.get(stepId)),
    })

    // Titles first too — outcomes and jumps quote their target's title.
    const stepFields = new Map<string, RecipeStepFields>()
    for (const node of ordered.nodes) {
      if (node.kind === 'CONDITION') {
        titles.set(node.id, normalizeConditionNodeData(node.data).condition.question.trim() || 'Check before continuing')
        continue
      }
      const { step } = normalizeRecipeStepNodeData(node.data)
      stepFields.set(node.id, step)
      const earlierBases = [
        ...step.actionOn.steps.map((entry) => rootIngredientName(stepById.get(entry.stepId), stepById)),
        ...step.actionOn.processes.map(productOf),
      ].filter(Boolean)
      titles.set(node.id, buildStepHeading(step, earlierBases, resolveLabel))
    }

    const relativeDirection = (fromId: string, toId: string): CheckOutcome['direction'] => {
      const from = indexOf.get(fromId) ?? 0
      const to = indexOf.get(toId) ?? 0
      if (to === from + 1) return 'next'
      return to <= from ? 'back' : 'forward'
    }

    /** "Only if …" when the only way into this item is one side of a genuine fork (not a retry loop). */
    const branchTagFor = (nodeId: string): RecipeBranchTag | undefined => {
      const into = ordered.incoming.get(nodeId) ?? []
      if (into.length !== 1) return undefined
      const edge = into[0]
      if (edge.sourceHandle !== CONDITION_HANDLES.yes && edge.sourceHandle !== CONDITION_HANDLES.no) return undefined
      // Reached only by looping back ("not golden yet → fry again"): a retry, not a fork.
      if (ordered.backEdges.has(edge)) return undefined
      const condition = stepById.get(edge.source)
      if (condition?.kind !== 'CONDITION') return undefined
      const otherSide = (ordered.outgoing.get(edge.source) ?? []).find((other) =>
        other.sourceHandle !== edge.sourceHandle && (other.sourceHandle === CONDITION_HANDLES.yes || other.sourceHandle === CONDITION_HANDLES.no))
      if (!otherSide || ordered.backEdges.has(otherSide)) return undefined
      const { condition: fields } = normalizeConditionNodeData(condition.data)
      const answer = edge.label?.trim() || (edge.sourceHandle === CONDITION_HANDLES.yes ? fields.successLabel : fields.failureLabel)
      return { answer, question: fields.question.trim() }
    }

    const items = ordered.nodes.map<RecipeTimelineItem>((node, index) => {
      const key = `${process.id}:${node.id}`
      const anchorId = itemAnchorId(process.id, node.id)

      if (node.kind === 'CONDITION') {
        const { condition } = normalizeConditionNodeData(node.data)
        const outcomes: CheckOutcome[] = []
        for (const [handle, tone, fallback] of [
          [CONDITION_HANDLES.yes, 'yes', condition.successLabel],
          [CONDITION_HANDLES.no, 'no', condition.failureLabel],
        ] as const) {
          const edge = (ordered.outgoing.get(node.id) ?? []).find((candidate) => candidate.sourceHandle === handle)
          if (!edge) continue
          outcomes.push({
            key: `${key}:${handle}`,
            tone,
            answer: edge.label?.trim() || fallback || (tone === 'yes' ? 'Yes' : 'No'),
            direction: relativeDirection(node.id, edge.target),
            target: refOf(edge.target),
          })
        }
        return {
          kind: 'check',
          key,
          anchorId,
          question: titles.get(node.id) ?? '',
          notes: condition.notes.trim(),
          outcomes,
          branch: branchTagFor(node.id),
        }
      }

      const step = stepFields.get(node.id) as RecipeStepFields
      const { visualization } = normalizeRecipeStepNodeData(node.data)

      for (const entry of step.actionOn.ingredients) {
        const ingredientKey = `${entry.ingredientId}:${entry.customIngredientName ?? ''}:${entry.unit}:${entry.customUnit ?? ''}`
        const preparation = getPreparationStyleDisplayName(entry.preparationStyleId ?? '', entry.customPreparationStyle)
        const existing = ingredientTotals.get(ingredientKey)
        if (existing) {
          if (entry.quantity != null) existing.quantity = (existing.quantity ?? 0) + entry.quantity
          if (preparation && !existing.preparations.includes(preparation)) existing.preparations.push(preparation)
        } else {
          ingredientTotals.set(ingredientKey, {
            key: ingredientKey,
            name: getActionOnIngredientDisplayName(entry),
            icon: getIngredientById(entry.ingredientId).icon,
            preparations: preparation ? [preparation] : [],
            quantity: entry.quantity,
            unit: entry.unit,
            customUnit: entry.customUnit ?? '',
          })
        }
      }

      totalMinutes += durationMinutes(step)
      if (visualization?.imageUrl) {
        fallbackImageUrl ??= visualization.imageUrl
        // The last illustrated step of the main process is usually the finished dish.
        if (process.type === 'MAIN') heroImageUrl = visualization.imageUrl
      }

      // Say each thing once: one sentence from the step's fields; its description and expected
      // result only show when they add something that sentence doesn't already say.
      const name = titles.get(node.id) ?? ''
      const description = toPlainText(step.actionDescription, resolveLabel).trim()
      const products = [
        ...step.actionOn.steps.flatMap((entry) => {
          const label = stepProductLabel(stepById.get(entry.stepId))
          const number = numberOf.get(`${process.id}:${entry.stepId}`)
          return label ? [productParts(label, itemAnchorId(process.id, entry.stepId), number ? `step ${number}` : undefined)] : []
        }),
        ...step.actionOn.processes.flatMap((entry) => {
          const sub = processById.get(entry.processId)
          if (!sub) return []
          const shown = sectionProcesses.includes(sub)
          // A Preparation step's output links to that step ("step 3"); a legacy whole-process use to its section.
          if (entry.stepId) {
            const number = numberOf.get(`${sub.id}:${entry.stepId}`)
            return [productParts(productOf(entry) || 'prepared component', shown ? itemAnchorId(sub.id, entry.stepId) : undefined, number ? `step ${number}` : undefined)]
          }
          return [productParts(productOf(entry) || 'prepared component', shown ? sectionAnchorId(sub.id) : undefined, shown ? sectionTitleOf(sub) : undefined)]
        }),
      ]
      // A description that opens by restating the built sentence only adds its tail ("Cut the
      // chicken into medium pieces" → "Cut the cleaned chicken into medium pieces."), so the two
      // read as one sentence; anything else it says stays a separate line.
      let composed = composeInstruction(step, products)
      let remaining = description
      if (composed.length > 0 && description) {
        const merge = descriptionTail(description, instructionText(composed))
        if (merge) {
          if (merge.tail) composed = [...composed.slice(0, -1), { text: ` ${merge.tail}` }, composed[composed.length - 1]]
          remaining = merge.rest
        }
      }
      const instruction = composed.length > 0 ? composed : [{ text: description || name }]
      const sentence = instructionText(instruction)
      const explanation = composed.length > 0 && remaining && !restatesText(remaining, [sentence]) ? remaining : ''
      const expected = step.expectedOutput.trim()
      const readyWhen = expected && !restatesText(expected, [sentence, explanation]) ? expected : ''

      // Only mention where to go next when it isn't simply the following item.
      const next = ordered.outgoing.get(node.id) ?? []
      let continuation: StepContinuation | undefined
      if (next.length === 0) {
        if (index < ordered.nodes.length - 1) continuation = { kind: 'end' }
      } else if (next.length === 1) {
        const direction = relativeDirection(node.id, next[0].target)
        if (direction !== 'next') continuation = { kind: 'jump', direction, target: refOf(next[0].target) }
      } else {
        const targets = [...new Set(next.map((edge) => edge.target))].sort((a, b) => (indexOf.get(a) ?? 0) - (indexOf.get(b) ?? 0))
        continuation = { kind: 'choice', targets: targets.map(refOf) }
      }

      return {
        kind: 'step',
        key,
        anchorId,
        processId: process.id,
        nodeId: node.id,
        // Carries on with what is already cooking: follows straight on, and brings in nothing new.
        continues: continuing.has(node.id) && step.actionOn.ingredients.length === 0 && step.actionOn.processes.length === 0,
        number: numberOf.get(key) ?? 0,
        name,
        showName: composed.length > 0,
        instruction,
        instructionText: sentence,
        explanation,
        readyWhen,
        icon: step.action ? getActionIcon(step.action) : '🍳',
        imageUrl: visualization?.imageUrl,
        branch: branchTagFor(node.id),
        continuation,
      }
    })

    const itemByNode = new Map(ordered.nodes.map((node, at) => [node.id, items[at]]))
    const flows = flowsById.get(process.id)?.map<RecipeTimelineFlow>((flow) => {
      const flowItems = flow.nodeIds.map((id) => itemByNode.get(id) as RecipeTimelineItem)
      const firstStep = flowItems.find((item) => item.kind === 'step')
      const outputItem = flow.outputNodeId ? itemByNode.get(flow.outputNodeId) : undefined
      return {
        key: `${process.id}:flow-${flow.index}`,
        anchorId: `recipe-flow-${process.id}-${flow.index}`,
        number: firstStep?.kind === 'step' ? firstStep.number : 0,
        processId: process.id,
        nodeId: flow.outputNodeId ?? flow.nodeIds[0],
        imageUrl: outputItem?.kind === 'step' ? outputItem.imageUrl : undefined,
        // Older recipes have one image, on the output step; any other step with an image means per-step images.
        stepImages: flowItems.some((item) => item.kind === 'step' && item.nodeId !== flow.outputNodeId && Boolean(item.imageUrl)),
        title: flow.title,
        outputName: flow.outputName,
        stepCount: flowItems.filter((item) => item.kind === 'step').length,
        items: flowItems,
      }
    })

    return {
      key: String(process.id),
      anchorId: sectionAnchorId(process.id),
      title: sectionTitleOf(process),
      summary: process.description?.trim() ?? '',
      isMain: process.type === 'MAIN',
      stepCount: flows ? flows.filter((flow) => flow.stepCount > 0).length : items.filter((item) => item.kind === 'step').length,
      items,
      flows,
    }
  })

  const ingredients = Array.from(ingredientTotals.values()).map<RecipeIngredientLine>(({ quantity, unit, customUnit, key, name, icon, preparations }) => ({
    key,
    name,
    icon,
    amount: formatQuantityWithUnit(quantity, unit, customUnit),
    preparations,
  }))

  return {
    sections,
    ingredients,
    stepCount: sections.reduce((count, section) => count + section.stepCount, 0),
    totalMinutes: totalMinutes > 0 ? Math.round(totalMinutes) : undefined,
    heroImageUrl: heroImageUrl ?? fallbackImageUrl,
  }
}

/** "step 3 · Fry the onions", or "the check “Is it golden?”" — how items refer to each other. */
export const describeRef = (ref: RecipeItemRef, withTitle = true) => {
  if (ref.isCheck) return `the check “${ref.title}”`
  return `step ${ref.number ?? '?'}${withTitle && ref.title ? ` · ${ref.title}` : ''}`
}

/** What choosing a check's outcome means: "Go back to step 2 · Fry the onions". */
export const describeOutcome = (outcome: CheckOutcome) => {
  const target = describeRef(outcome.target)
  if (outcome.direction === 'back') return `Go back to ${target}`
  if (outcome.direction === 'forward') return `Skip ahead to ${target}`
  return `Carry on with ${target}`
}

/** "1 hr 15 min", "40 min". */
export const formatTotalMinutes = (minutes: number) => {
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  const rest = minutes % 60
  return rest ? `${hours} hr ${rest} min` : `${hours} hr`
}
