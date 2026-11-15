import { useState } from 'react'
import { useRecipeSession } from '../session/sessionContext'
import { useCookModeGate } from '../session/useRecipeReadiness'
import { buildCookModeSlides } from '../view/model/cookModeSlides'
import { buildRecipePresentation } from '../view/model/recipeView'
import RecipeVisualizationSlideshow, { type SlideshowStep } from '../walkthrough/RecipeVisualizationSlideshow'

/**
 * Cook mode: the whole recipe, one step at a time, narrated — the one walkthrough of the whole recipe, opened from
 * the header or the Recipe Process. A recipe that isn't ready yet can still be previewed (see useCookModeGate).
 * Render `element` once and call `open` from a button.
 */
export function useCookMode(recipeId: number) {
  const session = useRecipeSession()
  const gate = useCookModeGate()
  const [walkthrough, setWalkthrough] = useState<{ steps: SlideshowStep[]; preview: boolean; ready: Promise<boolean> } | null>(null)

  const available = Boolean(session && !session.loading && !session.loadError)

  const open = async () => {
    if (!session || !available) return
    const decision = await gate()
    if (!decision) return
    const presentation = buildRecipePresentation(session.getProcesses())
    // The gate has already saved the owner's edits, so narration can follow the saved steps right away.
    setWalkthrough({ steps: buildCookModeSlides(presentation.sections), preview: decision === 'preview', ready: Promise.resolve(true) })
  }

  const element = walkthrough && (
    <RecipeVisualizationSlideshow
      steps={walkthrough.steps}
      recipeId={recipeId}
      processId={null}
      narrationReady={walkthrough.ready}
      heading={walkthrough.preview ? '👁 Recipe preview (not finished yet)' : '👩‍🍳 Cook mode'}
      onClose={() => setWalkthrough(null)}
    />
  )

  return { open, element, available }
}
