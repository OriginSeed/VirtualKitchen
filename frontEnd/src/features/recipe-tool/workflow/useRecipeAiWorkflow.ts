import { useCallback, useState } from 'react'
import { httpStatusOf } from '../../../api'
import { RecipeAiWorkflowApi } from '../../../api/recipeAiWorkflowApi'
import type { RecipeAiTaskType, RecipeAiWorkflowCreateRequest } from '../../../types/recipeAiWorkflow'
import { useRecipeSession } from '../session/sessionContext'
import { workflowJobKey } from '../jobs/jobTracker'
import { useJobTracker, useTrackedJob } from '../jobs/useJobTracker'
import { errorMessage } from '../../../shared/errors'
import type { RunModels } from './model/workflowSelection'

export type WorkflowAction = 'start' | 'approve' | 'retry' | 'discard' | 'dismiss'

/**
 * AI Recipe Creation for one recipe: the workflow's live state (followed by the app-level
 * JobTracker, so it survives closing the modal, navigating and reloading) plus its actions.
 *
 * Approval is where the editor and the workflow meet: the session is saved first, and the revision
 * that save produced goes with the approval, so the visuals and narration are created from exactly
 * the recipe process the user approved — never from the original AI output or an older save.
 */
export function useRecipeAiWorkflow(recipeId: number) {
  const tracker = useJobTracker()
  const session = useRecipeSession()
  const tracked = useTrackedJob(workflowJobKey(recipeId))
  const [busy, setBusy] = useState<WorkflowAction | null>(null)
  const [error, setError] = useState<string | null>(null)

  const run = useCallback(async (action: WorkflowAction, work: () => Promise<void>) => {
    setBusy(action)
    setError(null)
    try {
      await work()
      return true
    } catch (caught) {
      setError(errorMessage(caught, 'Something went wrong. Please try again.'))
      return false
    } finally {
      setBusy(null)
    }
  }, [])

  const start = useCallback((request: RecipeAiWorkflowCreateRequest) => run('start', async () => {
    await tracker.startWorkflow(recipeId, request)
  }), [run, tracker, recipeId])

  /**
   * `modelKeys`: the models picked at approval ('' = account setting); omitted keeps the run's choices.
   * `overwriteExisting`: replace what the steps already have; omitted keeps the run's choice.
   */
  const approve = useCallback((modelKeys?: RunModels, overwriteExisting?: boolean) => run('approve', async () => {
    const workflow = tracked?.job
    if (!workflow || !session) return
    // Approval is a decision about what the user sees now: make sure that is what the backend holds.
    const saved = await session.saveNow()
    if (!saved.ok) {
      throw new Error(`Your latest changes couldn't be saved, so nothing was started. ${saved.error ?? 'Please try again.'}`)
    }
    try {
      tracker.applyWorkflow(recipeId, await RecipeAiWorkflowApi.approve(recipeId, workflow.workflowId, session.getPersistedRevision(), modelKeys, overwriteExisting))
    } catch (caught) {
      if (httpStatusOf(caught) === 409) {
        // Typically an edit that saved in between: the user's next click approves that version.
        tracker.refresh(workflowJobKey(recipeId))
        throw new Error(errorMessage(caught, 'The recipe changed while approving. Please approve again.'), { cause: caught })
      }
      throw caught
    }
  }), [run, tracked, session, tracker, recipeId])

  /** `modelKeys`: the models picked for the retry ('' = account setting); omitted keeps the run's choices. */
  const retry = useCallback((task: RecipeAiTaskType, modelKeys?: RunModels) => run('retry', async () => {
    const workflow = tracked?.job
    if (!workflow) return
    tracker.applyWorkflow(recipeId, await RecipeAiWorkflowApi.retryTask(recipeId, workflow.workflowId, task, modelKeys))
  }), [run, tracked, tracker, recipeId])

  const discard = useCallback(() => run('discard', async () => {
    const workflow = tracked?.job
    if (!workflow) return
    await RecipeAiWorkflowApi.discard(recipeId, workflow.workflowId)
    tracker.dismiss(workflowJobKey(recipeId))
  }), [run, tracked, tracker, recipeId])

  /** Closes a finished workflow's summary for good (it isn't offered again on reload). */
  const dismiss = useCallback(() => run('dismiss', async () => {
    const workflow = tracked?.job
    if (!workflow) return
    tracker.dismiss(workflowJobKey(recipeId))
    await RecipeAiWorkflowApi.dismiss(recipeId, workflow.workflowId)
  }), [run, tracked, tracker, recipeId])

  return {
    workflow: tracked?.job ?? null,
    connectionLost: tracked?.connectionLost ?? false,
    pollError: tracked?.pollError ?? null,
    busy,
    error,
    clearError: () => setError(null),
    start,
    approve,
    retry,
    discard,
    dismiss,
  }
}
