import { useCallback, useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { RecipeApi } from '../../../api'
import { useNotifications } from '../../../shared/components/notifications/notificationContext'
import { errorMessage } from '../../../shared/errors'
import type { RecipeDetail } from '../../../types/recipe'
import { recipeToolPath } from '../recipeToolRoutes'

export type AddToMyRecipes = {
  /** The reader's own copy of this recipe, if they already added it; undefined while still checking. */
  existingCopyId: number | null | undefined
  adding: boolean
  /** Copies the recipe into the reader's kitchen and opens the copy in read mode. */
  add: () => Promise<void>
  /** Opens the reader's existing copy in read mode. */
  openCopy: () => void
}

/**
 * "Add to My Recipes" for a reader of someone else's public recipe: whether they already have a copy
 * (recipes record the one they were copied from), and adding one. Null for the owner, and for a recipe
 * that can't be copied (not public).
 */
export function useAddToMyRecipes(recipe: RecipeDetail | null, isOwner: boolean, currentUserId: number): AddToMyRecipes | null {
  const navigate = useNavigate()
  const { notifySuccess, notifyError } = useNotifications()
  const enabled = recipe != null && !isOwner && recipe.visibility === 'PUBLIC'
  const recipeId = recipe?.id
  const [existingCopyId, setExistingCopyId] = useState<number | null | undefined>(undefined)
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    if (!enabled || recipeId == null) return
    let cancelled = false
    RecipeApi.getRecipesByUserId(currentUserId)
      .then((mine) => {
        if (cancelled) return
        const copy = (mine ?? []).find((item) => item.copiedFromRecipeId === recipeId)
        setExistingCopyId(copy?.id ?? null)
      })
      // Not knowing only means offering "Add" — the copy itself still works.
      .catch(() => { if (!cancelled) setExistingCopyId(null) })
    return () => { cancelled = true }
  }, [enabled, recipeId, currentUserId])

  const add = useCallback(async () => {
    if (recipeId == null || adding) return
    setAdding(true)
    try {
      const copy = await RecipeApi.copyRecipe(recipeId)
      notifySuccess('Recipe added to your kitchen — it\'s yours to edit now.')
      navigate(recipeToolPath(copy.id, 'process'))
    } catch (error) {
      notifyError(errorMessage(error, 'Unable to add recipe to My Recipes'))
      setAdding(false)
    }
  }, [recipeId, adding, notifySuccess, notifyError, navigate])

  const openCopy = useCallback(() => {
    if (existingCopyId != null) navigate(recipeToolPath(existingCopyId, 'process'))
  }, [existingCopyId, navigate])

  if (!enabled) return null
  return { existingCopyId, adding, add, openCopy }
}
