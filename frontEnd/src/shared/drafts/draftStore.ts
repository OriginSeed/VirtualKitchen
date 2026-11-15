/**
 * Unsent form drafts: text the user typed into a form whose action hasn't succeeded yet (a recipe to
 * generate, a new subprocess, nutrition values…), kept in localStorage so closing the form,
 * navigating away or reloading never loses it. A form clears its draft only once its action
 * succeeds (or the user explicitly discards it).
 *
 * Drafts are a convenience, never the source of truth: every access tolerates a missing, full,
 * blocked or corrupt store, old drafts expire, and logging out removes them all (they may hold
 * recipe content and the browser may be shared).
 */

const KEY_PREFIX = 'vk.draft.v1.'
/** A draft nobody came back to for this long is dropped. */
export const DRAFT_TTL_MS = 14 * 24 * 60 * 60 * 1000

export type DraftStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem' | 'key' | 'length'>

type DraftEnvelope<T> = { savedAt: number; value: T }

/** The browser's localStorage, or null when it's unavailable (private mode, blocked site data). */
const browserDraftStorage = (): DraftStorage | null => {
  try {
    return typeof window !== 'undefined' ? window.localStorage : null
  } catch {
    return null
  }
}

const storageKey = (key: string) => `${KEY_PREFIX}${key}`

export const readDraft = <T>(key: string, storage: DraftStorage | null = browserDraftStorage(), now = Date.now()): T | undefined => {
  if (!storage) return undefined
  try {
    const raw = storage.getItem(storageKey(key))
    if (raw == null) return undefined
    const envelope = JSON.parse(raw) as Partial<DraftEnvelope<T>>
    if (typeof envelope?.savedAt !== 'number' || !('value' in envelope) || now - envelope.savedAt > DRAFT_TTL_MS) {
      storage.removeItem(storageKey(key))
      return undefined
    }
    return envelope.value as T
  } catch {
    return undefined
  }
}

export const writeDraft = <T>(key: string, value: T, storage: DraftStorage | null = browserDraftStorage(), now = Date.now()) => {
  if (!storage) return
  try {
    storage.setItem(storageKey(key), JSON.stringify({ savedAt: now, value } satisfies DraftEnvelope<T>))
  } catch {
    // Quota exceeded or storage blocked: the form itself keeps working, only the draft is lost.
  }
}

export const clearDraft = (key: string, storage: DraftStorage | null = browserDraftStorage()) => {
  if (!storage) return
  try {
    storage.removeItem(storageKey(key))
  } catch {
    // Nothing to clean up if storage is unavailable.
  }
}

/** Removes every draft (on logout), plus — when `onlyExpired` — just the stale ones. */
export const clearAllDrafts = (storage: DraftStorage | null = browserDraftStorage(), options: { onlyExpired?: boolean; now?: number } = {}) => {
  if (!storage) return
  try {
    const keys: string[] = []
    for (let index = 0; index < storage.length; index++) {
      const key = storage.key(index)
      if (key?.startsWith(KEY_PREFIX)) keys.push(key)
    }
    const now = options.now ?? Date.now()
    keys.forEach((key) => {
      if (options.onlyExpired) {
        readDraft(key.slice(KEY_PREFIX.length), storage, now) // drops it when expired or corrupt
      } else {
        storage.removeItem(key)
      }
    })
  } catch {
    // Storage unavailable: nothing to clear.
  }
}

/** Removes every draft that belongs to one recipe (e.g. once the recipe is deleted). */
export const clearRecipeDrafts = (recipeId: number, storage: DraftStorage | null = browserDraftStorage()) => {
  if (!storage) return
  const prefix = storageKey(`recipe.${recipeId}.`)
  try {
    const keys: string[] = []
    for (let index = 0; index < storage.length; index++) {
      const key = storage.key(index)
      if (key?.startsWith(prefix)) keys.push(key)
    }
    keys.forEach((key) => storage.removeItem(key))
  } catch {
    // Storage unavailable: nothing to clear.
  }
}

/** Draft keys used across the app, in one place so a form and whoever clears its draft agree. */
export const draftKeys = {
  aiRecipeCreation: (recipeId: number) => `recipe.${recipeId}.aiRecipeCreation`,
  aiEdit: (recipeId: number) => `recipe.${recipeId}.aiEdit`,
  nutrition: (recipeId: number) => `recipe.${recipeId}.nutrition`,
  stepIngredient: (stepId: string) => `step.${stepId}.newIngredient`,
  newRecipe: () => 'newRecipe',
  /** The sign-up / login form's step, name and email — never its passwords or codes. */
  auth: () => 'auth',
}
