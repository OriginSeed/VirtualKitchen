import type { SetStatus } from '../api/aiEvaluationApi'
import { toggle } from '../model/evaluationScoring'
import { RANGE_LABELS, STATUS_LABELS } from '../model/evaluationFormat'
import type { DateRange, EvaluationFilters } from '../model/evaluationFormat'

interface Props {
  filters: EvaluationFilters
  models: string[]
  showStatus: boolean
  onChange: (filters: EvaluationFilters) => void
}

const STATUSES = Object.keys(STATUS_LABELS) as SetStatus[]

export default function EvaluationFilterBar({ filters, models, showStatus, onChange }: Props) {
  return (
    <div className="aie-filters">
      <label className="aie-filter">
        <span className="aia-choice-label">Period</span>
        <select value={filters.range} onChange={(e) => onChange({ ...filters, range: e.target.value as DateRange })}>
          {(Object.keys(RANGE_LABELS) as DateRange[]).map((range) => (
            <option key={range} value={range}>{RANGE_LABELS[range]}</option>
          ))}
        </select>
      </label>
      <div className="aia-choice-row" role="group" aria-label="Models">
        <span className="aia-choice-label">Models</span>
        {models.map((model) => (
          <button key={model} type="button" aria-pressed={filters.models.includes(model)}
            className={`aia-chip ${filters.models.includes(model) ? 'is-active' : ''}`}
            onClick={() => onChange({ ...filters, models: toggle(filters.models, model) })}>
            {model}
          </button>
        ))}
      </div>
      {showStatus && (
        <div className="aia-choice-row" role="group" aria-label="Status">
          <span className="aia-choice-label">Status</span>
          {STATUSES.map((status) => (
            <button key={status} type="button" aria-pressed={filters.statuses.includes(status)}
              className={`aia-chip ${filters.statuses.includes(status) ? 'is-active' : ''}`}
              onClick={() => onChange({ ...filters, statuses: toggle(filters.statuses, status) as SetStatus[] })}>
              {STATUS_LABELS[status]}
            </button>
          ))}
        </div>
      )}
      {!showStatus && (
        <label className="aie-filter aie-check">
          <input type="checkbox" checked={filters.includeMixed}
            onChange={(e) => onChange({ ...filters, includeMixed: e.target.checked })} />
          <span>Include sets that fell back to another model</span>
        </label>
      )}
    </div>
  )
}
