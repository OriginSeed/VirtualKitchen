import { useCallback, useEffect, useState } from 'react'
import { Link, Navigate, useNavigate } from 'react-router-dom'
import { RecipeApi, RecipeDetailApi } from '../../../api'
import type { NutritionInfo, RecipeDetail } from '../../../types/recipe'
import { RecipeSessionProvider } from '../session/RecipeSessionProvider'
import '../editor/styles/RecipeProcessCanvas.css'
import RecipeToolNavbar from './RecipeToolNavbar'
import RecipeEditorView from '../editor/components/RecipeEditorView'
import RecipeViewPage from '../view/components/RecipeViewPage'
import { recipeShareUrl, recipeToolPath, type RecipeToolView } from '../recipeToolRoutes'
import { useAddToMyRecipes } from './useAddToMyRecipes'
import EditRecipeDetailsModal, { type RecipeDetailsDraft } from './EditRecipeDetailsModal'
import { useConfirm } from '../../../shared/components/confirm/confirmContext'
import { useNotifications } from '../../../shared/components/notifications/notificationContext'
import { forgetRecipeLocalState } from '../session/forgetRecipeLocalState'
import { errorMessage } from '../../../shared/errors'
import { copyText } from '../../../shared/clipboard'
import { ErrorState } from '../../../shared/components/page'
import type { ReadinessIssue } from '../model/recipeReadiness'

export type OrderRecipeRequest = {
  recipeId: number
  recipeName: string
  servings?: number | null
  ensureReady: () => Promise<boolean>
}

type RecipeToolPageProps = {
  recipeId: number
  currentUserId: number
  /** From the route; undefined on the bare `/tool` URL, which lands on the Recipe Process (read mode). */
  view?: RecipeToolView
  /** Leaves the tool: owners back to My Recipes, readers back to Discover. */
  onBack: (isOwner: boolean) => void
  /**
   * "Order" (owners only); supplied by the app, since ordering is its own feature. `ensureReady` saves and checks
   * readiness — the app runs it before an order actually starts.
   */
  onOrderRecipe?: (order: OrderRecipeRequest) => void
  /** Id of the recipe an order is currently being started for, so the button can show progress. */
  orderingRecipeId?: number | null
  /** Opened right after "New recipe → Describe it for AI": the editor opens AI Recipe Creation straight away. */
  startAiCreation?: boolean
}

/**
 * The Recipe Tool page: an internal navbar (compact recipe metadata + the
 * two tool tabs) above a full-height content area showing one of two views
 * of the same recipe, each its own route (see recipeToolRoutes.ts):
 * - Recipe Editor — the React Flow process editor (RecipeEditorView), where
 *   the recipe is built and saved;
 * - Recipe Process — the human-readable recipe (ingredients, steps,
 *   nutrition) derived from that same process data (RecipeViewPage).
 * Both read the one RecipeSessionProvider below, so there's a single source
 * of truth and the Recipe Process always reflects the editor's latest content.
 */
export default function RecipeToolPage({
  recipeId, currentUserId, view, onBack, onOrderRecipe, orderingRecipeId, startAiCreation = false,
}: RecipeToolPageProps) {
  // Latched: the route drops `?start=ai` from the address straight away, before the editor has mounted.
  const [openAiCreationOnStart] = useState(startAiCreation)
  const [recipe, setRecipe] = useState<RecipeDetail | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const confirm = useConfirm()
  const { notifySuccess, notifyError } = useNotifications()
  const [editingDetails, setEditingDetails] = useState(false)
  // The editor mounts on first visit and then stays mounted (hidden while reading the Recipe
  // Process), so switching tabs never loses its canvas state — undo history, selection, the open
  // process. Not mounted up front: React Flow shouldn't initialise inside a hidden container.
  const [editorMounted, setEditorMounted] = useState(view === 'editor')
  if (view === 'editor' && !editorMounted) setEditorMounted(true)
  // An issue that blocked ordering or cook mode, opened in the editor from the dialog's "Show me".
  const navigate = useNavigate()
  const [issueFocusRequest, setIssueFocusRequest] = useState<{ issue: ReadinessIssue; token: number } | null>(null)
  const showIssue = useCallback((issue: ReadinessIssue) => {
    setIssueFocusRequest({ issue, token: Date.now() })
    navigate(recipeToolPath(recipeId, 'editor'))
  }, [navigate, recipeId])

  // A step picked on the Recipe Process page: the editor opens on it (selected and centred).
  const [stepFocusRequest, setStepFocusRequest] = useState<{ processId: number; nodeId: string; token: number } | null>(null)
  const openStep = useCallback((processId: number, nodeId: string) => {
    setStepFocusRequest({ processId, nodeId, token: Date.now() })
    navigate(recipeToolPath(recipeId, 'editor'))
  }, [navigate, recipeId])

  useEffect(() => {
    let cancelled = false
    // No sync setLoading(true) here: `loading` already starts true (useState(true) above), and this
    // effect's deps are just [recipeId] — the caller (App.tsx's RecipeToolRoute) keys this component
    // by recipeId, so a different recipe means a fresh mount (fresh initial state) rather than this
    // effect re-running in place on an existing instance.
    RecipeDetailApi.getRecipeDetail(recipeId)
      .then(async (result) => {
        // Every recipe the owner opens gets its MAIN process before the session below loads, so
        // the tool always opens on MAIN. The endpoint is idempotent (safe to race, e.g. StrictMode's
        // double effect). Runs before RecipeSessionProvider mounts (it only mounts
        // once `loading` is false), so the session's single process load already includes it.
        if (result.mainProcessId == null && result.createdBy === currentUserId) {
          try {
            const main = await RecipeDetailApi.createMainProcess(recipeId)
            return { ...result, mainProcessId: main.id }
          } catch (error) {
            // Not fatal: the Recipe Editor tab still offers "Create Main Process" / AI generation.
            console.error('Unable to prepare this recipe\'s main process:', error)
          }
        }
        return result
      })
      .then((result) => {
        if (!cancelled) {
          setRecipe(result)
          setLoadError(null)
        }
      })
      .catch((error) => {
        if (!cancelled) {
          setLoadError(errorMessage(error, 'Unable to load this recipe'))
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [recipeId, currentUserId])

  const isOwner = recipe != null && recipe.createdBy != null && recipe.createdBy === currentUserId
  // Someone else's public recipe stays read-only until the reader adds their own copy.
  const addToMyRecipes = useAddToMyRecipes(recipe, isOwner, currentUserId)

  const handleMainProcessChanged = useCallback((mainProcessId: number) => {
    setRecipe((current) => (current ? { ...current, mainProcessId } : current))
  }, [])

  const handleNutritionSaved = useCallback((nutrition: NutritionInfo | null) => {
    setRecipe((current) => (current ? { ...current, nutrition } : current))
  }, [])

  const savePreparationNotes = useCallback(async (preparationNotes: string | null) => {
    const updated = await RecipeApi.updatePreparationNotes(recipeId, preparationNotes)
    setRecipe((current) => (current ? { ...current, preparationNotes: updated.preparationNotes ?? null } : current))
    notifySuccess(updated.preparationNotes ? 'Preparation notes saved for this recipe.' : 'Preparation notes reset to the default.')
  }, [recipeId, notifySuccess])

  // --- owner actions (the header's ⋯ menu) ---

  const saveDetails = async (details: RecipeDetailsDraft) => {
    const updated = await RecipeApi.updateRecipe(recipeId, details)
    setRecipe((current) => (current ? { ...current, name: updated.name, description: updated.description } : current))
    setEditingDetails(false)
    notifySuccess('Recipe details saved.')
  }

  const toggleVisibility = async (blockers: number) => {
    if (!recipe) return
    const makingPublic = recipe.visibility !== 'PUBLIC'
    // Recipes are shared so others can reproduce them: say so when this one isn't finished yet.
    const unfinished = makingPublic && blockers > 0
      ? `\n\nHeads up: this recipe still has ${blockers} ${blockers === 1 ? 'issue' : 'issues'} to fix, so others may not be able to follow or cook it yet.`
      : ''
    const confirmed = await confirm({
      eyebrow: makingPublic ? 'Publish recipe' : 'Make private',
      title: makingPublic ? 'Publish this recipe?' : 'Make this recipe private?',
      message: makingPublic
        ? `Anyone browsing Discover will be able to view this recipe and add a copy of it to their own kitchen. You can make it private again at any time.${unfinished}`
        : 'This recipe will be removed from Discover and other users will no longer be able to find or add it. Copies already added to other kitchens will not be affected.',
      confirmLabel: makingPublic ? (blockers > 0 ? 'Publish anyway' : 'Publish') : 'Make private',
      tone: 'default',
    })
    if (!confirmed) return
    try {
      const updated = await RecipeApi.updateVisibility(recipeId, makingPublic ? 'PUBLIC' : 'PRIVATE')
      setRecipe((current) => (current ? { ...current, visibility: updated.visibility } : current))
      notifySuccess(makingPublic ? 'Recipe published — others can now find it in Discover.' : 'Recipe is now private.')
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to update recipe visibility'))
    }
  }

  const deleteRecipe = async () => {
    if (!recipe) return
    const confirmed = await confirm({
      eyebrow: 'Delete recipe',
      title: `Delete "${recipe.name}"?`,
      message:
        'The recipe, its processes and step images will be permanently removed from your kitchen. Orders already placed keep their own copy. This cannot be undone.',
      confirmLabel: 'Delete recipe',
    })
    if (!confirmed) return
    try {
      await RecipeApi.deleteRecipe(recipeId)
      // Unsaved Recipe Tool changes left on this device for a deleted recipe can never be recovered.
      forgetRecipeLocalState(recipeId)
      notifySuccess('Recipe deleted.')
      onBack(true)
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to delete recipe'))
    }
  }

  const copyLink = async () => {
    if (await copyText(recipeShareUrl(recipeId))) notifySuccess('Link copied. Anyone signed in can open this public recipe.')
    else notifyError('Couldn’t copy the link. Copy the address from your browser instead.')
  }

  if (loading) {
    return (
      <div className="flex h-full w-full items-center justify-center" role="status" style={{ color: 'var(--flow-text-muted)' }}>
        Loading recipe…
      </div>
    )
  }

  if (loadError || !recipe) {
    return (
      <div className="flex h-full w-full items-center justify-center p-4">
        <div style={{ width: 'min(100%, 520px)' }}>
          <ErrorState
            title="This recipe couldn’t be opened"
            message={loadError ?? 'It may have been deleted, or made private by its author.'}
          />
          <div className="mt-4 flex justify-center">
            <button type="button" className="vk-btn vk-btn-secondary" onClick={() => onBack(true)}>← Back to My Recipes</button>
          </div>
        </div>
      </div>
    )
  }

  // A recipe always opens in read mode (the Recipe Process); only its owner can switch to the editor.
  if (!view || (view === 'editor' && !isOwner)) {
    return <Navigate to={recipeToolPath(recipeId, 'process')} replace />
  }

  return (
    <RecipeSessionProvider recipeId={recipeId} canEdit={isOwner}>
      {/* `flow-canvas-container` strips .kitchen-body's ambient page padding/scroll (see
          KitchenLayout.css) so the editor's canvas can use the full available area edge-to-edge;
          the Recipe Process manages its own padding/scroll. */}
      <div className="flow-canvas-container flex h-full w-full flex-col" style={{ background: 'var(--flow-surface-muted)' }}>
        <RecipeToolNavbar
          recipe={recipe}
          readOnly={!isOwner}
          addToMyRecipes={addToMyRecipes}
          onBack={() => onBack(isOwner)}
          onOrderRecipe={isOwner && onOrderRecipe
            ? (ensureReady) => onOrderRecipe({ recipeId: recipe.id, recipeName: recipe.name, servings: recipe.nutrition?.servings, ensureReady })
            : undefined}
          ordering={orderingRecipeId === recipe.id}
          onShowIssue={isOwner ? showIssue : undefined}
          onEditDetails={isOwner ? () => setEditingDetails(true) : undefined}
          onToggleVisibility={isOwner ? (blockers) => void toggleVisibility(blockers) : undefined}
          onCopyLink={() => void copyLink()}
          onDeleteRecipe={isOwner ? () => void deleteRecipe() : undefined}
        />

        <EditRecipeDetailsModal
          recipe={editingDetails ? recipe : null}
          onSave={saveDetails}
          onClose={() => setEditingDetails(false)}
        />

        {view === 'editor' && isOwner && (
          <div className="rt-small-screen-note" role="note">
            <span aria-hidden>💻</span>
            <span>Editing works best on a larger screen.</span>
            <Link to={recipeToolPath(recipeId, 'process')}>View the Recipe Process →</Link>
          </div>
        )}

        {editorMounted && isOwner && (
          <div className="min-h-0 flex-1" style={{ display: view === 'editor' ? 'flex' : 'none', flexDirection: 'column' }}>
            <RecipeEditorView
              recipeId={recipeId}
              recipeName={recipe.name}
              isOwner={isOwner}
              onMainProcessChanged={handleMainProcessChanged}
              preparationNotes={recipe.preparationNotes ?? null}
              onSavePreparationNotes={isOwner ? savePreparationNotes : undefined}
              issueFocusRequest={issueFocusRequest}
              stepFocusRequest={stepFocusRequest}
              openAiCreationOnStart={openAiCreationOnStart}
            />
          </div>
        )}

        {view === 'process' && (
          <RecipeViewPage
            recipe={recipe}
            isOwner={isOwner}
            addToMyRecipes={addToMyRecipes}
            onNutritionSaved={handleNutritionSaved}
            onOpenStep={openStep}
          />
        )}
      </div>
    </RecipeSessionProvider>
  )
}
