/**
 * The recipe tool's public API: everything the app shell and other features use. Code outside
 * recipe-tool imports from here, never from its folders directly.
 */
import './view/styles/recipe-view.css'

export { default as RecipeToolPage } from './page/RecipeToolPage'
export { default as EditRecipeDetailsModal, type RecipeDetailsDraft } from './page/EditRecipeDetailsModal'
export { forgetRecipeLocalState } from './session/forgetRecipeLocalState'
export { JobTrackerProvider } from './jobs/JobTrackerContext'
export { default as AiJobIndicator } from './jobs/AiJobIndicator'
export { isRecipeToolView, recipeShareUrl, recipeToolPath, type RecipeToolView } from './recipeToolRoutes'
export { seedAiRecipeCreation } from './workflow/seedAiRecipeCreation'

// Recipe files: what the editor's Export writes, and creating a recipe from one ("New recipe → Import from file").
export { parseFlowText as parseRecipeFile, summarizeFlow as summarizeRecipeFile, type FlowDoc as RecipeFile } from './model/recipeFlowFile.ts'
export { createRecipeFromFile, describeMissingAssets } from './session/recipeFile'

// The read-only recipe view, reused by recipe orders to show what is being cooked.
export { default as RecipeImage } from './view/components/RecipeImage'
export {
  buildRecipePresentation,
  formatTotalMinutes,
  type RecipePresentation,
  type RecipeTimelineStep,
} from './view/model/recipeView'
