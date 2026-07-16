import { useEffect, useState } from 'react'
import { RecipeApi } from '../../api'
import './styles/flow-editor.css'

type Recipe = {
  id: number
  name: string
  description?: string
  createdAt?: string
}

type RecipeHomePageProps = {
  onCreateRecipe: (recipeId: number, title: string) => void
}

export default function RecipeHomePage({ onCreateRecipe }: RecipeHomePageProps) {
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [recipes, setRecipes] = useState<Recipe[]>([])
  const [recipesLoading, setRecipesLoading] = useState(false)

  const loadRecipes = async () => {
    try {
      setRecipesLoading(true)
      const items = await RecipeApi.getRecipesByUserId(1)
      setRecipes(items || [])
    } catch (err) {
      console.error(err)
    } finally {
      setRecipesLoading(false)
    }
  }

  useEffect(() => {
    void loadRecipes()
  }, [])

  const handleDeleteRecipe = async (recipeId: number) => {
    try {
      await RecipeApi.deleteRecipe(recipeId)
      await loadRecipes()
    } catch (err) {
      console.error(err)
      setError(err instanceof Error ? err.message : 'Unable to delete recipe')
    }
  }

  const handleCreate = async () => {
    if (!title.trim()) {
      setError('Please enter a recipe name')
      return
    }

    try {
      setLoading(true)
      setError(null)
      const result = await RecipeApi.createRecipe({
        name: title.trim(),
        description: description.trim(),
        createdBy: 1,
      })

      if (!result.id) {
        throw new Error('Recipe created but no id returned')
      }

      onCreateRecipe(result.id, title.trim())
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Unable to create recipe')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="flow-editor-shell min-h-screen bg-[linear-gradient(135deg,#f8fafc_0%,#eef2ff_100%)] px-6 py-6">
      <div className="mx-auto max-w-7xl">
        <div className="flow-editor-panel mb-5 flex items-center justify-between px-4 py-3">
          <div>
            <div className="text-[0.75rem] font-semibold uppercase tracking-[0.16em] text-[var(--flow-accent)]">Virtual Kitchen</div>
            <div className="text-sm font-semibold text-[var(--flow-text)]">Recipes</div>
          </div>
          <button onClick={handleCreate} disabled={loading} className="rounded-[0.625rem] bg-[linear-gradient(135deg,var(--flow-accent),#8b5cf6)] px-3.5 py-2.5 text-sm font-semibold text-white disabled:cursor-default disabled:opacity-80">
            {loading ? 'Creating...' : 'Create recipe'}
          </button>
        </div>

        <div className="grid grid-cols-[minmax(0,1fr)_minmax(320px,360px)] gap-5">
          <div className="flow-editor-panel p-8">
            <div className="mb-5">
              <div className="text-[0.75rem] font-semibold uppercase tracking-[0.16em] text-[var(--flow-accent)]">Virtual Kitchen</div>
              <h1 className="mb-2 mt-1.5 text-3xl font-extrabold text-[var(--flow-text)]">Design a new recipe</h1>
              <p className="m-0 leading-6 text-[var(--flow-text-muted)]">Start with a recipe name and description, then enter the builder to design the flow.</p>
            </div>

            <label className="mb-3 block">
              <div className="mb-1.5 text-xs font-semibold text-slate-700">Recipe name</div>
              <input value={title} onChange={e => setTitle(e.target.value)} placeholder="e.g. Spaghetti Aglio e Olio" className="flow-editor-input" />
            </label>

            <label className="mb-4 block">
              <div className="mb-1.5 text-xs font-semibold text-slate-700">Description</div>
              <textarea value={description} onChange={e => setDescription(e.target.value)} placeholder="Describe the recipe" rows={4} className="flow-editor-input min-h-[7rem] resize-y" />
            </label>

            {error && <div className="mb-3.5 text-sm text-red-600">{error}</div>}

            <button onClick={handleCreate} disabled={loading} className="w-full rounded-xl bg-[linear-gradient(135deg,var(--flow-accent),#8b5cf6)] px-3.5 py-3 text-sm font-semibold text-white disabled:cursor-default disabled:opacity-80">
              {loading ? 'Creating recipe...' : 'Create recipe'}
            </button>
          </div>

          <div className="flow-editor-panel p-6">
            <div className="mb-3.5 flex items-center justify-between">
              <div>
                <div className="text-[0.75rem] font-semibold uppercase tracking-[0.16em] text-[var(--flow-accent)]">Existing recipes</div>
                <div className="text-lg font-extrabold text-[var(--flow-text)]">Open a recipe</div>
              </div>
            </div>

            {recipesLoading ? (
              <div className="text-sm text-[var(--flow-text-muted)]">Loading recipes...</div>
            ) : recipes.length === 0 ? (
              <div className="text-sm text-[var(--flow-text-muted)]">No recipes yet. Create your first one.</div>
            ) : (
              <div className="flex flex-col gap-2.5">
                {recipes.map(recipe => (
                  <div key={recipe.id} className="flex items-center justify-between gap-2 rounded-[0.875rem] border border-[var(--flow-border)] bg-white p-3">
                    <button onClick={() => onCreateRecipe(recipe.id, recipe.name)} className="flex-1 cursor-pointer bg-transparent p-0 text-left">
                      <div className="mb-1 font-semibold text-[var(--flow-text)]">{recipe.name}</div>
                      <div className="text-xs text-[var(--flow-text-muted)]">{recipe.description || 'Open to edit the flow'}</div>
                    </button>
                    <button onClick={() => void handleDeleteRecipe(recipe.id)} title="Delete recipe" className="flex h-8 w-8 items-center justify-center rounded-full bg-rose-50 text-lg text-rose-600">
                      🗑️
                    </button>
                  </div>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  )
}
