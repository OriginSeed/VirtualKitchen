/**
 * A recipe as a file: its processes (MAIN and Preparation) with their steps, the recipe's own details, and
 * links to its step images and narration audio. Written by the editor's Export and by the AI bench; a recipe
 * can be created from it by any user, with no AI call ("New recipe → Import from file", `bench import`).
 *
 * The steps never carry another recipe's image links: those are lifted into `assets`, and on import the
 * server copies each linked file into one of the new recipe's own.
 *
 * Framework- and fetch-free, with the Action On reference rewriting injected, so it is tested directly
 * (frontEnd/tests/recipeFlowFile.test.ts) and shared with tools/ai-bench.
 */

import type { Process } from '../../../types/process'
import { remapProcessIds, type ProcessNodesRemapper } from '../session/persistence/snapshotPersistence.ts'

export const FLOW_VERSION = 1
export const FLOW_KIND = 'virtual-kitchen-recipe'

type NodeData = Record<string, unknown>

/** The saved shape of one process; `id` is a temporary negative id, references between processes use it. */
export type FlowProcess = {
  id: number
  type: string
  name: string
  description?: string
  nodes: { id: string; kind?: string; data?: NodeData; [key: string]: unknown }[]
  edges: unknown[]
  viewport?: unknown
  [key: string]: unknown
}

/** A step's image in the source recipe. `process` is the file's own (temporary) process id. */
export type FlowImage = { process: number; stepId: string; url: string; prompt?: string }

/** A step's narration audio in the source recipe. */
export type FlowAudio = { process: number; stepId: string; url: string }

export type FlowAssets = { images: FlowImage[]; audio: FlowAudio[] }

export type FlowDoc = {
  version: number
  kind?: string
  /** The recipe's own details (an export from the editor). */
  name?: string
  description?: string
  preparationNotes?: string
  nutrition?: Record<string, unknown>
  exportedAt?: string
  /** The bench recipe id the flow was generated for (bench files). */
  recipe: string
  recipeText: string
  /** The model that generated it, or null when it is not known. */
  textModel: string | null
  generatedAt: string
  /** The generation's warnings and repairs, kept so reused bench runs report the same quality signals. */
  warnings: string[]
  repairs: string[]
  processes: FlowProcess[]
  /** Links to the source recipe's images and audio; absent when it has none (and in bench files). */
  assets?: FlowAssets
}

/** Where a step's generated image lives on its node data: the editor's nested shape and the backend's flat one. */
export const VISUALIZATION_KEYS = ['visualization', 'visualizationAssetId', 'imagePrompt', 'imageUrl']

const text = (value: unknown): string => (typeof value === 'string' ? value.trim() : '')

/** A copy of the processes with every generated image removed, so a recipe built from them starts unillustrated. */
export function stripVisuals<T extends { nodes: { data?: NodeData }[] }>(processes: T[]): T[] {
  return processes.map((process) => ({
    ...process,
    nodes: process.nodes.map((node) => {
      if (!node.data || !VISUALIZATION_KEYS.some((key) => key in node.data!)) return node
      const data = { ...node.data }
      for (const key of VISUALIZATION_KEYS) delete data[key]
      return { ...node, data }
    }),
  }))
}

/** The image link of every illustrated step, read from either shape the node data can have. */
export function imageLinksOf(processes: { id: number; nodes: { id: string; data?: NodeData }[] }[]): FlowImage[] {
  const images: FlowImage[] = []
  for (const process of processes) {
    for (const node of process.nodes) {
      const data = node.data ?? {}
      const nested = data.visualization && typeof data.visualization === 'object' ? (data.visualization as NodeData) : {}
      const url = text(nested.imageUrl) || text(data.imageUrl)
      if (!url) continue
      const prompt = text(nested.imagePrompt) || text(data.imagePrompt)
      images.push({ process: process.id, stepId: node.id, url, ...(prompt ? { prompt } : {}) })
    }
  }
  return images
}

/** Each process's temporary id: -1 for MAIN, then -2, -3, … in the order given. */
export function temporaryIdsOf(processes: { id: number; type: string }[]): Map<number, number> {
  const ordered = [...processes].sort((a, b) => Number(b.type === 'MAIN') - Number(a.type === 'MAIN'))
  return new Map(ordered.map((process, index) => [process.id, -(index + 1)]))
}

/**
 * Gives every process its temporary id (MAIN first) and rewrites the references between them to match. A file
 * then never carries ids of the recipe it came from, and can't collide with the ids of the recipe it is built
 * into.
 */
export function withTemporaryIds(processes: Process[], remapNodes: ProcessNodesRemapper): Process[] {
  const ids = temporaryIdsOf(processes)
  const ordered = [...processes].sort((a, b) => ids.get(b.id)! - ids.get(a.id)!)
  // Two passes, so processes whose ids are already -1, -2, … in another order can't map onto each other halfway.
  const parked = new Map(ordered.map((process, index) => [process.id, -1_000_000 - index]))
  const temporary = new Map(ordered.map((process, index) => [-1_000_000 - index, ids.get(process.id)!]))
  return remapProcessIds(remapProcessIds(ordered, parked, remapNodes), temporary, remapNodes)
}

export type FlowSource = {
  processes: Process[]
  remapNodes: ProcessNodesRemapper
  /** The recipe's details, for an export from the editor. */
  name?: string
  description?: string
  preparationNotes?: string | null
  nutrition?: Record<string, unknown> | null
  /** Ready narration audio by the source's own process ids; mapped to the file's ids here. */
  audio?: { processId: number; stepId: string; url: string }[]
  /** False leaves the step images out (a bench flow is copied into runs that draw their own). */
  includeImages?: boolean
  /** Bench fields. */
  recipe?: string
  recipeText?: string
  textModel?: string | null
  warnings?: string[]
  repairs?: string[]
  now?: Date
}

/** The file of a recipe as it currently is: temporary ids, images lifted out of the steps into `assets`. */
export function toFlowDoc(source: FlowSource): FlowDoc {
  const ids = temporaryIdsOf(source.processes)
  const portable = withTemporaryIds(source.processes, source.remapNodes)
  const images = source.includeImages === false ? [] : imageLinksOf(portable)
  const stepIds = new Map(portable.map((process) => [process.id, new Set(process.nodes.map((node) => node.id))]))
  const audio = (source.audio ?? []).flatMap((entry): FlowAudio[] => {
    const process = ids.get(entry.processId)
    return process !== undefined && stepIds.get(process)?.has(entry.stepId) && text(entry.url)
      ? [{ process, stepId: entry.stepId, url: entry.url.trim() }]
      : []
  })
  const now = (source.now ?? new Date()).toISOString()
  const doc: FlowDoc = {
    version: FLOW_VERSION,
    kind: FLOW_KIND,
    ...(text(source.name) ? { name: text(source.name) } : {}),
    ...(text(source.description) ? { description: text(source.description) } : {}),
    ...(text(source.preparationNotes) ? { preparationNotes: text(source.preparationNotes) } : {}),
    ...(source.nutrition ? { nutrition: source.nutrition } : {}),
    exportedAt: now,
    recipe: source.recipe ?? '',
    recipeText: source.recipeText ?? '',
    textModel: source.textModel ?? null,
    generatedAt: now,
    warnings: source.warnings ?? [],
    repairs: source.repairs ?? [],
    processes: stripVisuals(portable).map((process): FlowProcess => ({
      id: process.id,
      type: process.type,
      name: process.name,
      description: process.description,
      nodes: process.nodes,
      edges: process.edges,
      viewport: process.viewport,
    })),
  }
  if (images.length > 0 || audio.length > 0) doc.assets = { images, audio }
  return doc
}

const fail = (where: string, message: string): never => {
  throw new Error(`${where}: ${message}`)
}

const isWebUrl = (value: string) => /^https?:\/\//i.test(value)

/** The asset links of a file that point at a step the file really has; anything malformed is an error. */
function parseAssets(raw: unknown, processes: FlowProcess[], where: string): FlowAssets | undefined {
  if (raw === undefined || raw === null) return undefined
  if (typeof raw !== 'object' || Array.isArray(raw)) fail(where, '"assets" must be an object')
  const value = raw as Record<string, unknown>
  const stepIds = new Map(processes.map((process) => [process.id, new Set(process.nodes.map((node) => node.id))]))
  const read = <T extends FlowAudio>(field: string, build: (entry: Record<string, unknown>, base: FlowAudio) => T): T[] => {
    const list = value[field] ?? []
    if (!Array.isArray(list)) fail(where, `"assets.${field}" must be a list`)
    return (list as unknown[]).flatMap((item, index): T[] => {
      if (!item || typeof item !== 'object') fail(where, `assets.${field}[${index}] is not an object`)
      const entry = item as Record<string, unknown>
      const url = text(entry.url)
      if (typeof entry.process !== 'number' || !text(entry.stepId) || !isWebUrl(url)) {
        fail(where, `assets.${field}[${index}] needs a "process" id, a "stepId" and an http(s) "url"`)
      }
      const base = { process: entry.process as number, stepId: text(entry.stepId), url }
      // A link to a step the file doesn't have is harmless: it is just not used.
      return stepIds.get(base.process)?.has(base.stepId) ? [build(entry, base)] : []
    })
  }
  const images = read<FlowImage>('images', (entry, base) => ({ ...base, ...(text(entry.prompt) ? { prompt: text(entry.prompt) } : {}) }))
  const audio = read<FlowAudio>('audio', (_entry, base) => base)
  return images.length > 0 || audio.length > 0 ? { images, audio } : undefined
}

/** Checks a parsed file's shape; throws with `where` (the file's name) and a readable reason on anything unusable. */
export function parseFlowDoc(raw: unknown, where: string): FlowDoc {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) fail(where, 'is not a recipe file (not a JSON object)')
  const value = raw as Record<string, unknown>
  if (value.version === undefined) {
    fail(where, 'is not a recipe file. Export the recipe again from the recipe editor (older exports held one process only and can’t be imported)')
  }
  if (value.version !== FLOW_VERSION) fail(where, `has version ${JSON.stringify(value.version)}; this app reads version ${FLOW_VERSION}`)
  if (!Array.isArray(value.processes) || value.processes.length === 0) fail(where, 'has no "processes"')
  const processes = value.processes as FlowProcess[]
  processes.forEach((process, index) => {
    if (!process || typeof process !== 'object') fail(where, `processes[${index}] is not an object`)
    if (typeof process.id !== 'number') fail(where, `processes[${index}].id must be a number`)
    if (process.type !== 'MAIN' && process.type !== 'SUBPROCESS') fail(where, `processes[${index}].type must be MAIN or SUBPROCESS`)
    if (!Array.isArray(process.nodes) || !Array.isArray(process.edges)) fail(where, `processes[${index}] needs "nodes" and "edges"`)
    process.nodes.forEach((node, nodeIndex) => {
      if (!node || typeof node !== 'object' || !text(node.id)) fail(where, `processes[${index}].nodes[${nodeIndex}] needs an "id"`)
    })
  })
  if (processes.filter((process) => process.type === 'MAIN').length !== 1) fail(where, 'must have exactly one MAIN process')
  if (new Set(processes.map((process) => process.id)).size !== processes.length) fail(where, 'has two processes with the same id')
  const strings = (field: unknown) => (Array.isArray(field) ? field.filter((entry): entry is string => typeof entry === 'string') : [])
  const nutrition = value.nutrition && typeof value.nutrition === 'object' && !Array.isArray(value.nutrition)
    ? (value.nutrition as Record<string, unknown>)
    : undefined
  const assets = parseAssets(value.assets, processes, where)
  return {
    version: FLOW_VERSION,
    ...(text(value.kind) ? { kind: text(value.kind) } : {}),
    ...(text(value.name) ? { name: text(value.name) } : {}),
    ...(text(value.description) ? { description: text(value.description) } : {}),
    ...(text(value.preparationNotes) ? { preparationNotes: text(value.preparationNotes) } : {}),
    ...(nutrition ? { nutrition } : {}),
    ...(text(value.exportedAt) ? { exportedAt: text(value.exportedAt) } : {}),
    recipe: text(value.recipe),
    recipeText: typeof value.recipeText === 'string' ? value.recipeText : '',
    textModel: text(value.textModel) || null,
    generatedAt: text(value.generatedAt),
    warnings: strings(value.warnings),
    repairs: strings(value.repairs),
    // Image links never travel on the steps, even if a file was edited by hand to carry some.
    processes: stripVisuals(processes),
    ...(assets ? { assets } : {}),
  }
}

/** Parses the text of a file; a file that is not JSON is reported like any other unusable file. */
export function parseFlowText(content: string, where: string): FlowDoc {
  let raw: unknown
  try {
    raw = JSON.parse(content)
  } catch {
    return fail(where, 'is not a recipe file (not valid JSON)')
  }
  return parseFlowDoc(raw, where)
}

export type FlowSummary = { processes: number; steps: number; images: number; audio: number }

/** What a file holds, for showing before it is imported. */
export function summarizeFlow(doc: FlowDoc): FlowSummary {
  return {
    processes: doc.processes.length,
    steps: doc.processes.reduce((count, process) => count + process.nodes.filter((node) => node.kind === 'STEP').length, 0),
    images: doc.assets?.images.length ?? 0,
    audio: doc.assets?.audio.length ?? 0,
  }
}

/** "Tomato Masala" -> "tomato-masala.recipe.json" */
export function flowFileName(name: string | undefined): string {
  const slug = (name ?? '').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60)
  return `${slug || 'recipe'}.recipe.json`
}
