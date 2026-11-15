import { useCallback, useEffect, useMemo, useState, useSyncExternalStore } from 'react'
import type { ReactNode } from 'react'
import { ProcessApi, RecipeDetailApi, httpStatusOf } from '../../../api'
import type { Process } from '../../../types/process'
import { remapActionOnProcessRefs } from '../model/subprocessReferences'
import { useJobTracker } from '../jobs/useJobTracker'
import { SaveCoordinator } from './persistence/saveCoordinator'
import { SessionHistory } from './persistence/sessionHistory'
import { fingerprint, persistSnapshot, remapProcessIds, type PersistenceApi } from './persistence/snapshotPersistence'
import { browserStorage, clearRecovery, pruneRecoveries, readRecovery, type RecoverySnapshot } from './persistence/recoveryStore'
import { mergeRecoveredProcesses, restoreSnapshot } from './persistence/sessionSnapshot'
import { SessionStore } from './persistence/sessionStore'
import { RecipeSessionContext, type RecipeSessionContextValue } from './sessionContext'
import RecoveryPrompt from './RecoveryPrompt'
import { useRecoveryMirror } from './useRecoveryMirror'
import { useVisualsApplier } from './useVisualsApplier'
import { withoutStrayMainProcesses } from '../model/mainProcess'
import { errorMessage } from '../../../shared/errors'

/** Pause in editing after which the session autosaves. */
const AUTOSAVE_DEBOUNCE_MS = 1500
/** Recovery copies nobody came back for are dropped after this long. */
const RECOVERY_MAX_AGE_MS = 30 * 24 * 60 * 60 * 1000

type RecoveryOffer = { snapshot: RecoverySnapshot; backendChanged: boolean }

/** Provides one recipe's editing session (see RecipeSessionContextValue) to the editor and recipe view. */
export function RecipeSessionProvider({ recipeId, canEdit = true, children }: { recipeId: number; canEdit?: boolean; children: ReactNode }) {
  const [store] = useState(() => new SessionStore())
  const [history] = useState(() => new SessionHistory<Process[]>())
  const [loadNonce, setLoadNonce] = useState(0)
  const [loadResult, setLoadResult] = useState<{ key: string; error: string | null } | null>(null)
  const [version, setVersion] = useState(0)
  const [focusRequest, setFocusRequest] = useState<{ processId: number; token: number } | null>(null)
  const [historyFlags, setHistoryFlags] = useState({ canUndo: false, canRedo: false })
  const [recoveryOffer, setRecoveryOffer] = useState<RecoveryOffer | null>(null)
  const [storage] = useState(browserStorage)
  const jobTracker = useJobTracker()

  // Loading is derived from which load finished last, so a reload (another recipe, or "reload" after a
  // conflict) shows as loading at once without resetting state inside the effect.
  const loadKey = `${recipeId}:${canEdit}:${loadNonce}`
  const loading = loadResult?.key !== loadKey
  const loadError = loading ? null : loadResult.error

  const bumpVersion = useCallback(() => setVersion((v) => v + 1), [])
  const notifyReseed = useCallback(() => store.notifyReseed(), [store])
  const subscribeReseed = useCallback((listener: () => void) => store.subscribeReseed(listener), [store])

  const syncHistoryFlags = useCallback(() => {
    setHistoryFlags((flags) => (flags.canUndo === history.canUndo && flags.canRedo === history.canRedo
      ? flags
      : { canUndo: history.canUndo, canRedo: history.canRedo }))
  }, [history])

  /** A save created processes for real: swap their temporary ids everywhere the session keeps them. */
  const applyAssignedIds = useCallback((idMap: Map<number, number>) => {
    store.setProcesses(remapProcessIds(store.processes, idMap, remapActionOnProcessRefs))
    history.map((snapshot) => remapProcessIds(snapshot, idMap, remapActionOnProcessRefs), (id) => idMap.get(id) ?? id)
    store.addAliases(idMap)
    notifyReseed()
    bumpVersion()
  }, [store, history, notifyReseed, bumpVersion])

  const [coordinator] = useState(() => {
    const api: PersistenceApi = {
      createMainProcess: () => RecipeDetailApi.createMainProcess(recipeId),
      createProcess: (request) => ProcessApi.create(recipeId, request),
      updateAll: (items, baseRevision) => ProcessApi.updateAll(recipeId, items, baseRevision, { uiAction: 'editor.save' }),
      deleteProcess: async (processId) => {
        try {
          await ProcessApi.delete(recipeId, processId)
        } catch (error) {
          if (httpStatusOf(error) !== 404) throw error // already gone is what we wanted
        }
      },
    }
    return new SaveCoordinator({
      // Reads the session's latest content at call time; persistSnapshot validates/saves it exactly
      // like an explicit Save would — there is no separate, weaker autosave path.
      save: async () => {
        await persistSnapshot(store.processes, store.persisted, api, remapActionOnProcessRefs, applyAssignedIds)
      },
      statusOf: httpStatusOf,
      autosave: canEdit,
      debounceMs: AUTOSAVE_DEBOUNCE_MS,
    })
  })
  const saveState = useSyncExternalStore(coordinator.subscribe, coordinator.getState)

  const { noteChange } = useRecoveryMirror({ recipeId, canEdit, coordinator, store, storage })

  // --- loading -------------------------------------------------------------------------------

  useEffect(() => {
    let cancelled = false
    const load = async () => {
      // The revision is read *before* the processes: a save landing between the two reads can then
      // only cause a false conflict later, never a silent overwrite.
      const detail = canEdit ? await RecipeDetailApi.getRecipeDetail(recipeId) : null
      const revision = detail?.processRevision ?? 0
      // A MAIN process created twice at once is briefly duplicated on the backend; never keep the doomed copy.
      const processes = withoutStrayMainProcesses(await ProcessApi.listByRecipe(recipeId), detail?.mainProcessId)
      return { revision, processes }
    }
    load()
      .then(({ revision, processes }) => {
        if (cancelled) return
        store.load(processes, revision)
        history.clear()
        coordinator.reset()
        syncHistoryFlags()
        bumpVersion()
        notifyReseed()

        if (canEdit) {
          pruneRecoveries(storage, RECOVERY_MAX_AGE_MS)
          const recovered = readRecovery(storage, recipeId)
          if (recovered && fingerprint(recovered.processes) === store.persisted.fingerprint) {
            clearRecovery(storage, recipeId) // the backend already has exactly this
          } else if (recovered) {
            setRecoveryOffer({ snapshot: recovered, backendChanged: recovered.baseRevision !== revision })
          }
        }
        setLoadResult({ key: loadKey, error: null })
        // Re-attach to any generation/visuals job still running (or finished while the user was
        // away) — the visuals applier below then fills in the steps generated so far.
        void jobTracker.discover(recipeId)
      })
      .catch((error) => {
        if (!cancelled) setLoadResult({ key: loadKey, error: errorMessage(error, 'Unable to load this recipe\'s processes') })
      })
    return () => {
      cancelled = true
    }
  }, [loadKey, recipeId, canEdit, jobTracker, store, coordinator, history, storage, syncHistoryFlags, notifyReseed, bumpVersion])

  useVisualsApplier({ enabled: !loading, recipeId, jobTracker, store, onApplied: bumpVersion })

  // --- mutations -----------------------------------------------------------------------------

  const getProcesses = useCallback(() => store.processes, [store])
  const getProcess = useCallback((processId: number) => store.processes.find((process) => process.id === processId), [store])
  const getPersistedRevision = useCallback(() => store.persisted.revision, [store])
  const resolveProcessId = useCallback((processId: number) => store.resolveId(processId), [store])

  const updateProcess = useCallback<RecipeSessionContextValue['updateProcess']>((processId, patch) => {
    const current = store.processes
    const index = current.findIndex((process) => process.id === processId)
    if (index === -1) return

    const next = current.slice()
    next[index] = { ...current[index], ...patch }
    store.setProcesses(next)
    bumpVersion()
    noteChange()
  }, [store, noteChange, bumpVersion])

  const addProcess = useCallback<RecipeSessionContextValue['addProcess']>((process, options) => {
    if (options?.persisted) store.markPersisted(process)
    const current = store.processes
    const index = current.findIndex((existing) => existing.id === process.id)
    store.setProcesses(index === -1
      ? [...current, process]
      : current.map((existing, i) => (i === index ? process : existing)))
    if (index !== -1) notifyReseed()
    bumpVersion()
    noteChange()
  }, [store, noteChange, notifyReseed, bumpVersion])

  const removeProcess = useCallback((processId: number) => {
    store.setProcesses(store.processes.filter((process) => process.id !== processId))
    bumpVersion()
    noteChange()
  }, [store, noteChange, bumpVersion])

  // --- history -------------------------------------------------------------------------------

  const recordHistory = useCallback<RecipeSessionContextValue['recordHistory']>(({ before, ...options }) => {
    history.record(before ?? store.processes, options)
    syncHistoryFlags()
  }, [store, history, syncHistoryFlags])

  const restore = useCallback((entry: { snapshot: Process[]; focusProcessId: number | null } | null) => {
    if (!entry) return
    store.setProcesses(restoreSnapshot(entry.snapshot, store.processes))
    const focusProcessId = entry.focusProcessId
    if (focusProcessId != null) setFocusRequest((request) => ({ processId: focusProcessId, token: (request?.token ?? 0) + 1 }))
    syncHistoryFlags()
    notifyReseed()
    bumpVersion()
    noteChange()
  }, [store, syncHistoryFlags, noteChange, notifyReseed, bumpVersion])

  const undo = useCallback(() => restore(history.undo(store.processes)), [store, history, restore])
  const redo = useCallback(() => restore(history.redo(store.processes)), [store, history, restore])

  // --- saving / recovery / conflicts ------------------------------------------------------------

  const saveNow = useCallback(async () => {
    const ok = await coordinator.flush()
    return { ok, error: ok ? null : coordinator.getState().error }
  }, [coordinator])

  const resolveConflict = useCallback(async (choice: 'reload' | 'keepMine') => {
    if (choice === 'reload') {
      clearRecovery(storage, recipeId)
      coordinator.reset()
      setLoadNonce((nonce) => nonce + 1)
      return
    }
    // Rebase this session onto the recipe's current revision and save it over the newer one.
    const detail = await RecipeDetailApi.getRecipeDetail(recipeId)
    store.rebase(detail.processRevision ?? 0)
    coordinator.resolveConflict()
    await coordinator.flush()
  }, [coordinator, store, storage, recipeId])

  const recoverChanges = useCallback(() => {
    if (!recoveryOffer) return
    const { snapshot } = recoveryOffer
    // Undoable like any other operation: Undo returns to what the backend had.
    history.record(store.processes, { focusProcessId: null })
    // A recovered copy can hold a MAIN process the backend has since dropped as a duplicate; keep the backend's.
    const backendMainId = store.processes.find((process) => process.type === 'MAIN')?.id
    store.setProcesses(withoutStrayMainProcesses(mergeRecoveredProcesses(snapshot.processes, snapshot.persistedIds, store.processes), backendMainId))
    setRecoveryOffer(null)
    syncHistoryFlags()
    notifyReseed()
    bumpVersion()
    noteChange() // autosave persists it; the recovery copy is cleared once that's confirmed
  }, [store, recoveryOffer, history, syncHistoryFlags, noteChange, notifyReseed, bumpVersion])

  const discardRecovery = useCallback(() => {
    clearRecovery(storage, recipeId)
    setRecoveryOffer(null)
  }, [storage, recipeId])

  const value = useMemo<RecipeSessionContextValue>(() => ({
    recipeId,
    loading,
    loadError,
    canEdit,
    version,
    getProcesses,
    getProcess,
    anyDirty: saveState.status !== 'saved',
    updateProcess,
    addProcess,
    removeProcess,
    saveState,
    saveNow,
    getPersistedRevision,
    recordHistory,
    undo,
    redo,
    canUndo: historyFlags.canUndo,
    canRedo: historyFlags.canRedo,
    subscribeReseed,
    focusRequest,
    resolveProcessId,
    resolveConflict,
  }), [recipeId, loading, loadError, canEdit, version, getProcesses, getProcess, saveState, updateProcess, addProcess, removeProcess,
    saveNow, getPersistedRevision, recordHistory, undo, redo, historyFlags, subscribeReseed, focusRequest, resolveProcessId, resolveConflict])

  return (
    <RecipeSessionContext.Provider value={value}>
      {children}
      {recoveryOffer && !loading && (
        <RecoveryPrompt
          writtenAt={recoveryOffer.snapshot.writtenAt}
          backendChanged={recoveryOffer.backendChanged}
          onRecover={recoverChanges}
          onDiscard={discardRecovery}
        />
      )}
    </RecipeSessionContext.Provider>
  )
}
