import { useMemo } from 'react'
import { Link } from 'react-router-dom'
import '../../../../styles/flow-ui.css'
import '../styles/recipe-view.css'
import type { NutritionInfo, RecipeDetail } from '../../../../types/recipe'
import { useRecipeSession } from '../../session/sessionContext'
import { recipeToolPath } from '../../recipeToolRoutes'
import { buildRecipePresentation } from '../model/recipeView'
import { useCookMode } from '../../page/useCookMode'
import RecipeHero from './RecipeHero'
import RecipeIngredientsList from './RecipeIngredientsList'
import RecipeNutritionPanel from './RecipeNutritionPanel'
import RecipeStepsTimeline from './RecipeStepsTimeline'
import AddToMyRecipesButton from '../../page/AddToMyRecipesButton'
import type { AddToMyRecipes } from '../../page/useAddToMyRecipes'

type RecipeViewPageProps = {
  recipe: RecipeDetail
  isOwner: boolean
  /** Readers of someone else's public recipe: adding it to their kitchen (or opening the copy they have). */
  addToMyRecipes?: AddToMyRecipes | null
  onNutritionSaved: (nutrition: NutritionInfo | null) => void
  /** Opens one step in the editor (owners only). */
  onOpenStep?: (processId: number, nodeId: string) => void
}

const ANCHORS = {
  ingredients: 'recipe-ingredients',
  steps: 'recipe-steps',
  nutrition: 'recipe-nutrition',
}

/**
 * The Recipe Process: the recipe as someone would cook it — hero, ingredients, the steps in order
 * (with checks and their outcomes), then nutrition. Entirely derived from the same in-memory
 * recipe session the Recipe Editor edits (see buildRecipePresentation), so it always reflects the
 * editor's latest content, including edits that haven't been saved yet.
 */
export default function RecipeViewPage({ recipe, isOwner, addToMyRecipes, onNutritionSaved, onOpenStep }: RecipeViewPageProps) {
  const session = useRecipeSession()
  const cookMode = useCookMode(recipe.id)

  // `session` changes identity on every process edit (its `version` bumps), which is exactly
  // when this needs re-deriving.
  const presentation = useMemo(
    () => (session && !session.loading ? buildRecipePresentation(session.getProcesses()) : null),
    [session],
  )

  if (session?.loadError) {
    return <div className="rp-page"><div className="rp-state">{session.loadError}</div></div>
  }

  if (!presentation) {
    return <div className="rp-page"><div className="rp-state">Loading recipe…</div></div>
  }

  const hasSteps = presentation.sections.length > 0

  return (
    <div className="rp-page">
      <div className="rp-container">
        {session?.anyDirty && (
          <div className="rp-draft-banner" role="status">
            <span aria-hidden>✏️</span>
            Showing unsaved changes from the Recipe Editor.
          </div>
        )}

        {!isOwner && (
          <div className="rp-readonly-banner" role="note">
            <span className="rp-readonly-banner-icon" aria-hidden>👀</span>
            <span className="rp-readonly-banner-text">
              {addToMyRecipes?.existingCopyId != null
                ? <><strong>You already have this recipe.</strong> This is the public original — open your copy to edit or order it.</>
                : <><strong>You're viewing a public recipe from another cook.</strong> Add it to your kitchen to edit, customise or order it.</>}
            </span>
            {addToMyRecipes && <AddToMyRecipesButton action={addToMyRecipes} className="rp-button" />}
          </div>
        )}

        <div className="rp-page-actions">
          {isOwner && (
            <Link className="rp-button" to={recipeToolPath(recipe.id, 'editor')} title="Open this recipe in the Recipe Editor">
              <span aria-hidden>✏️</span> Edit recipe
            </Link>
          )}
          <button type="button" className="rp-button rp-button-ghost" onClick={() => window.print()} title="Print this recipe">
            <span aria-hidden>🖨️</span> Print
          </button>
        </div>

        <RecipeHero
          recipe={recipe}
          presentation={presentation}
          sectionAnchors={[
            { id: ANCHORS.ingredients, label: '🥕 Ingredients' },
            { id: ANCHORS.steps, label: '👩‍🍳 Steps' },
            { id: ANCHORS.nutrition, label: '📊 Nutrition' },
          ]}
        />

        <section id={ANCHORS.ingredients} className="rp-section" aria-labelledby={`${ANCHORS.ingredients}-title`}>
          <div className="rp-section-header">
            <h2 id={`${ANCHORS.ingredients}-title`} className="rp-section-title">
              <span className="rp-section-title-icon" aria-hidden>🥕</span>
              Ingredients
            </h2>
            {presentation.ingredients.length > 0 && (
              <p className="rp-section-subtitle">Everything you'll need, totalled across all steps.</p>
            )}
          </div>
          <RecipeIngredientsList ingredients={presentation.ingredients} />
        </section>

        <section id={ANCHORS.steps} className="rp-section" aria-labelledby={`${ANCHORS.steps}-title`}>
          <div className="rp-section-header">
            <h2 id={`${ANCHORS.steps}-title`} className="rp-section-title">
              <span className="rp-section-title-icon" aria-hidden>👩‍🍳</span>
              Recipe Steps
            </h2>
            {hasSteps && (
              <button type="button" className="rp-button" onClick={() => void cookMode.open()} title="Follow the recipe one step at a time, with narration">
                <span aria-hidden>▶</span> Cook mode
              </button>
            )}
          </div>
          {hasSteps ? (
            <RecipeStepsTimeline sections={presentation.sections} onOpenStep={isOwner ? onOpenStep : undefined} />
          ) : (
            <div className="rp-empty-card">
              <span className="rp-empty-card-icon" aria-hidden>📝</span>
              This recipe doesn't have any steps yet.
              {isOwner && (
                <Link className="rp-button" to={recipeToolPath(recipe.id, 'editor')}>Open the Recipe Editor</Link>
              )}
            </div>
          )}
        </section>

        <section id={ANCHORS.nutrition} className="rp-section" aria-labelledby={`${ANCHORS.nutrition}-title`}>
          <div className="rp-section-header">
            <h2 id={`${ANCHORS.nutrition}-title`} className="rp-section-title">
              <span className="rp-section-title-icon" aria-hidden>📊</span>
              Nutrition
            </h2>
          </div>
          <RecipeNutritionPanel
            recipeId={recipe.id}
            nutrition={recipe.nutrition}
            isOwner={isOwner}
            onSaved={onNutritionSaved}
          />
        </section>
      </div>

      {cookMode.element}
    </div>
  )
}
