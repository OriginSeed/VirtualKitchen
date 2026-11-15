/**
 * Exporting a recipe as a file and creating a recipe from one, against the real API. The file format and the
 * step import are pure modules (model/recipeFlowFile.ts, persistence/importRecipeFlow.ts); this wires them to
 * the app's API calls.
 */

import { ProcessApi, RecipeApi, RecipeDetailApi } from '../../../api'
import { StepNarrationApi } from '../../../api/narrationApi'
import type { Process } from '../../../types/process'
import type { NutritionInfo, RecipeAssetImportResponse, RecipeAssetRef } from '../../../types/recipe'
import { remapActionOnProcessRefs } from '../model/subprocessReferences'
import { flowFileName, summarizeFlow, toFlowDoc, type FlowDoc, type FlowSummary } from '../model/recipeFlowFile.ts'
import { importRecipeFlow } from './persistence/importRecipeFlow.ts'

export type RecipeFileDetails = {
  name: string
  description?: string | null
  preparationNotes?: string | null
  nutrition?: NutritionInfo | null
}

/**
 * The recipe as a file: its processes as they are now in the editor, its details, and links to its step
 * images and to the narration audio that is ready. A process whose narration can't be read is exported
 * without audio rather than failing the export.
 */
export async function buildRecipeFile(recipeId: number, details: RecipeFileDetails, processes: Process[]): Promise<FlowDoc> {
  const audio = (await Promise.all(processes
    // A process not saved yet has no narration to list.
    .filter((process) => process.id > 0)
    .map(async (process) => {
      try {
        const narrations = await StepNarrationApi.list(recipeId, process.id)
        return narrations
          .filter((narration) => narration.status === 'READY' && narration.audioUrl)
          .map((narration) => ({ processId: process.id, stepId: narration.stepId, url: narration.audioUrl! }))
      } catch {
        return []
      }
    }))).flat()
  return toFlowDoc({
    processes,
    remapNodes: remapActionOnProcessRefs,
    name: details.name,
    description: details.description ?? undefined,
    preparationNotes: details.preparationNotes,
    nutrition: details.nutrition ? { ...details.nutrition } : null,
    audio,
  })
}

export type ExportedRecipeFile = { json: string; fileName: string; summary: FlowSummary }

/** The recipe file of a recipe as it is now, ready to download: its details are read fresh from the server. */
export async function exportRecipeFile(recipeId: number, processes: Process[]): Promise<ExportedRecipeFile> {
  const detail = await RecipeDetailApi.getRecipeDetail(recipeId)
  const doc = await buildRecipeFile(recipeId, detail, processes)
  return { json: JSON.stringify(doc, null, 2), fileName: flowFileName(detail.name), summary: summarizeFlow(doc) }
}

/** Hands a text file to the browser to save. */
export function downloadTextFile(content: string, fileName: string, mimeType = 'application/json') {
  const url = URL.createObjectURL(new Blob([content], { type: mimeType }))
  const link = document.createElement('a')
  link.href = url
  link.download = fileName
  link.click()
  URL.revokeObjectURL(url)
}

export type ImportedRecipe = {
  recipeId: number
  /** How many linked images and audio files the file had. */
  linked: { images: number; audio: number }
  /** What the server copied; null when the file linked nothing or the copy could not be attempted. */
  assets: RecipeAssetImportResponse | null
}

/**
 * Creates a recipe in the signed-in user's account from a recipe file: the recipe, then its steps (no AI
 * call), then its own copies of the linked images and audio.
 * <p>
 * Throws when the recipe or its steps can't be created; a recipe created before its steps failed is deleted
 * again, so a failed import leaves nothing behind. Notes, nutrition, images and audio are best effort: the
 * recipe is kept without whichever could not be brought over.
 */
export async function createRecipeFromFile(doc: FlowDoc, name: string, description: string): Promise<ImportedRecipe> {
  const created = await RecipeApi.createRecipe({ name, description })
  if (!created.id) throw new Error('Recipe created but no id returned')
  const recipeId = created.id

  let processIds: Map<number, number>
  try {
    ({ processIds } = await importRecipeFlow(recipeId, doc, {
      createMainProcess: () => RecipeDetailApi.createMainProcess(recipeId),
      createProcess: (request) => ProcessApi.create(recipeId, request),
      updateAll: (items, baseRevision) => ProcessApi.updateAll(recipeId, items, baseRevision),
      deleteProcess: (processId) => ProcessApi.delete(recipeId, processId),
      loadRevision: async () => (await RecipeDetailApi.getRecipeDetail(recipeId)).processRevision ?? 0,
      listProcesses: () => ProcessApi.listByRecipe(recipeId),
    }, remapActionOnProcessRefs))
  } catch (error) {
    await RecipeApi.deleteRecipe(recipeId).catch(() => {})
    throw error
  }

  await Promise.all([
    doc.preparationNotes ? RecipeApi.updatePreparationNotes(recipeId, doc.preparationNotes).catch(() => {}) : null,
    doc.nutrition ? RecipeDetailApi.updateNutrition(recipeId, doc.nutrition as NutritionInfo).catch(() => {}) : null,
  ])

  const toRefs = <T extends { process: number; stepId: string; url: string; prompt?: string }>(entries: T[]): RecipeAssetRef[] =>
    entries.flatMap((entry) => {
      const processId = processIds.get(entry.process)
      return processId === undefined ? [] : [{ processId, stepId: entry.stepId, url: entry.url, ...(entry.prompt ? { prompt: entry.prompt } : {}) }]
    })
  const images = toRefs(doc.assets?.images ?? [])
  const audio = toRefs(doc.assets?.audio ?? [])
  const linked = { images: doc.assets?.images.length ?? 0, audio: doc.assets?.audio.length ?? 0 }
  if (images.length === 0 && audio.length === 0) return { recipeId, linked, assets: null }
  try {
    return { recipeId, linked, assets: await RecipeDetailApi.importAssets(recipeId, { images, audio }) }
  } catch {
    return { recipeId, linked, assets: null }
  }
}

const plural = (count: number, noun: string) => `${count} ${noun}${count === 1 ? '' : 's'}`

/**
 * What to tell the user after an import: null when everything the file linked was copied, else what is
 * missing (those steps are simply left without an image or narration).
 */
export function describeMissingAssets(imported: ImportedRecipe): string | null {
  const missingImages = imported.linked.images - (imported.assets?.images.copied ?? 0)
  const missingAudio = imported.linked.audio - (imported.assets?.audio.copied ?? 0)
  const parts = [
    missingImages > 0 ? plural(missingImages, 'image') : '',
    missingAudio > 0 ? plural(missingAudio, 'narration') : '',
  ].filter(Boolean)
  if (parts.length === 0) return null
  return `${parts.join(' and ')} could not be copied. You can generate ${missingImages + missingAudio === 1 ? 'it' : 'them'} from the editor.`
}
