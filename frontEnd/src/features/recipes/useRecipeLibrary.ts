import { RecipeApi, type Recipe } from '../../api'
import { forgetRecipeLocalState, recipeShareUrl, type RecipeDetailsDraft } from '../recipe-tool'
import { copyText } from '../../shared/clipboard'
import { useConfirm } from '../../shared/components/confirm/confirmContext'
import { useNotifications } from '../../shared/components/notifications/notificationContext'
import { errorMessage } from '../../shared/errors'
import { useApiData } from '../../shared/hooks/useApiData'

/** Which list a library page shows: the user's own recipes, or other people's public ones. */
export type RecipeScope = 'mine' | 'discover'

const NO_RECIPES: Recipe[] = []

/**
 * The recipe library's data and actions. The user's own recipes always load (Discover needs them too, to know
 * which public recipes were already added); the public ones only on Discover. Load failures are returned for
 * the page to show with a retry; action failures are toasts, and each action resolves to whether it succeeded.
 */
export function useRecipeLibrary(userId: number, scope: RecipeScope) {
  const { notifySuccess, notifyError } = useNotifications()
  const confirm = useConfirm()

  const mine = useApiData(`mine-${userId}`, async () => (await RecipeApi.getRecipesByUserId(userId)) ?? [])
  const global = useApiData(scope === 'discover' ? `global-${userId}` : null,
    async () => (await RecipeApi.getGlobalRecipes(userId)) ?? [])

  // The update endpoints don't resolve thumbnails, so an updated recipe keeps the one already shown.
  const replaceRecipe = (updated: Recipe) =>
    mine.mutate((recipes) => recipes.map((item) => (item.id === updated.id ? { ...item, ...updated, thumbnailUrl: item.thumbnailUrl } : item)))

  /** Creates a recipe and returns its id; throws (with the backend's message) on failure. */
  const createRecipe = async (name: string, description: string): Promise<number> => {
    const result = await RecipeApi.createRecipe({ name, description })
    if (!result.id) throw new Error('Recipe created but no id returned')
    return result.id
  }

  const deleteRecipe = async (recipe: Recipe) => {
    const confirmed = await confirm({
      eyebrow: 'Delete recipe',
      title: `Delete "${recipe.name}"?`,
      message:
        'The recipe, its processes and step images will be permanently removed from your kitchen. Orders already placed keep their own copy. This cannot be undone.',
      confirmLabel: 'Delete recipe',
    })
    if (!confirmed) return
    try {
      await RecipeApi.deleteRecipe(recipe.id)
      // Unsaved Recipe Tool changes left on this device for a deleted recipe can never be recovered.
      forgetRecipeLocalState(recipe.id)
      mine.mutate((recipes) => recipes.filter((item) => item.id !== recipe.id))
      notifySuccess('Recipe deleted.')
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to delete recipe'))
    }
  }

  /** Saves a recipe's name and description; throws on failure so the edit dialog can show the error. */
  const saveDetails = async (recipe: Recipe, details: RecipeDetailsDraft) => {
    replaceRecipe(await RecipeApi.updateRecipe(recipe.id, details))
    notifySuccess('Recipe details saved.')
  }

  const toggleVisibility = async (recipe: Recipe): Promise<boolean> => {
    const nextVisibility = recipe.visibility === 'PUBLIC' ? 'PRIVATE' : 'PUBLIC'
    try {
      replaceRecipe(await RecipeApi.updateVisibility(recipe.id, nextVisibility))
      notifySuccess(nextVisibility === 'PUBLIC'
        ? 'Recipe is now public — others can find it in Discover.'
        : 'Recipe is now private.')
      return true
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to update recipe visibility'))
      return false
    }
  }

  /** Copies a public recipe into the user's kitchen; returns the copy's id, or null when it failed. */
  const addToMyRecipes = async (recipe: Recipe): Promise<number | null> => {
    try {
      const copy = await RecipeApi.copyRecipe(recipe.id)
      mine.reload()
      notifySuccess('Recipe added to My Recipes.')
      return copy.id
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to add recipe to My Recipes'))
      return null
    }
  }

  const copyLink = async (recipe: Recipe) => {
    if (await copyText(recipeShareUrl(recipe.id))) notifySuccess('Link copied. Anyone signed in can open this public recipe.')
    else notifyError('Couldn’t copy the link. Open the recipe and copy the address from your browser instead.')
  }

  return {
    recipes: mine.data ?? NO_RECIPES,
    recipesLoading: mine.loading,
    recipesError: mine.error,
    reloadRecipes: mine.reload,
    globalRecipes: global.data ?? NO_RECIPES,
    globalRecipesLoading: global.loading,
    globalRecipesError: global.error,
    reloadGlobalRecipes: global.reload,
    createRecipe,
    deleteRecipe,
    saveDetails,
    toggleVisibility,
    addToMyRecipes,
    copyLink,
  }
}
