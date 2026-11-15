import { draftKeys, writeDraft } from '../../../shared/drafts/draftStore'
import { createWorkflowTaskSelection } from './model/workflowSelection'

/**
 * Prefills AI Recipe Creation for a just-created recipe with the dish the user described, so the editor can open
 * the dialog ready to run (all tasks ticked). Uses the dialog's own draft, which it clears once the generated
 * process has been loaded.
 */
export function seedAiRecipeCreation(recipeId: number, recipeText: string) {
  const text = recipeText.trim()
  if (!text) return
  writeDraft(draftKeys.aiRecipeCreation(recipeId), { selection: createWorkflowTaskSelection(true), recipeText: text })
}
