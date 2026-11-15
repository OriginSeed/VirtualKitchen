/**
 * Text → text: start a recipe process generation job, poll it, then load the result exactly like
 * the editor does (the frontend's own converter) and save it through the editor's own save path.
 */

import { randomUUID } from 'node:crypto'
import type { ApiClient } from '../api'
import type { Process } from '../../../../frontEnd/src/types/process'
import type { RecipeProcessGenerationJobResponse, RecipeProcessGenerationResult } from '../../../../frontEnd/src/types/recipe'
import { convertGeneratedResultToProcesses } from '../../../../frontEnd/src/features/recipe-tool/editor/adapters/recipeProcessGenerationConverter'
import { createPersistedState, persistSnapshot, type PersistenceApi } from '../../../../frontEnd/src/features/recipe-tool/session/persistence/snapshotPersistence'
import { remapActionOnProcessRefs } from '../../../../frontEnd/src/features/recipe-tool/model/subprocessReferences'
import type { TextStage } from '../types'
import { pollUntil } from './poll'

const TERMINAL = new Set(['COMPLETED', 'FAILED'])

export type GenerationOutput = { stage: TextStage; result?: RecipeProcessGenerationResult }

export async function runGeneration(
  api: ApiClient, recipeId: number, recipeText: string, requestedModel: string, timeoutSeconds: number,
): Promise<GenerationOutput> {
  const base = `/api/v1/recipes/${recipeId}/processes/generate/jobs`
  const stage: TextStage = {
    status: 'failed', requestedModel, resolvedModels: [], usedFallback: false, clientMs: 0,
    stageMs: {}, warnings: [], repairs: [],
  }
  const started = Date.now()
  let lastStage: string | null = null
  let lastAt = started
  const track = (current: string) => {
    const now = Date.now()
    if (lastStage) stage.stageMs[lastStage] = (stage.stageMs[lastStage] ?? 0) + (now - lastAt)
    lastStage = current
    lastAt = now
  }

  try {
    const job = await api.post<RecipeProcessGenerationJobResponse>(base, {
      recipeText, clientRequestId: randomUUID(), mode: 'CREATE',
    })
    stage.jobId = job.jobId
    track(job.stage)
    const final = TERMINAL.has(job.status) ? job : await pollUntil(
      () => api.get<RecipeProcessGenerationJobResponse>(`${base}/${job.jobId}`),
      (value) => TERMINAL.has(value.status),
      timeoutSeconds,
      'process generation',
      (value) => { if (value.stage !== lastStage) track(value.stage) },
    )
    track('done')
    stage.clientMs = Date.now() - started
    if (final.status !== 'COMPLETED' || !final.result) {
      stage.error = final.errorMessage ?? `job ended ${final.status}`
      return { stage }
    }
    const result = final.result
    stage.status = 'ok'
    stage.resolvedModels = result.modelUsed ? [result.modelUsed] : []
    stage.usedFallback = !!result.usedFallback || (!!result.modelUsed && result.modelUsed !== requestedModel)
    stage.fallbackReason = result.fallbackReason
    stage.warnings = result.warnings ?? []
    stage.repairs = result.repairs ?? []
    return { stage, result }
  } catch (error) {
    stage.clientMs = Date.now() - started
    stage.error = (error as Error).message
    return { stage }
  }
}

type RecipeDetail = { processRevision?: number | null; mainProcessId?: number | null }

/** The editor's save calls for one recipe, as `persistSnapshot` needs them. */
export const persistenceApi = (api: ApiClient, recipeId: number): PersistenceApi => {
  const processesPath = `/api/v1/recipes/${recipeId}/processes`
  return {
    createMainProcess: () => api.post<Process>(`/api/v1/recipes/${recipeId}/main-process`),
    createProcess: (request) => api.post<Process>(processesPath, request),
    updateAll: (items, baseRevision) => api.put<{ revision: number }>(processesPath, { processes: items, baseRevision }),
    deleteProcess: (processId) => api.delete<void>(`${processesPath}/${processId}`),
  }
}

/**
 * Converts the generation result and saves it as the recipe's processes, the way the editor's
 * autosave would. Returns the saved processes (real ids) and the converter's problems.
 */
export async function saveGeneratedProcesses(
  api: ApiClient, recipeId: number, jobId: string, result: RecipeProcessGenerationResult, stage: TextStage,
): Promise<{ processes: Process[]; problems: string[] }> {
  const processesPath = `/api/v1/recipes/${recipeId}/processes`
  const detail = await api.get<RecipeDetail>(`/api/v1/recipes/${recipeId}`)
  const existing = await api.get<Process[]>(processesPath)
  const existingMain = existing.find((process) => process.type === 'MAIN'
    && (detail.mainProcessId == null || process.id === detail.mainProcessId)) ?? null

  const convertStarted = Date.now()
  const converted = convertGeneratedResultToProcesses(recipeId, result, existingMain, existing.map((process) => process.id), null)
  stage.convertMs = Date.now() - convertStarted
  if (converted.problems.length > 0) return { processes: [], problems: converted.problems }

  const saveStarted = Date.now()
  const state = createPersistedState(existing, detail.processRevision ?? 0)
  await persistSnapshot(converted.processes, state, persistenceApi(api, recipeId), remapActionOnProcessRefs, () => {})
  await api.post<void>(`/api/v1/recipes/${recipeId}/processes/generate/jobs/${jobId}/applied`)
  stage.saveMs = Date.now() - saveStarted

  return { processes: await api.get<Process[]>(processesPath), problems: [] }
}
