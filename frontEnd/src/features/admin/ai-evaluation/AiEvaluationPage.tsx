import { useEffect, useMemo, useState } from 'react'
import { NavLink, useParams } from 'react-router-dom'
import { AiAnalyticsApi } from '../ai-analytics/api/aiAnalyticsApi'
import { configureDisplayCurrency } from '../ai-analytics/model/analyticsFormat'
import { useApiData } from '../../../shared/hooks/useApiData'
import { AiEvaluationApi } from './api/aiEvaluationApi'
import EvaluationFilterBar from './components/EvaluationFilterBar'
import { DEFAULT_FILTERS, toEvaluationParams } from './model/evaluationFormat'
import type { EvaluationFilters } from './model/evaluationFormat'
import EvaluationComparePage from './pages/EvaluationComparePage'
import EvaluationQueuePage from './pages/EvaluationQueuePage'
import EvaluationSetPage from './pages/EvaluationSetPage'
import '../ai-analytics/aiAnalytics.css'
import './aiEvaluation.css'

const FILTERS_KEY = 'ai-evaluation:filters'

/** Filters survive moving between the queue, a set and the comparison (per browser tab only). */
function loadFilters(): EvaluationFilters {
  try {
    const saved = sessionStorage.getItem(FILTERS_KEY)
    return saved ? { ...DEFAULT_FILTERS, ...(JSON.parse(saved) as Partial<EvaluationFilters>) } : DEFAULT_FILTERS
  } catch {
    return DEFAULT_FILTERS
  }
}

/**
 * Admin AI evaluation: score generated step images by hand (queue → set), and compare models on quality, cost and
 * time. The route is only offered to admins; the backend enforces it on every call.
 */
export default function AiEvaluationPage() {
  const { tab, setKey } = useParams()
  const [filters, setFilters] = useState<EvaluationFilters>(loadFilters)
  const options = useApiData('options', () => AiAnalyticsApi.options())
  const rubric = useApiData('rubric', () => AiEvaluationApi.rubric())
  if (options.data) configureDisplayCurrency(options.data.displayCurrency, options.data.usdToDisplay ?? null)

  useEffect(() => {
    try {
      sessionStorage.setItem(FILTERS_KEY, JSON.stringify(filters))
    } catch {
      // Storage unavailable: filters just won't survive navigation.
    }
  }, [filters])

  // Anchored when the filters change, so re-renders don't refetch.
  const params = useMemo(() => toEvaluationParams(filters, new Date()), [filters])
  const models = options.data?.values.model ?? []
  const view = setKey ? 'set' : tab === 'compare' ? 'compare' : 'queue'

  return (
    <div className="aia-root">
      <header className="aia-header">
        <div>
          <h1>AI evaluation</h1>
          <p className="aia-muted">
            Score generated step images, then compare models on quality, cost and time. Cost and time are recorded
            automatically; quality comes from your scores.
          </p>
        </div>
      </header>
      <nav className="aia-tabs" aria-label="AI evaluation views">
        <NavLink end to="/kitchen/admin/ai-evaluation" className={({ isActive }) => `aia-tab ${isActive || view === 'set' ? 'is-active' : ''}`}>
          Image sets
        </NavLink>
        <NavLink to="/kitchen/admin/ai-evaluation/compare" className={({ isActive }) => `aia-tab ${isActive ? 'is-active' : ''}`}>
          Compare models
        </NavLink>
      </nav>

      {rubric.error && <div className="aia-alert aia-alert--bad" role="alert">{rubric.error}</div>}
      {!rubric.data && !rubric.error && <div className="aia-loading">Loading…</div>}
      {rubric.data && view === 'set' && setKey && (
        <EvaluationSetPage setKey={setKey} rubric={rubric.data} params={params} />
      )}
      {rubric.data && view !== 'set' && (
        <>
          <EvaluationFilterBar filters={filters} models={models} showStatus={view === 'queue'} onChange={setFilters} />
          {view === 'queue'
            // Keyed by the filters, so a filter change starts again from the first page.
            ? <EvaluationQueuePage key={JSON.stringify(params)} params={params} />
            : <EvaluationComparePage params={params} rubric={rubric.data} />}
        </>
      )}
    </div>
  )
}
