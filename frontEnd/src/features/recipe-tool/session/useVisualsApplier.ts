import { useEffect, useRef } from 'react'
import type { JobTracker } from '../jobs/jobTracker'
import { withRecipeStepVisualization } from '../model/recipeStepData'
import { storyboardStepChanges } from '../model/storyboardView'
import type { SessionStore } from './persistence/sessionStore'

/**
 * Applies step images made in the background (Generate Visuals results, and the panel images a storyboard put on
 * the steps) to the session as they arrive, whether or not that process's
 * canvas is open — so leaving a process mid-job (or coming back after a reload) still shows every image
 * generated so far, and a later save can't send nodes missing them. Not an edit: no history entry and no
 * autosave of its own (the job already stored these images on the backend); the next save simply carries
 * them along. Calls `onApplied` after changing the session. Idle while `enabled` is false (still loading).
 */
export function useVisualsApplier({ enabled, recipeId, jobTracker, store, onApplied }: {
  enabled: boolean
  recipeId: number
  jobTracker: JobTracker
  store: SessionStore
  onApplied: () => void
}) {
  const appliedStepsRef = useRef(new Set<string>())
  useEffect(() => {
    if (!enabled) return undefined
    const applyVisuals = () => {
      let changed = false
      for (const tracked of jobTracker.all()) {
        if (tracked.kind !== 'visuals' || tracked.recipeId !== recipeId) continue
        const fresh = tracked.job.steps.filter((step) =>
          step.success && step.visualizationAssetId != null && !appliedStepsRef.current.has(`${tracked.job.jobId}:${step.stepId}`))
        if (fresh.length === 0) continue

        const freshByStepId = new Map(fresh.map((step) => [step.stepId, step]))
        store.setProcesses(store.processes.map((process) => {
          if (process.id !== tracked.processId) return process
          return {
            ...process,
            nodes: process.nodes.map((node) => {
              const step = freshByStepId.get(node.id)
              if (!step || node.kind !== 'STEP') return node
              return {
                ...node,
                data: withRecipeStepVisualization(node.data, {
                  assetId: step.visualizationAssetId,
                  imageUrl: step.imageUrl ?? undefined,
                  status: 'generated',
                }) as unknown as Record<string, unknown>,
              }
            }),
          }
        }))
        fresh.forEach((step) => appliedStepsRef.current.add(`${tracked.job.jobId}:${step.stepId}`))
        changed = true
      }
      // A storyboard puts each panel's image on one step, in either process: same rule, one step list for the recipe.
      for (const tracked of jobTracker.all()) {
        if (tracked.kind !== 'storyboard' || tracked.recipeId !== recipeId) continue
        const fresh = storyboardStepChanges(tracked.job).filter((step) =>
          !appliedStepsRef.current.has(`${tracked.job.jobId}:${step.stepId}`))
        if (fresh.length === 0) continue

        const freshByStepId = new Map(fresh.map((step) => [step.stepId, step]))
        store.setProcesses(store.processes.map((process) => ({
          ...process,
          nodes: process.nodes.map((node) => {
            const step = freshByStepId.get(node.id)
            if (!step || node.kind !== 'STEP' || step.processId !== process.id) return node
            return {
              ...node,
              // A step an earlier storyboard had illustrated, and this one did not, has its image removed.
              data: withRecipeStepVisualization(node.data, step.cleared ? { status: 'not_generated' } : {
                assetId: step.visualizationAssetId ?? undefined,
                imagePrompt: step.imagePrompt ?? undefined,
                imageUrl: step.imageUrl ?? undefined,
                status: 'generated',
              }) as unknown as Record<string, unknown>,
            }
          }),
        })))
        fresh.forEach((step) => appliedStepsRef.current.add(`${tracked.job.jobId}:${step.stepId}`))
        changed = true
      }
      if (changed) onApplied()
    }
    applyVisuals()
    return jobTracker.subscribe(applyVisuals)
  }, [enabled, recipeId, jobTracker, store, onApplied])
}
