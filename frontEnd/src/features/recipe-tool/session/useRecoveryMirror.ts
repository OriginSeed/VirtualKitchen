import { useCallback, useEffect, useRef } from 'react'
import { useNotifications } from '../../../shared/components/notifications/notificationContext'
import { clearRecovery, writeRecovery, type StorageLike } from './persistence/recoveryStore'
import type { SaveCoordinator } from './persistence/saveCoordinator'
import type { SessionStore } from './persistence/sessionStore'

/** Unsaved edits reach the local recovery copy at most this long after they happen. */
const RECOVERY_WRITE_THROTTLE_MS = 500

/**
 * Crash recovery for an editing session: mirrors unsaved edits into localStorage (throttled), drops the
 * copy once a save confirms them, keeps it on tab close/refresh and warns when leaving with unsaved
 * changes, and saves what's pending when the session unmounts. Readers (`canEdit` false) keep no copy.
 *
 * Returns `noteChange`, to call after every edit (it also schedules the autosave).
 */
export function useRecoveryMirror({ recipeId, canEdit, coordinator, store, storage }: {
  recipeId: number
  canEdit: boolean
  coordinator: SaveCoordinator
  store: SessionStore
  storage: StorageLike | null
}) {
  const recoveryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const recoveryWarnedRef = useRef(false)
  const { notifyInfo } = useNotifications()

  /** Writes the current unsaved session to localStorage (synchronously — also used on pagehide/unmount). */
  const writeRecoveryNow = useCallback(() => {
    if (recoveryTimerRef.current != null) clearTimeout(recoveryTimerRef.current)
    recoveryTimerRef.current = null
    if (!canEdit || !coordinator.hasPendingChanges()) return
    const result = writeRecovery(storage, {
      recipeId,
      writtenAt: Date.now(),
      baseRevision: store.persisted.revision,
      persistedIds: Array.from(store.persisted.processes.keys()),
      processes: store.processes,
    })
    if (!result.ok && !recoveryWarnedRef.current) {
      recoveryWarnedRef.current = true
      console.warn('Unable to keep a local recovery copy of unsaved recipe changes:', result.error)
      // Autosave still runs; only the crash-safety net is missing, so say so once rather than alarm.
      notifyInfo('This browser isn’t letting us keep a backup copy of unsaved edits. Autosave still works, but save before closing the tab.')
    }
  }, [canEdit, coordinator, store, storage, recipeId, notifyInfo])

  /** Throttled, so continuous typing still reaches the recovery copy every half second at most. */
  const noteChange = useCallback(() => {
    coordinator.markChanged()
    if (canEdit && recoveryTimerRef.current == null) {
      recoveryTimerRef.current = setTimeout(writeRecoveryNow, RECOVERY_WRITE_THROTTLE_MS)
    }
  }, [coordinator, canEdit, writeRecoveryNow])

  // Once a save confirms everything, the recovery copy is stale — drop it; if edits arrived during
  // that save, rewrite it instead (its base revision just moved on).
  // (A save's success is announced while its request is still settling, so "everything saved" is
  // only known once the status itself reaches `saved`.)
  const handledSaveRef = useRef<number | null>(null)
  useEffect(() => {
    handledSaveRef.current = coordinator.getState().lastSavedAt
    return coordinator.subscribe(() => {
      const { status, lastSavedAt } = coordinator.getState()
      if (lastSavedAt == null || lastSavedAt === handledSaveRef.current) return
      if (status === 'saving') return // wait for this save to settle
      handledSaveRef.current = lastSavedAt
      if (status === 'saved') {
        if (recoveryTimerRef.current != null) clearTimeout(recoveryTimerRef.current)
        recoveryTimerRef.current = null
        clearRecovery(storage, recipeId)
      } else {
        writeRecoveryNow()
      }
    })
  }, [coordinator, writeRecoveryNow, storage, recipeId])

  // Tab close / refresh / crash-adjacent exits: keep the unsaved work locally, warn when leaving
  // with changes not yet saved, and retry a failed save as soon as the network is back.
  useEffect(() => {
    if (!canEdit) return undefined
    const onPageHide = () => writeRecoveryNow()
    const onBeforeUnload = (event: BeforeUnloadEvent) => {
      if (!coordinator.hasPendingChanges()) return
      writeRecoveryNow()
      event.preventDefault()
    }
    const onOnline = () => coordinator.retryNow()
    window.addEventListener('pagehide', onPageHide)
    window.addEventListener('beforeunload', onBeforeUnload)
    window.addEventListener('online', onOnline)
    return () => {
      window.removeEventListener('pagehide', onPageHide)
      window.removeEventListener('beforeunload', onBeforeUnload)
      window.removeEventListener('online', onOnline)
    }
  }, [canEdit, coordinator, writeRecoveryNow])

  // Leaving the recipe inside the app (navbar Back, another route): save what's pending right
  // away; the recovery copy stays until that save is confirmed.
  useEffect(() => () => {
    writeRecoveryNow()
    const savedAtBefore = coordinator.getState().lastSavedAt
    void coordinator.flush()
      .then((ok) => {
        // Only clear when *this* flush actually saved something — StrictMode's simulated unmount
        // must not wipe a recovery copy nobody has been offered yet.
        if (ok && coordinator.getState().lastSavedAt !== savedAtBefore && !coordinator.hasPendingChanges()) {
          clearRecovery(storage, recipeId)
        }
      })
      .finally(() => coordinator.dispose())
  }, [coordinator, writeRecoveryNow, storage, recipeId])

  return { noteChange }
}
