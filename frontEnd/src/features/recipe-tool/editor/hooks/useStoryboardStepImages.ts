import { useEffect, useRef } from 'react'
import type { RecipeStoryboardStep } from '../../../../types/recipe'
import { isTrackedJobActive, storyboardJobKey } from '../../jobs/jobTracker'
import { useTrackedJob } from '../../jobs/useJobTracker'
import { storyboardStepChanges } from '../../model/storyboardView'

/**
 * Hands the open canvas the step images a storyboard changed in its process (a panel image put on a step, or an
 * earlier storyboard's image removed), as soon as the storyboard finishes. The canvas keeps its own copy of the
 * nodes, so without this it would go on showing (and later saving) the nodes as they were before.
 *
 * Only a storyboard that finishes while this canvas is open is applied: one that was already finished when the
 * canvas opened is in the nodes the canvas started from, and applying it again could undo newer step images.
 */
export function useStoryboardStepImages({ recipeId, processId, applySteps }: {
  recipeId: number
  processId: number
  applySteps: (steps: RecipeStoryboardStep[]) => void
}) {
  const tracked = useTrackedJob(storyboardJobKey(recipeId))
  const handledRef = useRef(new Set<string>())
  const seenJobsRef = useRef(new Set<string>())

  useEffect(() => {
    if (!tracked) return
    const { job } = tracked
    const changes = storyboardStepChanges(job).filter((step) => step.processId === processId)
    const keyOf = (step: RecipeStoryboardStep) => `${job.jobId}:${step.stepId}`
    if (!seenJobsRef.current.has(job.jobId)) {
      seenJobsRef.current.add(job.jobId)
      if (!isTrackedJobActive(tracked)) {
        changes.forEach((step) => handledRef.current.add(keyOf(step)))
        return
      }
    }
    const fresh = changes.filter((step) => !handledRef.current.has(keyOf(step)))
    if (fresh.length === 0) return
    applySteps(fresh)
    fresh.forEach((step) => handledRef.current.add(keyOf(step)))
  }, [tracked, processId, applySteps])
}
