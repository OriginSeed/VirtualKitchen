import type { RecipeStoryboardResponse } from '../../../../types/recipe'
import RecipeStoryboardResult from './RecipeStoryboardResult'

/**
 * The recipe's storyboard on the Recipe Process page: the one combined image, shown to the owner and to readers of
 * a public recipe alike. Its panels are not listed here: each one already illustrates its steps further down the
 * page. Renders nothing for a storyboard without an image.
 */
export default function RecipeStoryboardSection({ storyboard, anchorId }: { storyboard: RecipeStoryboardResponse; anchorId: string }) {
  if (!storyboard.imageUrl) return null

  return (
    <section id={anchorId} className="rp-section" aria-labelledby={`${anchorId}-title`}>
      <div className="rp-section-header">
        <h2 id={`${anchorId}-title`} className="rp-section-title">
          <span className="rp-section-title-icon" aria-hidden>🎞️</span>
          Visuals
        </h2>
        <p className="rp-section-subtitle">The whole recipe at a glance, one panel per cooking action.</p>
      </div>
      <RecipeStoryboardResult storyboard={storyboard} />
    </section>
  )
}
