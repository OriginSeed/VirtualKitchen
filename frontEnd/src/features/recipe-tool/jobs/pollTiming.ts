/**
 * How often a tracked AI job is polled while the page is hidden (another tab, minimised window). Status polls are
 * not free on the server (the AI Recipe Creation poll reads several collections), and nobody is looking, so a hidden
 * page polls this slowly; it catches up at once when it becomes visible again.
 */
export const HIDDEN_POLL_INTERVAL_MS = 15000

/** The delay before the next poll: the job's normal interval, stretched to {@link HIDDEN_POLL_INTERVAL_MS} while hidden. */
export const pollDelay = (normalMs: number, hidden: boolean): number =>
  hidden ? Math.max(normalMs, HIDDEN_POLL_INTERVAL_MS) : normalMs

/** Whether the document is currently hidden; false outside a browser. */
export const isDocumentHidden = (): boolean =>
  typeof document !== 'undefined' && document.visibilityState === 'hidden'
