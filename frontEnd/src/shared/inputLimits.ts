/**
 * Maximum sizes of text users type, mirroring the backend's limits (backend `common/web/InputLimits.java`) with
 * equal or smaller values, so people see the limit while typing instead of a rejected request. Keep the two in step.
 */
export const INPUT_LIMITS = {
  /** Recipe text sent to AI recipe creation, editing or refinement. */
  recipeText: 8000,
  /** A step's longer text fields (summary, expected output). */
  stepText: 2000,
  /** Free notes on a step or a check. */
  notes: 1000,
  /** Largest recipe file that can be imported (the backend refuses request bodies over 5 MB). */
  importFileBytes: 5 * 1024 * 1024,
} as const

/** Shown under a limited text box once it is close to the limit, e.g. "7,950 / 8,000". */
export const charCount = (length: number, max: number): string =>
  `${length.toLocaleString()} / ${max.toLocaleString()}`

/** Whether a counter is worth showing: only within the last 10% of the limit, so it doesn't add noise earlier. */
export const nearLimit = (length: number, max: number): boolean => length >= max * 0.9
