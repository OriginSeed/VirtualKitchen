import { useCallback, useMemo, useSyncExternalStore } from 'react'
import { Link } from 'react-router-dom'
import { recipeToolPath } from '../recipeToolRoutes'
import { activeJobsKey, parseActiveJobsKey, summarizeActiveJobs, type ActiveJobInfo } from './activeJobsSummary'
import { isTrackedJobActive } from './jobTracker'
import { useJobTracker } from './useJobTracker'
import './AiJobIndicator.css'

/**
 * A small navbar pill while AI work runs in the background (generation, visuals, AI Recipe Creation), so the user
 * knows it is still going — and when a draft waits for their approval — wherever they are in the app.
 */
export default function AiJobIndicator() {
  const tracker = useJobTracker()
  // The snapshot is a string, so it only changes (and re-renders) when the summary would.
  const getKey = useCallback(() => activeJobsKey(
    tracker.all().filter(isTrackedJobActive).map((tracked): ActiveJobInfo => ({
      kind: tracked.kind,
      recipeId: tracked.recipeId,
      waitingForApproval: tracked.kind === 'workflow' && tracked.job.status === 'WAITING_FOR_APPROVAL',
    })),
  ), [tracker])
  const key = useSyncExternalStore(tracker.subscribe, getKey)
  const summary = useMemo(() => summarizeActiveJobs(parseActiveJobsKey(key)), [key])

  if (summary.count === 0 || summary.recipeId == null) return null
  return (
    <Link
      to={recipeToolPath(summary.recipeId, 'editor')}
      className={`ai-job-indicator${summary.needsReview ? ' needs-review' : ''}`}
      title={summary.needsReview ? 'Open the recipe to review the AI draft' : 'Open the recipe the AI is working on'}
      aria-live="polite"
    >
      <span className="ai-job-indicator-dot" aria-hidden="true" />
      <span className="ai-job-indicator-label">{summary.label}</span>
    </Link>
  )
}
