/** Text → speech: narrate every step of every saved process that has steps, then poll until done. */

import type { ApiClient } from '../api'
import type { Process } from '../../../../frontEnd/src/types/process'
import type { SpeechStage, SpeechStep } from '../types'
import { pollUntil } from './poll'
import { hasSteps } from './steps'

type NarrationDTO = {
  stepId: string
  status: 'NOT_GENERATED' | 'GENERATING' | 'READY' | 'STALE' | 'FAILED'
  narratable: boolean
  text?: string
  audioUrl?: string | null
  durationSeconds?: number | null
  provider?: string | null
  modelKey?: string | null
  voice?: string | null
  failureReason?: string | null
}

const TERMINAL = new Set(['READY', 'FAILED'])
/** A dead generation reads as NOT_GENERATED; asking again takes it over (StepNarrationService). */
const MAX_RE_ENSURES = 3

export async function runNarration(
  api: ApiClient, recipeId: number, processes: Process[], requestedModel: string, timeoutSeconds: number,
): Promise<SpeechStage> {
  const stage: SpeechStage = {
    status: 'failed', requestedModel, resolvedModels: [], usedFallback: false, clientMs: 0,
    narratable: 0, ready: 0, failed: 0, audioSeconds: 0, steps: [],
  }
  const started = Date.now()
  const errors: string[] = []
  const targets = processes.filter(hasSteps)
  if (targets.length === 0) {
    stage.status = 'skipped'
    stage.error = 'no steps to narrate'
    return stage
  }

  await Promise.all(targets.map(async (process) => {
    const base = `/api/v1/recipes/${recipeId}/processes/${process.id}/narrations`
    let reEnsures = 0
    try {
      await api.post<NarrationDTO[]>(base, {})
      const final = await pollUntil(
        () => api.get<NarrationDTO[]>(base),
        (items) => items.filter((item) => item.narratable).every((item) => TERMINAL.has(item.status)),
        timeoutSeconds,
        `narration of process ${process.id}`,
        async (items) => {
          const orphaned = items.some((item) => item.narratable && (item.status === 'NOT_GENERATED' || item.status === 'STALE'))
          if (orphaned && reEnsures < MAX_RE_ENSURES) {
            reEnsures++
            await api.post<NarrationDTO[]>(base, {})
          }
        },
      )
      for (const item of final.filter((entry) => entry.narratable)) {
        const entry: SpeechStep = {
          processId: process.id, stepId: item.stepId, status: item.status, text: item.text,
          audioUrl: item.audioUrl, durationSeconds: item.durationSeconds, provider: item.provider,
          modelKey: item.modelKey, voice: item.voice, failureReason: item.failureReason,
        }
        stage.steps.push(entry)
        stage.narratable++
        if (item.status === 'READY') {
          stage.ready++
          stage.audioSeconds += item.durationSeconds ?? 0
        } else {
          stage.failed++
        }
      }
    } catch (error) {
      errors.push(`process ${process.id}: ${(error as Error).message}`)
    }
  }))

  stage.clientMs = Date.now() - started
  stage.audioSeconds = Math.round(stage.audioSeconds * 10) / 10
  stage.resolvedModels = [...new Set(stage.steps.map((step) => step.modelKey).filter((key): key is string => !!key))]
  stage.usedFallback = stage.resolvedModels.some((key) => key !== requestedModel)
  if (errors.length > 0) stage.error = errors.join('; ')
  stage.status = stage.ready === 0 ? 'failed' : stage.failed > 0 || errors.length > 0 ? 'partial' : 'ok'
  return stage
}
