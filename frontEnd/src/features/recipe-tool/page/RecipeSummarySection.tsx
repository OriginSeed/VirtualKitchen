import { useMemo } from 'react'
import '../../../styles/flow-ui.css'
import type { RecipeDetail } from '../../../types/recipe'
import { useRecipeSession } from '../session/sessionContext'
import { buildRecipePresentation } from '../view/model/recipeView'

type RecipeSummarySectionProps = {
  recipe: RecipeDetail
}

/**
 * Read-only recipe metadata: name, full description, visibility, and a
 * handful of at-a-glance facts. Used as the content of RecipeToolNavbar's
 * "full recipe details" popover — the always-visible compact metadata in
 * the navbar itself covers the common case, this covers "show me
 * everything" (and is what a narrow viewport's collapsed icon button
 * opens). The main-process entry point used to live here (Phase 6); it now
 * lives in the Recipe Editor tab itself, which is the tab this same
 * information used to just link out to.
 */
export default function RecipeSummarySection({ recipe }: RecipeSummarySectionProps) {
  // The ingredients are the ones the recipe's steps use (as in the Recipe Process view's list) —
  // recipe.ingredients is a recipe-level list the editor doesn't fill. The session value changes on
  // every edit, so the count follows unsaved changes too.
  const session = useRecipeSession()
  const ingredientCount = useMemo(
    () => (session && !session.loading
      ? buildRecipePresentation(session.getProcesses()).ingredients.length
      : recipe.ingredients.length),
    [session, recipe.ingredients.length],
  )
  const hasNutrition = recipe.nutrition != null

  return (
    <section
      className="flow-editor-surface"
      style={{
        border: '1px solid var(--flow-border)',
        borderRadius: 14,
        background: 'var(--flow-surface)',
        padding: '18px 20px',
        display: 'flex',
        flexDirection: 'column',
        gap: 10,
      }}
    >
      <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 12, flexWrap: 'wrap' }}>
        <div style={{ minWidth: 0 }}>
          <h1 style={{ margin: 0, fontSize: 22, fontWeight: 800, color: 'var(--flow-text)' }}>{recipe.name}</h1>
          {recipe.description && (
            <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--flow-text-muted)', maxWidth: 620 }}>{recipe.description}</p>
          )}
        </div>

        <span
          style={{
            flexShrink: 0,
            fontSize: 10,
            fontWeight: 700,
            padding: '3px 10px',
            borderRadius: 999,
            background: recipe.visibility === 'PUBLIC' ? 'var(--flow-success-soft)' : 'var(--flow-surface-muted)',
            border: `1px solid ${recipe.visibility === 'PUBLIC' ? 'var(--flow-success-border)' : 'var(--flow-border)'}`,
            color: recipe.visibility === 'PUBLIC' ? 'var(--flow-success-strong)' : 'var(--flow-text-muted)',
          }}
        >
          {recipe.visibility === 'PUBLIC' ? '🌐 Public' : '🔒 Private'}
        </span>
      </div>

      <div style={{ display: 'flex', flexWrap: 'wrap', gap: 16, fontSize: 12, color: 'var(--flow-text-muted)', fontWeight: 600 }}>
        <span>{ingredientCount} ingredient{ingredientCount === 1 ? '' : 's'}</span>
        <span>{hasNutrition ? 'Nutrition added' : 'No nutrition info yet'}</span>
        <span>{recipe.mainProcessId != null ? 'Main process ready' : 'No main process yet'}</span>
      </div>
    </section>
  )
}
