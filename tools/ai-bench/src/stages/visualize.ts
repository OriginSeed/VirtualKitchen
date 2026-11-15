/** Text → image: one visualization job per saved process that has steps, run side by side. */

import type { ApiClient } from '../api'
import type { Process } from '../../../../frontEnd/src/types/process'
import type { RecipeProcessVisualizationJobResponse } from '../../../../frontEnd/src/types/recipe'
import type { ImageStage, ImageStep } from '../types'
import { pollUntil } from './poll'
import { hasSteps } from './steps'

const TERMINAL = new Set(['COMPLETED', 'COMPLETED_WITH_ERRORS', 'FAILED'])

export async function runVisualization(
  api: ApiClient, recipeId: number, processes: Process[], requestedModel: string, timeoutSeconds: number,
): Promise<ImageStage> {
  const stage: ImageStage = {
    status: 'failed', requestedModel, resolvedModels: [], usedFallback: false, clientMs: 0,
    totalSteps: 0, succeeded: 0, failed: 0, steps: [],
  }
  const started = Date.now()
  const errors: string[] = []
  const targets = processes.filter(hasSteps)
  if (targets.length === 0) {
    stage.status = 'skipped'
    stage.error = 'no steps to visualize'
    return stage
  }

  await Promise.all(targets.map(async (process) => {
    const base = `/api/v1/recipes/${recipeId}/processes/${process.id}/visualization/jobs`
    try {
      const job = await api.post<RecipeProcessVisualizationJobResponse>(base)
      const final = TERMINAL.has(job.status) ? job : await pollUntil(
        () => api.get<RecipeProcessVisualizationJobResponse>(`${base}/${job.jobId}`),
        (value) => TERMINAL.has(value.status),
        timeoutSeconds,
        `visualization of process ${process.id}`,
      )
      stage.totalSteps += final.totalSteps
      for (const step of final.steps ?? []) {
        const entry: ImageStep = {
          processId: process.id, stepId: step.stepId, imageUrl: step.imageUrl, modelKey: step.modelKey,
          usedFallback: step.usedFallback, error: step.errorMessage,
        }
        stage.steps.push(entry)
        if (step.success && step.imageUrl) stage.succeeded++
        else stage.failed++
      }
      if (final.status === 'FAILED') errors.push(final.errorMessage ?? `process ${process.id}: job FAILED`)
    } catch (error) {
      errors.push(`process ${process.id}: ${(error as Error).message}`)
    }
  }))

  stage.clientMs = Date.now() - started
  stage.resolvedModels = [...new Set(stage.steps.map((step) => step.modelKey).filter((key): key is string => !!key))]
  stage.usedFallback = stage.steps.some((step) => step.usedFallback || (step.modelKey && step.modelKey !== requestedModel))
  stage.failed += Math.max(0, stage.totalSteps - stage.steps.length)
  if (errors.length > 0) stage.error = errors.join('; ')
  stage.status = stage.succeeded === 0 ? 'failed' : stage.failed > 0 || errors.length > 0 ? 'partial' : 'ok'
  return stage
}
