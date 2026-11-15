/**
 * A one-line summary of the AI work running in the background, for the app's navbar. Pure, so it is unit-tested;
 * the tracked jobs are reduced to the few fields it needs.
 */

export type ActiveJobInfo = {
  kind: 'generation' | 'visuals' | 'workflow'
  recipeId: number
  /** A workflow paused until the user approves the generated process. */
  waitingForApproval: boolean
}

export type ActiveJobsSummary = {
  /** Jobs still running or waiting. */
  count: number
  /** The recipe to open: one waiting for approval first, else the most recently listed one. */
  recipeId: number | null
  /** Something is blocked on the user. */
  needsReview: boolean
  label: string
}

export const summarizeActiveJobs = (jobs: ActiveJobInfo[]): ActiveJobsSummary => {
  if (jobs.length === 0) return { count: 0, recipeId: null, needsReview: false, label: '' }
  const waiting = jobs.find((job) => job.waitingForApproval)
  const target = waiting ?? jobs[jobs.length - 1]
  const recipeCount = new Set(jobs.map((job) => job.recipeId)).size
  let label: string
  if (waiting) label = 'AI draft ready for review'
  else if (recipeCount > 1) label = `AI working on ${recipeCount} recipes`
  else label = 'AI working…'
  return { count: jobs.length, recipeId: target.recipeId, needsReview: Boolean(waiting), label }
}

/** A stable string for `useSyncExternalStore`: changes only when the summary would. */
export const activeJobsKey = (jobs: ActiveJobInfo[]): string =>
  jobs.map((job) => `${job.kind}:${job.recipeId}:${job.waitingForApproval ? 1 : 0}`).join('|')

const KINDS = new Set<ActiveJobInfo['kind']>(['generation', 'visuals', 'workflow'])

/** The jobs an `activeJobsKey` string was made from (malformed parts are skipped). */
export const parseActiveJobsKey = (key: string): ActiveJobInfo[] => {
  if (!key) return []
  return key.split('|').flatMap((part) => {
    const [kind, recipeId, waiting] = part.split(':')
    const id = Number(recipeId)
    if (!KINDS.has(kind as ActiveJobInfo['kind']) || !Number.isFinite(id)) return []
    return [{ kind: kind as ActiveJobInfo['kind'], recipeId: id, waitingForApproval: waiting === '1' }]
  })
}
