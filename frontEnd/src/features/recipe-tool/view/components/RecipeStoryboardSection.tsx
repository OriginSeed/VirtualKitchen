import { RecipeStoryboardApi } from '../../../../api'
import { useApiData } from '../../../../shared/hooks/useApiData'
import { storyboardJobKey } from '../../jobs/jobTracker'
import { useTrackedJob } from '../../jobs/useJobTracker'
import RecipeStoryboardResult from './RecipeStoryboardResult'

/**
 * The recipe's storyboard on the Recipe Process page: shown once the recipe has a finished one, to its owner and to
 * readers of a public recipe alike. Renders nothing while there is none (it is made from the editor's AI menu).
 */
export default function RecipeStoryboardSection({ recipeId, anchorId }: { recipeId: number; anchorId: string }) {
  // Read again when a generation this page is following finishes.
  const tracked = useTrackedJob(storyboardJobKey(recipeId))
  const finishedJobId = tracked?.job.status === 'COMPLETED' ? tracked.job.jobId : ''
  const latest = useApiData(`recipe-storyboard:${recipeId}:${finishedJobId}`, () => RecipeStoryboardApi.getLatest(recipeId))

  const storyboard = latest.data
  if (!storyboard || !storyboard.imageUrl) return null

  return (
    <section id={anchorId} className="rp-section" aria-labelledby={`${anchorId}-title`}>
      <div className="rp-section-header">
        <h2 id={`${anchorId}-title`} className="rp-section-title">
          <span className="rp-section-title-icon" aria-hidden>🎞️</span>
          Storyboard
        </h2>
        <p className="rp-section-subtitle">The whole recipe at a glance, one panel per cooking action.</p>
      </div>
      <RecipeStoryboardResult storyboard={storyboard} />
    </section>
  )
}
