import { useCallback, useMemo } from 'react'
import { resolveStepActionId } from '../../../shared/catalog/actionCatalog'
import { useConfirm } from '../../../shared/components/confirm/confirmContext'
import { useNotifications } from '../../../shared/components/notifications/notificationContext'
import { evaluateRecipeReadiness, type ReadinessIssue, type RecipeReadiness } from '../model/recipeReadiness'
import { useRecipeSession } from './sessionContext'

/** A catalog action id exactly as stored (an alias or unknown id resolves to something else, or nothing). */
const isKnownAction = (actionId: string) => resolveStepActionId(actionId) === actionId

/** Readiness of the session's current content (including unsaved edits), recomputed on every change; null while loading. */
export function useRecipeReadiness(): RecipeReadiness | null {
  const session = useRecipeSession()
  const version = session?.version
  const loading = session?.loading ?? true
  return useMemo(
    () => (session && !loading ? evaluateRecipeReadiness(session.getProcesses(), { isKnownAction }) : null),
    // `version` is what changes on every edit; the session object itself is read through its getters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [version, loading],
  )
}

/**
 * Guards a whole-recipe operation (ordering, cook mode): saves first (these operations read the saved recipe),
 * then refuses while the recipe has blockers, explaining them and offering to show the first one. Warnings
 * never block. Resolves true when the operation may go ahead.
 */
export function useReadinessGate(onShowIssue?: (issue: ReadinessIssue) => void) {
  const session = useRecipeSession()
  const confirm = useConfirm()
  const { notifyError } = useNotifications()

  return useCallback(async (operation: string): Promise<boolean> => {
    if (!session) return true
    if (session.canEdit) {
      const saved = await session.saveNow()
      if (!saved.ok) {
        notifyError(`Your latest changes couldn't be saved, so ${operation} didn't start. ${saved.error ?? 'Please try again.'}`)
        return false
      }
    }
    const readiness = evaluateRecipeReadiness(session.getProcesses(), { isKnownAction })
    if (readiness.ready) return true

    const { blockers } = readiness
    const listed = blockers.slice(0, 4).map((issue) => `• ${issue.message}`).join('\n')
    const more = blockers.length > 4 ? `\n…and ${blockers.length - 4} more.` : ''
    const showIt = await confirm({
      eyebrow: 'Recipe not ready',
      title: `Fix ${blockers.length === 1 ? 'this' : `these ${blockers.length}`} before ${operation}`,
      message: `${listed}${more}\n\nWarnings don't block anything; these do. The Readiness list in the editor shows them all.`,
      confirmLabel: onShowIssue ? 'Show me' : 'OK',
      cancelLabel: 'Close',
      tone: 'default',
    })
    if (showIt) onShowIssue?.(blockers[0])
    return false
  }, [session, confirm, notifyError, onShowIssue])
}

/**
 * Cook mode's gate: like `useReadinessGate`, but an unfinished recipe isn't a dead end — the cook can still walk
 * through what is there as a preview. Resolves 'cook' (ready), 'preview' (not ready, previewing anyway) or null.
 */
export function useCookModeGate() {
  const session = useRecipeSession()
  const confirm = useConfirm()
  const { notifyError } = useNotifications()

  return useCallback(async (): Promise<'cook' | 'preview' | null> => {
    if (!session) return null
    // Narration is generated from the saved steps, so pending edits are saved first.
    if (session.canEdit) {
      const saved = await session.saveNow()
      if (!saved.ok) {
        notifyError(`Your latest changes couldn't be saved, so cook mode didn't start. ${saved.error ?? 'Please try again.'}`)
        return null
      }
    }
    const readiness = evaluateRecipeReadiness(session.getProcesses(), { isKnownAction })
    if (readiness.ready) return 'cook'

    const { blockers } = readiness
    const listed = blockers.slice(0, 4).map((issue) => `• ${issue.message}`).join('\n')
    const more = blockers.length > 4 ? `\n…and ${blockers.length - 4} more.` : ''
    const fixWhere = session.canEdit
      ? 'The readiness badge in the header lists everything to fix.'
      : 'Its author still has to finish it.'
    const preview = await confirm({
      eyebrow: 'Recipe not finished',
      title: 'This recipe isn’t ready to cook yet',
      message: `${listed}${more}\n\nYou can still preview what is there, step by step. ${fixWhere}`,
      confirmLabel: 'Preview anyway',
      cancelLabel: 'Close',
      tone: 'default',
    })
    return preview ? 'preview' : null
  }, [session, confirm, notifyError])
}
