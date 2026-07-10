import { useEffect, useState } from 'react'
import { RecipeApi } from '../../api'

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
    <div style={{ minHeight: '100vh', background: 'linear-gradient(135deg, #f8fafc 0%, #eef2ff 100%)', padding: 24 }}>
      <div style={{ maxWidth: 1200, margin: '0 auto' }}>
        <div style={{ background: 'white', borderRadius: 16, border: '1px solid #e2e8f0', padding: '12px 16px', display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 20 }}>
          <div>
            <div style={{ fontSize: 12, fontWeight: 700, color: '#6366f1', letterSpacing: '0.16em', textTransform: 'uppercase' }}>Virtual Kitchen</div>
            <div style={{ fontSize: 14, fontWeight: 700, color: '#0f172a' }}>Recipes</div>
          </div>
          <button onClick={handleCreate} disabled={loading} style={{ border: 'none', borderRadius: 10, padding: '10px 14px', fontSize: 13, fontWeight: 700, background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: 'white', cursor: loading ? 'default' : 'pointer' }}>
            {loading ? 'Creating...' : 'Create recipe'}
          </button>
        </div>

        <div style={{ display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) minmax(320px, 360px)', gap: 20, alignItems: 'start' }}>
          <div style={{ background: 'white', borderRadius: 24, boxShadow: '0 20px 60px rgba(15,23,42,0.08)', border: '1px solid #e2e8f0', padding: 32 }}>
            <div style={{ marginBottom: 20 }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: '#6366f1', letterSpacing: '0.16em', textTransform: 'uppercase' }}>Virtual Kitchen</div>
              <h1 style={{ margin: '6px 0 8px', fontSize: 30, fontWeight: 800, color: '#0f172a' }}>Design a new recipe</h1>
              <p style={{ margin: 0, color: '#64748b', lineHeight: 1.6 }}>Start with a recipe name and description, then enter the builder to design the flow.</p>
            </div>

            <label style={{ display: 'block', marginBottom: 12 }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: '#334155', marginBottom: 6 }}>Recipe name</div>
              <input value={title} onChange={e => setTitle(e.target.value)} placeholder="e.g. Spaghetti Aglio e Olio" style={{ width: '100%', border: '1px solid #cbd5e1', borderRadius: 12, padding: '10px 12px', fontSize: 14 }} />
            </label>

            <label style={{ display: 'block', marginBottom: 16 }}>
              <div style={{ fontSize: 12, fontWeight: 700, color: '#334155', marginBottom: 6 }}>Description</div>
              <textarea value={description} onChange={e => setDescription(e.target.value)} placeholder="Describe the recipe" rows={4} style={{ width: '100%', border: '1px solid #cbd5e1', borderRadius: 12, padding: '10px 12px', fontSize: 14, resize: 'vertical' }} />
            </label>

            {error && <div style={{ marginBottom: 14, color: '#dc2626', fontSize: 13 }}>{error}</div>}

            <button onClick={handleCreate} disabled={loading} style={{ width: '100%', border: 'none', borderRadius: 12, padding: '12px 14px', fontSize: 14, fontWeight: 700, background: 'linear-gradient(135deg, #6366f1, #8b5cf6)', color: 'white', cursor: loading ? 'default' : 'pointer', opacity: loading ? 0.8 : 1 }}>
              {loading ? 'Creating recipe...' : 'Create recipe'}
            </button>
          </div>

          <div style={{ background: 'white', borderRadius: 24, boxShadow: '0 20px 60px rgba(15,23,42,0.08)', border: '1px solid #e2e8f0', padding: 24 }}>
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
              <div>
                <div style={{ fontSize: 12, fontWeight: 700, color: '#6366f1', letterSpacing: '0.16em', textTransform: 'uppercase' }}>Existing recipes</div>
                <div style={{ fontSize: 18, fontWeight: 800, color: '#0f172a' }}>Open a recipe</div>
              </div>
            </div>

            {recipesLoading ? (
              <div style={{ color: '#64748b', fontSize: 13 }}>Loading recipes...</div>
            ) : recipes.length === 0 ? (
              <div style={{ color: '#64748b', fontSize: 13 }}>No recipes yet. Create your first one.</div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                {recipes.map(recipe => (
                  <div key={recipe.id} style={{ border: '1px solid #e2e8f0', borderRadius: 14, padding: '12px 14px', background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8 }}>
                    <button onClick={() => onCreateRecipe(recipe.id, recipe.name)} style={{ flex: 1, background: 'transparent', border: 'none', textAlign: 'left', cursor: 'pointer', padding: 0 }}>
                      <div style={{ fontWeight: 700, color: '#0f172a', marginBottom: 4 }}>{recipe.name}</div>
                      <div style={{ fontSize: 12, color: '#64748b' }}>{recipe.description || 'Open to edit the flow'}</div>
                    </button>
                    <button onClick={() => void handleDeleteRecipe(recipe.id)} title="Delete recipe" style={{ border: 'none', background: '#fff1f2', color: '#e11d48', width: 32, height: 32, borderRadius: 999, cursor: 'pointer', fontSize: 15 }}>
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
