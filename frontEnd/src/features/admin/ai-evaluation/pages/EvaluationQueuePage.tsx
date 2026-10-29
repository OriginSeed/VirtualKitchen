import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useApiData } from '../../../../shared/hooks/useApiData'
import { LoadState } from '../../ai-analytics/components/common'
import { formatCost, formatDateTime, formatMs } from '../../ai-analytics/model/analyticsFormat'
import { AiEvaluationApi } from '../api/aiEvaluationApi'
import type { QueryParams, SetSummary } from '../api/aiEvaluationApi'
import { STATUS_LABELS, formatScore, setRoute, statusTone } from '../model/evaluationFormat'

const PAGE_SIZE = 25

/** Image sets to score, unscored first. A row opens the set. */
export default function EvaluationQueuePage({ params }: { params: QueryParams }) {
  const navigate = useNavigate()
  const [page, setPage] = useState(0)
  const query = { ...params, sort: 'unscored', page, size: PAGE_SIZE }
  const sets = useApiData(`sets?${JSON.stringify(query)}`, () => AiEvaluationApi.sets(query))
  const data = sets.data?.page
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1

  return (
    <section className="aia-page" aria-label="Image sets">
      <LoadState loading={sets.loading && !sets.data} error={sets.error} onRetry={sets.reload} />
      {sets.data?.truncated && (
        <p className="aia-notes">Only the most recent {sets.data.setsScanned.toLocaleString()} sets were read; narrow the period.</p>
      )}
      {data && data.items.length === 0 && (
        <p className="aia-empty">No generated step images in this period. Generate visuals for a recipe, then score them here.</p>
      )}
      {data && data.items.length > 0 && (
        <div className={`aia-table-scroll ${sets.loading ? 'is-loading' : ''}`}>
          <table className="aia-table">
            <thead>
              <tr>
                <th>Recipe</th>
                <th>Model</th>
                <th className="aia-num">Images</th>
                <th className="aia-num">Failed</th>
                <th className="aia-num">Cost</th>
                <th className="aia-num">Time</th>
                <th>Generated</th>
                <th>Status</th>
                <th className="aia-num">Score</th>
                <th>Verdict</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((set) => (
                <tr key={set.setKey} className="aia-clickable" tabIndex={0}
                  onClick={() => navigate(setRoute(set.setKey))}
                  onKeyDown={(e) => { if (e.key === 'Enter') navigate(setRoute(set.setKey)) }}>
                  <td>{set.recipeName ?? 'Unknown recipe'}</td>
                  <td>
                    {set.modelKey ?? '—'}
                    {set.mixedModels && <span className="aia-tag aia-tag--warn" title={set.models.join(', ')}>mixed</span>}
                  </td>
                  <td className="aia-num">{set.imageCount}</td>
                  <td className="aia-num">{set.failedRequests || '—'}</td>
                  <td className="aia-num">
                    {formatCost(set.totalCost, set.currency)}
                    {!set.costComplete && set.totalCost !== null && <span className="aia-tag aia-tag--warn">partial</span>}
                  </td>
                  <td className="aia-num">{formatMs(set.spanMs)}</td>
                  <td>{formatDateTime(set.startedAt)}</td>
                  <td><StatusPill set={set} /></td>
                  <td className="aia-num">{formatScore(set.recipeScore)}</td>
                  <td><VerdictLabel set={set} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
      {data && pages > 1 && (
        <div className="aia-pager">
          <button type="button" className="aia-button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
          <span>Page {page + 1} of {pages} · {data.total} sets</span>
          <button type="button" className="aia-button" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
        </div>
      )}
    </section>
  )
}

function StatusPill({ set }: { set: SetSummary }) {
  const tone = statusTone(set.status)
  return (
    <span className={`aia-badge aia-badge--${tone}`}>
      {STATUS_LABELS[set.status]}
      {set.status === 'PARTIAL' && ` ${set.scoredImages}/${set.imageCount}`}
    </span>
  )
}

export function VerdictLabel({ set }: { set: Pick<SetSummary, 'verdict' | 'accepted' | 'hardFail'> }) {
  if (!set.verdict) return <span className="aia-muted">—</span>
  if (set.verdict === 'REJECTED') return <span className="aia-badge aia-badge--bad">Rejected</span>
  return set.accepted
    ? <span className="aia-badge aia-badge--good">Accepted</span>
    : <span className="aia-badge aia-badge--warn" title="A step scored too low on correctness">Accepted · hard fail</span>
}
