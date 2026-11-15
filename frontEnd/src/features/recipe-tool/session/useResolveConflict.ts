import { useCallback } from 'react'
import { useConfirm } from '../../../shared/components/confirm/confirmContext'
import { useNotifications } from '../../../shared/components/notifications/notificationContext'
import { errorMessage } from '../../../shared/errors'
import { useRecipeSession } from './sessionContext'

/**
 * The two ways out of a save conflict (the recipe changed on another tab or device): load the latest saved version
 * (after confirming the local changes will be lost), or keep this version and save it over the newer one.
 */
export function useResolveConflict() {
  const session = useRecipeSession()
  const confirm = useConfirm()
  const { notifyError } = useNotifications()

  return useCallback(async (choice: 'reload' | 'keepMine') => {
    if (!session) return
    if (choice === 'reload' && !(await confirm({
      eyebrow: 'Discard changes',
      title: 'Discard your unsaved changes?',
      message: 'Every change made here since the last save will be lost, and the latest saved version of this recipe is loaded instead. This can\'t be undone.',
      confirmLabel: 'Discard & reload',
    }))) return
    session.resolveConflict(choice).catch((error) => {
      notifyError(errorMessage(error, 'Unable to resolve the conflict right now'))
    })
  }, [session, confirm, notifyError])
}
