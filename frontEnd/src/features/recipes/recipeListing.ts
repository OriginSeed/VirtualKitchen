/** Searching and sorting a list of recipes (My Recipes and Discover share it). Pure, so it is unit-tested. */

export type RecipeSort = 'updated' | 'newest' | 'oldest' | 'name'

export const RECIPE_SORT_OPTIONS: Array<{ value: RecipeSort; label: string }> = [
  { value: 'newest', label: 'Newest first' },
  { value: 'oldest', label: 'Oldest first' },
  { value: 'updated', label: 'Recently updated' },
  { value: 'name', label: 'Name (A–Z)' },
]

export const isRecipeSort = (value: string | null | undefined): value is RecipeSort =>
  value === 'updated' || value === 'newest' || value === 'oldest' || value === 'name'

type Listable = {
  id: number
  name: string
  description?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

const time = (value: string | null | undefined) => {
  const parsed = value ? Date.parse(value) : Number.NaN
  return Number.isFinite(parsed) ? parsed : null
}

/** When the recipe was last changed: its update time, else its creation time (null when it has neither). */
export const recipeUpdatedTime = (recipe: { createdAt?: string | null; updatedAt?: string | null }) =>
  time(recipe.updatedAt) ?? time(recipe.createdAt)

/**
 * Orders two recipes oldest first by `when`. Recipes saved before the backend recorded dates have none; they were
 * all created before any dated recipe, so they come first, ordered by id (ids are assigned in creation order).
 */
export const compareByTime = (
  a: { id: number; time: number | null },
  b: { id: number; time: number | null },
): number => {
  if (a.time == null || b.time == null) {
    if (a.time == null && b.time == null) return a.id - b.id
    return a.time == null ? -1 : 1
  }
  return a.time - b.time || a.id - b.id
}

/** Recipes whose name or description contains every word of `query` (case-insensitive). */
export const searchRecipes = <T extends Listable>(recipes: T[], query: string): T[] => {
  const words = query.trim().toLowerCase().split(/\s+/).filter(Boolean)
  if (words.length === 0) return recipes
  return recipes.filter((recipe) => {
    const haystack = `${recipe.name} ${recipe.description ?? ''}`.toLowerCase()
    return words.every((word) => haystack.includes(word))
  })
}

/**
 * A sorted copy. 'newest'/'oldest' sort by creation date, 'updated' by last change (most recent first); undated
 * recipes count as older than every dated one and are ordered by id between themselves.
 */
export const sortRecipes = <T extends Listable>(recipes: T[], sort: RecipeSort): T[] => {
  const indexed = recipes.map((recipe, index) => ({
    recipe,
    index,
    id: recipe.id,
    time: sort === 'updated' ? recipeUpdatedTime(recipe) : time(recipe.createdAt),
  }))
  indexed.sort((a, b) => {
    if (sort === 'name') {
      return a.recipe.name.localeCompare(b.recipe.name, undefined, { sensitivity: 'base' }) || a.index - b.index
    }
    return sort === 'oldest' ? compareByTime(a, b) : compareByTime(b, a)
  })
  return indexed.map((entry) => entry.recipe)
}
