import { RECIPE_SORT_OPTIONS, isRecipeSort, type RecipeSort } from '../recipeListing'

type RecipeToolbarProps = {
  search: string
  onSearchChange: (value: string) => void
  sort: RecipeSort
  onSortChange: (sort: RecipeSort) => void
  recipeCount: number
  placeholder?: string
}

export default function RecipeToolbar({
  search,
  onSearchChange,
  sort,
  onSortChange,
  recipeCount,
  placeholder = 'Search recipes...',
}: RecipeToolbarProps) {
  return (
    <div className="mb-6 flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
      <div className="relative min-w-0 flex-1 sm:max-w-xl">
        <span
          className="pointer-events-none absolute left-3.5 top-1/2 -translate-y-1/2 text-[var(--flow-text-muted)]"
          aria-hidden="true"
        >
          🔍
        </span>

        <input
          value={search}
          onChange={e => onSearchChange(e.target.value)}
          placeholder={placeholder}
          aria-label={placeholder.replace(/\.+$/, '')}
          className="recipe-search-input"
        />

        {search && (
          <button
            type="button"
            onClick={() => onSearchChange('')}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-sm text-[var(--flow-text-muted)] hover:text-[var(--flow-text)]"
            aria-label="Clear search"
          >
            ✕
          </button>
        )}
      </div>

      <div className="flex shrink-0 items-center gap-3 text-sm text-[var(--flow-text-muted)]">
        <span>{recipeCount} {recipeCount === 1 ? 'recipe' : 'recipes'}</span>
        <label className="flex items-center gap-2">
          <span className="sr-only">Sort recipes</span>
          <select
            value={sort}
            onChange={e => { if (isRecipeSort(e.target.value)) onSortChange(e.target.value) }}
            className="recipe-sort-select"
          >
            {RECIPE_SORT_OPTIONS.map((option) => (
              <option key={option.value} value={option.value}>{option.label}</option>
            ))}
          </select>
        </label>
      </div>
    </div>
  )
}
