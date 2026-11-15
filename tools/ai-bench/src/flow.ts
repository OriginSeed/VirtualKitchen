/**
 * Turning saved processes into a flow file and a flow back into a recipe, through the editor's own
 * save path — so a recipe built from a flow is exactly what saving it in the app would produce, with
 * no AI call. The file format and the import are the app's own (recipeFlowFile.ts, importRecipeFlow.ts).
 */

import type { ApiClient } from './api'
import type { Process } from '../../../frontEnd/src/types/process'
import { remapActionOnProcessRefs } from '../../../frontEnd/src/features/recipe-tool/model/subprocessReferences'
import { toFlowDoc as buildFlowDoc, type FlowDoc } from '../../../frontEnd/src/features/recipe-tool/model/recipeFlowFile.ts'
import { importRecipeFlow } from '../../../frontEnd/src/features/recipe-tool/session/persistence/importRecipeFlow.ts'
import { persistenceApi } from './stages/generate'

/**
 * The flow of a recipe as it was saved: its processes with temporary ids. Images are left out: a bench
 * flow is copied into runs that each draw their own.
 */
export function toFlowDoc(
  recipe: string, recipeText: string, textModel: string | null, processes: Process[], warnings: string[], repairs: string[],
): FlowDoc {
  return buildFlowDoc({
    processes, remapNodes: remapActionOnProcessRefs, includeImages: false, recipe, recipeText, textModel, warnings, repairs,
  })
}

type RecipeDetail = { processRevision?: number | null }

/**
 * Builds the flow into an existing (fresh) recipe and returns its saved processes, with real ids.
 * Step ids are kept, so the same step has the same id in every recipe built from one flow.
 */
export async function importFlow(api: ApiClient, recipeId: number, flow: FlowDoc): Promise<Process[]> {
  const processesPath = `/api/v1/recipes/${recipeId}/processes`
  await importRecipeFlow(recipeId, flow, {
    ...persistenceApi(api, recipeId),
    loadRevision: async () => (await api.get<RecipeDetail>(`/api/v1/recipes/${recipeId}`)).processRevision ?? 0,
    listProcesses: () => api.get<Process[]>(processesPath),
  }, remapActionOnProcessRefs)
  return api.get<Process[]>(processesPath)
}

/** Creates a recipe in the app from a flow file. */
export async function createRecipeFromFlow(api: ApiClient, flow: FlowDoc, name: string): Promise<{ recipeId: number; processes: Process[] }> {
  const created = await api.post<{ id: number }>('/api/v1/process-templates', {
    name: name.slice(0, 120),
    description: 'Imported by tools/ai-bench',
  })
  return { recipeId: created.id, processes: await importFlow(api, created.id, flow) }
}
