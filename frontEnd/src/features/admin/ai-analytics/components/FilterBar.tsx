import { useState } from 'react'
import type { AnalyticsFilters, DatePreset } from '../model/analyticsQuery'
import { activeFilterCount, emptyFilters } from '../model/analyticsQuery'
import type { AnalyticsChoice } from '../api/aiAnalyticsApi'
import { humanize } from '../model/analyticsFormat'
import SearchableSelect from '../../../../shared/components/SearchableSelect'
import MultiSelectDropdown from './MultiSelectDropdown'

interface FilterBarProps {
  filters: AnalyticsFilters
  options: Record<string, string[]> | null
  users: AnalyticsChoice[]
  recipes: AnalyticsChoice[]
  onChange: (filters: AnalyticsFilters) => void
}

const PRESETS: { id: DatePreset; label: string }[] = [
  { id: '24h', label: '24h' },
  { id: '7d', label: '7 days' },
  { id: '30d', label: '30 days' },
  { id: '90d', label: '90 days' },
  { id: 'all', label: 'All time' },
  { id: 'custom', label: 'Custom' },
]

type ListKey = 'operationContext' | 'requestType' | 'model' | 'provider' | 'tier' | 'status' | 'source'

/** "What ran" filters: pick any number of values; a request matches if it has any of them. */
const LIST_FILTERS: { key: ListKey; label: string; hint: string }[] = [
  { key: 'operationContext', label: 'Operation', hint: 'What the app used AI for (flow generation, step images, narration…)' },
  { key: 'requestType', label: 'Request type', hint: 'The modality: text→text, text→image or text→audio' },
  { key: 'model', label: 'Model', hint: 'The registry model that served the request (after any credit fallback)' },
  { key: 'provider', label: 'Provider', hint: 'Who ran it: gemini, ollama, drawThings, local TTS…' },
  { key: 'tier', label: 'Category', hint: 'Paid API models vs open-source / local models' },
  { key: 'status', label: 'Status', hint: 'How the request ended' },
  { key: 'source', label: 'Data source', hint: 'Live = recorded as it ran; backfill = recovered from older records (often partial)' },
]

/** Model keys and provider names are shown exactly as configured; enum values are humanized. */
const valueLabel = (key: ListKey, value: string) => (key === 'model' || key === 'provider' ? value : humanize(value))

const withAll = (allLabel: string, choices: AnalyticsChoice[]) =>
  [{ value: '', label: allLabel }, ...choices.map((c) => ({ value: c.id, label: c.label, keywords: [c.id] }))]

/**
 * Date range, then one dropdown per filter (each "All" by default). All filters combine with AND (within one list,
 * any selected value matches) and are applied by the backend.
 */
export default function FilterBar({ filters, options, users, recipes, onChange }: FilterBarProps) {
  const set = <K extends keyof AnalyticsFilters>(key: K, value: AnalyticsFilters[K]) => onChange({ ...filters, [key]: value })
  const reset = () => onChange({ ...emptyFilters(), preset: filters.preset, fromDate: filters.fromDate, toDate: filters.toDate })
  // Phones only (the toggle is hidden on wider screens): the filter dropdowns fold away until asked for.
  const [showFilters, setShowFilters] = useState(false)

  return (
    <div className="aia-filters">
      <div className="aia-filter-row">
        <div className="aia-segmented" role="group" aria-label="Date range">
          {PRESETS.map((p) => (
            <button
              key={p.id}
              type="button"
              className={filters.preset === p.id ? 'is-active' : undefined}
              aria-pressed={filters.preset === p.id}
              onClick={() => set('preset', p.id)}
            >
              {p.label}
            </button>
          ))}
        </div>
        {filters.preset === 'custom' && (
          <span className="aia-date-range">
            <input type="date" aria-label="From" value={filters.fromDate} onChange={(e) => set('fromDate', e.target.value)} />
            <span>to</span>
            <input type="date" aria-label="To" value={filters.toDate} onChange={(e) => set('toDate', e.target.value)} />
          </span>
        )}
      </div>

      <button
        type="button"
        className="aia-button aia-filters-toggle"
        aria-expanded={showFilters}
        onClick={() => setShowFilters((value) => !value)}
      >
        {showFilters ? 'Hide filters' : 'Filters'}
        {activeFilterCount(filters) > 0 && ` (${activeFilterCount(filters)})`}
      </button>

      <div className={`aia-dropdown-row${showFilters ? ' is-open' : ''}`}>
        {LIST_FILTERS.map((f) => (
          <MultiSelectDropdown
            key={f.key}
            label={f.label}
            hint={f.hint}
            values={options?.[f.key] ?? []}
            selected={filters[f.key]}
            valueLabel={(v) => valueLabel(f.key, v)}
            onChange={(selected) => set(f.key, selected)}
          />
        ))}
        <div className="aia-dropdown aia-dropdown--wide">
          <span className="aia-dropdown-label" title="Every AI request made for one recipe">Recipe</span>
          <SearchableSelect value={filters.recipeId} onChange={(v) => set('recipeId', v)}
            options={withAll('All recipes', recipes)} placeholder="All recipes" />
        </div>
        <div className="aia-dropdown aia-dropdown--wide">
          <span className="aia-dropdown-label" title="Requests made by one user">User</span>
          <SearchableSelect value={filters.userId} onChange={(v) => set('userId', v)}
            options={withAll('All users', users)} placeholder="All users" />
        </div>
        {activeFilterCount(filters) > 0 && (
          <button type="button" className="aia-link aia-dropdown-reset" onClick={reset}>Reset filters</button>
        )}
      </div>
    </div>
  )
}
