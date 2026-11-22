/**
 * Debounced, single-flight autosave scheduler for the Recipe Tool's working session — framework-
 * free (like jobTracker.ts) so its timing rules are unit-testable with a fake clock
 * (frontEnd/tests/saveCoordinator.test.ts).
 *
 * The session tells it *that* something changed (`markChanged`); it decides *when* to persist by
 * calling the session's `save` function, which reads the latest snapshot at call time. Rules:
 * - Debounce: bursts of changes produce one save once editing pauses for `debounceMs`.
 * - Single flight: at most one save runs at a time. A save requested while one is in flight
 *   runs right after it, with the then-latest snapshot — so an older snapshot can never be
 *   persisted after a newer one.
 * - Revisions: every change bumps `editRevision`; a save that started at revision N only marks
 *   revision N as saved, so edits made during the request keep the session dirty.
 * - Failures keep everything (the session is never touched here). Network/5xx failures retry
 *   with exponential backoff up to `maxAutoRetries`; validation/permission failures wait for the
 *   next edit or an explicit Save; a 409 parks the coordinator in `conflict` until resolved.
 */

import { errorMessage } from '../../../../shared/errors.ts'

export type SaveStatus = 'saved' | 'dirty' | 'saving' | 'failed' | 'conflict'

/** How a failed save should be handled: `network` retries automatically, the rest don't. */
export type SaveErrorKind = 'network' | 'validation' | 'forbidden' | 'conflict'

export type SaveState = {
  status: SaveStatus
  /** Message of the last failed save, cleared by the next successful one. */
  error: string | null
  errorKind: SaveErrorKind | null
  /** Epoch ms of the last successful save in this session (null until one happened). */
  lastSavedAt: number | null
  /** Epoch ms when the next automatic retry fires, null when none is scheduled. */
  retryAt: number | null
}

/** Maps an HTTP status (undefined = no response at all) to how a failed save is handled. */
export const classifySaveError = (status: number | undefined): SaveErrorKind => {
  if (status == null || status >= 500 || status === 408 || status === 429) return 'network'
  if (status === 409) return 'conflict'
  if (status === 401 || status === 403) return 'forbidden'
  return 'validation'
}

type TimerHandle = ReturnType<typeof setTimeout>

export type SaveCoordinatorOptions = {
  /** Persists the session's latest snapshot. Must reject on failure; `statusOf` reads the HTTP status. */
  save: () => Promise<void>
  statusOf?: (error: unknown) => number | undefined
  debounceMs?: number
  retryBaseMs?: number
  retryMaxMs?: number
  maxAutoRetries?: number
  /** When false, changes are tracked but never saved automatically (read-only viewers). */
  autosave?: boolean
  now?: () => number
  setTimer?: (callback: () => void, ms: number) => TimerHandle
  clearTimer?: (handle: TimerHandle) => void
}

export class SaveCoordinator {
  private editRevision = 0
  private savedRevision = 0
  private inFlight: Promise<boolean> | null = null
  private rerun = false
  private attempts = 0
  private debounceTimer: TimerHandle | null = null
  private retryTimer: TimerHandle | null = null
  private state: SaveState = { status: 'saved', error: null, errorKind: null, lastSavedAt: null, retryAt: null }
  private lastEmitted: SaveState | null = null
  private listeners = new Set<() => void>()

  private readonly save: () => Promise<void>
  private readonly statusOf: (error: unknown) => number | undefined
  private readonly debounceMs: number
  private readonly retryBaseMs: number
  private readonly retryMaxMs: number
  private readonly maxAutoRetries: number
  private readonly autosave: boolean
  private readonly now: () => number
  private readonly setTimer: (callback: () => void, ms: number) => TimerHandle
  private readonly clearTimer: (handle: TimerHandle) => void

  constructor(options: SaveCoordinatorOptions) {
    this.save = options.save
    this.statusOf = options.statusOf ?? (() => undefined)
    this.debounceMs = options.debounceMs ?? 1500
    this.retryBaseMs = options.retryBaseMs ?? 2000
    this.retryMaxMs = options.retryMaxMs ?? 60000
    this.maxAutoRetries = options.maxAutoRetries ?? 8
    this.autosave = options.autosave ?? true
    this.now = options.now ?? Date.now
    this.setTimer = options.setTimer ?? ((callback, ms) => setTimeout(callback, ms))
    this.clearTimer = options.clearTimer ?? ((handle) => clearTimeout(handle))
  }

  getState = () => this.state

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => {
      this.listeners.delete(listener)
    }
  }

  /** True while there are edits not yet confirmed persisted (or a save is running). */
  hasPendingChanges() {
    return this.editRevision > this.savedRevision || this.inFlight != null
  }

  /** Records that the session changed and (re)starts the autosave debounce. */
  markChanged() {
    this.editRevision += 1
    this.update()
    if (!this.autosave || this.state.errorKind === 'conflict') return
    // While a network retry is pending, let it pick up the latest snapshot instead of hammering a
    // backend that's down on every keystroke.
    if (this.retryTimer != null) return
    this.cancelDebounce()
    this.debounceTimer = this.setTimer(() => {
      this.debounceTimer = null
      void this.run()
    }, this.debounceMs)
  }

  /**
   * "Save everything now": skips the debounce (and any pending retry) and resolves once the
   * latest revision is persisted (true) or the attempt failed (false). Never runs concurrently
   * with an in-flight save — it queues behind it.
   */
  flush(): Promise<boolean> {
    this.cancelDebounce()
    this.cancelRetry()
    this.attempts = 0
    if (this.state.errorKind === 'conflict') return Promise.resolve(false)
    return this.run()
  }

  /** Clears a `conflict` so saving can resume (after the session rebased or reloaded). */
  resolveConflict() {
    if (this.state.errorKind !== 'conflict') return
    this.state = { ...this.state, error: null, errorKind: null }
    this.update()
  }

  /** Back to a clean, saved state — after the session reloaded from the backend. */
  reset() {
    this.cancelDebounce()
    this.cancelRetry()
    this.editRevision = 0
    this.savedRevision = 0
    this.attempts = 0
    this.state = { status: 'saved', error: null, errorKind: null, lastSavedAt: this.state.lastSavedAt, retryAt: null }
    this.emit()
  }

  /** Stops timers (provider unmount). An in-flight save is left to finish. */
  dispose() {
    this.cancelDebounce()
    this.cancelRetry()
  }

  private run(): Promise<boolean> {
    if (this.inFlight) {
      this.rerun = true
      return this.inFlight
    }
    this.inFlight = this.loop().finally(() => {
      this.inFlight = null
      this.update()
    })
    this.update()
    return this.inFlight
  }

  private async loop(): Promise<boolean> {
    do {
      this.rerun = false
      if (this.editRevision === this.savedRevision && this.state.error == null) continue
      const revision = this.editRevision
      try {
        await this.save()
      } catch (error) {
        this.fail(error)
        return false
      }
      this.savedRevision = Math.max(this.savedRevision, revision)
      this.attempts = 0
      this.state = { ...this.state, error: null, errorKind: null, lastSavedAt: this.now(), retryAt: null }
      this.update()
    } while (this.rerun)
    return true
  }

  private fail(error: unknown) {
    const kind = classifySaveError(this.statusOf(error))
    const message = errorMessage(error, 'Unable to save this recipe right now')
    let retryAt: number | null = null
    if (kind === 'network' && this.autosave) {
      this.attempts += 1
      if (this.attempts <= this.maxAutoRetries) {
        const delay = Math.min(this.retryBaseMs * 2 ** (this.attempts - 1), this.retryMaxMs)
        retryAt = this.now() + delay
        this.cancelDebounce()
        this.cancelRetry()
        this.retryTimer = this.setTimer(() => {
          this.retryTimer = null
          void this.run()
        }, delay)
      }
    }
    this.state = { ...this.state, error: message, errorKind: kind, retryAt }
    this.update()
  }

  /** Retries right away after a network failure — e.g. when the browser comes back online. */
  retryNow() {
    if (this.state.errorKind !== 'network') return
    this.cancelRetry()
    this.attempts = 0
    void this.run()
  }

  private cancelDebounce() {
    if (this.debounceTimer != null) this.clearTimer(this.debounceTimer)
    this.debounceTimer = null
  }

  private cancelRetry() {
    if (this.retryTimer != null) this.clearTimer(this.retryTimer)
    this.retryTimer = null
    if (this.state.retryAt != null) this.state = { ...this.state, retryAt: null }
  }

  /** Recomputes `status` from the counters and notifies only when something visible changed. */
  private update() {
    const status: SaveStatus = this.state.errorKind === 'conflict'
      ? 'conflict'
      : this.inFlight != null
        ? 'saving'
        : this.state.error != null && this.editRevision > this.savedRevision
          ? 'failed'
          : this.editRevision > this.savedRevision ? 'dirty' : 'saved'
    if (status === this.state.status && this.state === this.lastEmitted) return
    this.state = { ...this.state, status }
    this.emit()
  }


  private emit() {
    this.lastEmitted = this.state
    this.listeners.forEach((listener) => listener())
  }
}
