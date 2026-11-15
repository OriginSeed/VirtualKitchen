import { useEffect } from 'react'
import { Navigate, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useOrderRecipeEntry } from '../../features/recipe-order'
import {
  isRecipeToolView,
  RecipeToolPage,
  recipeToolPath,
  seedAiRecipeCreation,
} from '../../features/recipe-tool'
import RecipeLibraryPage, { type CreatedRecipe } from '../../features/recipes/RecipeLibraryPage'
import { useSignedIn } from '../session/signedIn'

const toLibrary = <Navigate to="/kitchen/recipes" replace />

/** Opens the editor for a just-created recipe — with AI Recipe Creation ready to run for an AI start. */
const useOpenCreatedRecipe = () => {
  const navigate = useNavigate()
  return ({ recipeId, mode, recipeText }: CreatedRecipe) => {
    if (mode === 'ai' && recipeText) {
      seedAiRecipeCreation(recipeId, recipeText)
      navigate(`${recipeToolPath(recipeId, 'editor')}?start=ai`)
    } else {
      navigate(recipeToolPath(recipeId, 'editor'))
    }
  }
}

/**
 * My Recipes. `?create=1` (the navbar's "+ New recipe") opens the create dialog; the parameter is dropped from
 * the address once seen, so a reload doesn't reopen it. Old `?tab=global` links go to Discover.
 */
export function MyRecipesRoute() {
  const { user } = useSignedIn()
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams, setSearchParams] = useSearchParams()
  const openCreatedRecipe = useOpenCreatedRecipe()
  const { requestOrder, dialog } = useOrderRecipeEntry()

  const createRequested = searchParams.get('create') === '1'
  useEffect(() => {
    if (!createRequested) return
    setSearchParams((params) => {
      params.delete('create')
      return params
    }, { replace: true })
  }, [createRequested, setSearchParams])

  if (searchParams.get('tab') === 'global') return <Navigate to="/kitchen/discover" replace />

  return (
    <>
      <RecipeLibraryPage
        userId={user.id}
        scope="mine"
        onOpenRecipe={(recipeId) => navigate(recipeToolPath(recipeId, 'process'))}
        onEditRecipe={(recipeId) => navigate(recipeToolPath(recipeId, 'editor'))}
        onCreateRecipe={openCreatedRecipe}
        onOrderRecipe={(recipe) => requestOrder({ recipeId: recipe.id, recipeName: recipe.name, isOwner: true })}
        createRequestKey={createRequested ? location.key : null}
      />
      {dialog}
    </>
  )
}

/** Discover: other people's public recipes, opened read-only in their Recipe Process. */
export function DiscoverRoute() {
  const { user } = useSignedIn()
  const navigate = useNavigate()
  return (
    <RecipeLibraryPage
      userId={user.id}
      scope="discover"
      onOpenRecipe={(recipeId) => navigate(recipeToolPath(recipeId, 'process'))}
    />
  )
}

/**
 * The Recipe Tool: the Recipe Editor (`tool/editor`) and the Recipe Process (`tool/process`). `?start=ai` (set
 * right after creating a recipe with "Describe it for AI") opens AI Recipe Creation; it is dropped from the
 * address straight away.
 */
export function RecipeToolRoute() {
  const { user } = useSignedIn()
  const navigate = useNavigate()
  const { requestOrder, dialog, orderingRecipeId } = useOrderRecipeEntry()
  const { recipeId: recipeIdParam, view } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const recipeId = Number(recipeIdParam)
  const startAi = searchParams.get('start') === 'ai'

  useEffect(() => {
    if (!startAi) return
    setSearchParams((params) => {
      params.delete('start')
      return params
    }, { replace: true })
  }, [startAi, setSearchParams])

  if (!Number.isFinite(recipeId)) return toLibrary

  // An unknown view segment falls back to the bare tool URL, which picks the default view.
  if (view !== undefined && !isRecipeToolView(view)) {
    return <Navigate to={recipeToolPath(recipeId)} replace />
  }

  return (
    <>
      <RecipeToolPage
        // Fresh mount per recipe (fresh initial state) rather than resetting in place.
        key={recipeId}
        recipeId={recipeId}
        currentUserId={user.id}
        view={view}
        onBack={(isOwner) => navigate(isOwner ? '/kitchen/recipes' : '/kitchen/discover')}
        onOrderRecipe={(order) => requestOrder({ ...order, isOwner: true })}
        orderingRecipeId={orderingRecipeId}
        startAiCreation={startAi}
      />
      {dialog}
    </>
  )
}

/** The old standalone process pages (`/processes`, `/process/:id`) were folded into the Recipe Editor. */
export function LegacyProcessRedirect() {
  const { recipeId: recipeIdParam } = useParams()
  const recipeId = Number(recipeIdParam)
  if (!Number.isFinite(recipeId)) return toLibrary
  return <Navigate to={recipeToolPath(recipeId, 'editor')} replace />
}
