import { useState } from 'react'
import { PHONE_MAX_WIDTH } from '../../../../shared/hooks/useViewport'
import type { ReactNode } from 'react'
import { AiAnalyticsApi } from '../api/aiAnalyticsApi'
import type { AnalyticsRequest } from '../api/aiAnalyticsApi'
import { CostCell, LoadState, StatusBadge } from '../components/common'
import { useApiData } from '../../../../shared/hooks/useApiData'
import { formatDateTime, formatMs, formatTokens, humanize, shortId } from '../model/analyticsFormat'
import { requestKey } from '../model/analyticsQuery'

interface RequestsPageProps {
  params: Record<string, string>
  onOpenRequest: (id: string) => void
  onViewResult: (id: string) => void
}

interface Column {
  id: string
  label: string
  sort?: string
  numeric?: boolean
  render: (r: AnalyticsRequest) => ReactNode
}

const COLUMNS: Column[] = [
  { id: 'time', label: 'Time', sort: 'createdAt', render: (r) => formatDateTime(r.createdAt) },
  { id: 'id', label: 'Request', render: (r) => <span className="aia-mono" title={r.id}>{shortId(r.id)}</span> },
  { id: 'type', label: 'Type', render: (r) => humanize(r.requestType) },
  { id: 'context', label: 'Operation', render: (r) => humanize(r.operationContext) },
  { id: 'provider', label: 'Provider', render: (r) => r.finalProvider ?? <span className="aia-muted">Unknown</span> },
  { id: 'model', label: 'Model', render: (r) => r.resolvedModelKey ?? <span className="aia-muted">Unknown</span> },
  { id: 'status', label: 'Status', render: (r) => <StatusBadge status={r.status} /> },
  { id: 'in', label: 'In', sort: 'inputTokens', numeric: true, render: (r) => formatTokens(r.usageTotals?.inputTokens) },
  { id: 'out', label: 'Out', sort: 'outputTokens', numeric: true, render: (r) => formatTokens(r.usageTotals?.outputTokens) },
  { id: 'cost', label: 'Cost', sort: 'cost', numeric: true, render: (r) => <CostCell cost={r.costTotals} total={r.totalCost} /> },
  { id: 'latency', label: 'Latency', sort: 'latency', numeric: true, render: (r) => formatMs(r.totalMs) },
  { id: 'retries', label: 'Retries', sort: 'retries', numeric: true, render: (r) => (r.transportAttempts == null ? '—' : Math.max(0, r.transportAttempts - 1)) },
  { id: 'recipe', label: 'Recipe / workflow', render: (r) => [r.recipeId && `recipe ${r.recipeId}`, r.stepId && `step ${r.stepId}`, r.workflowId && `wf ${shortId(r.workflowId)}`].filter(Boolean).join(' · ') || '—' },
  { id: 'source', label: 'Source', render: (r) => (r.source === 'LIVE' ? 'Live' : 'Historical') },
]

const DEFAULT_VISIBLE = ['time', 'id', 'context', 'model', 'status', 'in', 'out', 'cost', 'latency', 'retries', 'recipe']
const PHONE_DEFAULT_VISIBLE = ['time', 'context', 'status', 'cost']
const PAGE_SIZES = [25, 50, 100]

/** Searchable, sortable, paginated request explorer; filters, sorting and paging all run on the backend. */
export default function RequestsPage({ params, onOpenRequest, onViewResult }: RequestsPageProps) {
  const [sort, setSort] = useState('createdAt')
  const [direction, setDirection] = useState<'asc' | 'desc'>('desc')
  const [page, setPage] = useState(0)
  const [size, setSize] = useState(25)
  const [visible, setVisible] = useState<string[]>(
    () => (window.innerWidth <= PHONE_MAX_WIDTH ? PHONE_DEFAULT_VISIBLE : DEFAULT_VISIBLE),
  )
  const [columnsOpen, setColumnsOpen] = useState(false)
  const [lastParamsKey, setLastParamsKey] = useState(requestKey(params))

  const paramsKey = requestKey(params)
  if (paramsKey !== lastParamsKey) {
    // New filters: back to the first page.
    setLastParamsKey(paramsKey)
    setPage(0)
  }
  const query = { ...params, sort, direction, page, size }
  const { data, error, loading, reload } = useApiData(`requests?${requestKey(query)}`, () => AiAnalyticsApi.requests(query))

  const shown = COLUMNS.filter((c) => visible.includes(c.id))
  const pages = data ? Math.max(1, Math.ceil(data.total / data.size)) : 1
  const toggleSort = (key: string) => {
    if (sort === key) setDirection(direction === 'desc' ? 'asc' : 'desc')
    else {
      setSort(key)
      setDirection('desc')
    }
    setPage(0)
  }

  return (
    <div className="aia-page">
      <div className="aia-toolbar">
        <span className="aia-muted">{data ? `${data.total.toLocaleString()} requests` : ' '}</span>
        <div className="aia-columns">
          <button type="button" className="aia-button" aria-expanded={columnsOpen} onClick={() => setColumnsOpen(!columnsOpen)}>Columns</button>
          {columnsOpen && (
            <div className="aia-menu">
              {COLUMNS.map((c) => (
                <label key={c.id} className="aia-inline-field">
                  <input type="checkbox" checked={visible.includes(c.id)}
                    onChange={(e) => setVisible(e.target.checked ? [...visible, c.id] : visible.filter((v) => v !== c.id))} />
                  <span>{c.label}</span>
                </label>
              ))}
            </div>
          )}
        </div>
      </div>
      <LoadState loading={loading && !data} error={error} onRetry={reload} />
      {data && (
        <>
          <div className={`aia-table-scroll${loading ? ' is-loading' : ''}`}>
            <table className="aia-table">
              <thead>
                <tr>
                  {shown.map((c) => (
                    <th key={c.id} className={c.numeric ? 'aia-num' : undefined} aria-sort={c.sort && sort === c.sort ? (direction === 'asc' ? 'ascending' : 'descending') : undefined}>
                      {c.sort ? (
                        <button type="button" className="aia-sort" onClick={() => toggleSort(c.sort as string)}>
                          {c.label}{sort === c.sort ? (direction === 'asc' ? ' ▲' : ' ▼') : ''}
                        </button>
                      ) : c.label}
                    </th>
                  ))}
                  <th><span className="aia-sr-only">Actions</span></th>
                </tr>
              </thead>
              <tbody>
                {data.items.length === 0 && (
                  <tr><td colSpan={shown.length + 1} className="aia-empty">No requests match these filters.</td></tr>
                )}
                {data.items.map((r) => (
                  <tr key={r.id} className="aia-clickable" onClick={() => onOpenRequest(r.id)}>
                    {shown.map((c) => <td key={c.id} className={c.numeric ? 'aia-num' : undefined}>{c.render(r)}</td>)}
                    <td className="aia-actions" onClick={(e) => e.stopPropagation()}>
                      <button type="button" className="aia-link" onClick={() => onViewResult(r.id)}
                        title={r.status === 'SUCCEEDED' || r.status === 'REUSED' ? 'View the AI result' : 'View the error and attempts'}>
                        {r.status === 'SUCCEEDED' || r.status === 'REUSED' ? 'View result' : 'View error'}
                      </button>
                      <button type="button" className="aia-link" onClick={() => onOpenRequest(r.id)}>Details</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div className="aia-pager">
            <button type="button" className="aia-button" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
            <span>Page {page + 1} of {pages}</span>
            <button type="button" className="aia-button" disabled={page + 1 >= pages} onClick={() => setPage(page + 1)}>Next</button>
            <label className="aia-inline-field">
              <span>Per page</span>
              <select value={size} onChange={(e) => { setSize(Number(e.target.value)); setPage(0) }}>
                {PAGE_SIZES.map((s) => <option key={s} value={s}>{s}</option>)}
              </select>
            </label>
          </div>
        </>
      )}
    </div>
  )
}
