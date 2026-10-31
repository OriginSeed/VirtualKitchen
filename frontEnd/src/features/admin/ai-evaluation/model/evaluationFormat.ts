/** Filters, labels and formatting for the AI evaluation pages. Pure, so it is unit-tested. */

import type { SetStatus } from '../api/aiEvaluationApi'

export type DateRange = '7d' | '30d' | '90d' | 'all'

export interface EvaluationFilters {
  range: DateRange
  models: string[]
  statuses: SetStatus[]
  includeMixed: boolean
}

export const DEFAULT_FILTERS: EvaluationFilters = { range: '30d', models: [], statuses: [], includeMixed: false }

const RANGE_DAYS: Record<Exclude<DateRange, 'all'>, number> = { '7d': 7, '30d': 30, '90d': 90 }

export const RANGE_LABELS: Record<DateRange, string> = {
  '7d': 'Last 7 days', '30d': 'Last 30 days', '90d': 'Last 90 days', all: 'All time',
}

/** Query parameters for `/sets` and `/compare`; `now` anchors the relative range (to the minute). */
export function toEvaluationParams(filters: EvaluationFilters, now: Date): Record<string, string | boolean> {
  const params: Record<string, string | boolean> = {}
  if (filters.range !== 'all') {
    const from = new Date(now.getTime() - RANGE_DAYS[filters.range] * 86_400_000)
    from.setSeconds(0, 0)
    params.from = from.toISOString()
  }
  if (filters.models.length > 0) params.model = filters.models.join(',')
  if (filters.statuses.length > 0) params.status = filters.statuses.join(',')
  if (filters.includeMixed) params.includeMixed = true
  return params
}

export const STATUS_LABELS: Record<SetStatus, string> = {
  UNSCORED: 'Not scored', PARTIAL: 'Partly scored', SCORED: 'Scored',
}

export function statusTone(status: SetStatus): 'good' | 'warn' | 'neutral' {
  return status === 'SCORED' ? 'good' : status === 'PARTIAL' ? 'warn' : 'neutral'
}

/** A 0–100 score, or a dash when there is none. */
export function formatScore(value: number | null | undefined): string {
  return value === null || value === undefined || Number.isNaN(value) ? '—' : value.toFixed(0)
}

/** A mean 1–5 rating. */
export function formatRating(value: number | null | undefined): string {
  return value === null || value === undefined ? '—' : value.toFixed(1)
}

/**
 * What one recipe-score point costs: mean cost per set ÷ mean recipe score (lower is better). Shown as a cost, so it
 * goes through the same display-currency conversion as every other cost.
 */
export function costPerPoint(costPerSet: number | null, recipeScore: number | null): number | null {
  if (costPerSet === null || recipeScore === null || recipeScore <= 0) return null
  return costPerSet / recipeScore
}

/** The set key encoded for a route segment. */
export function setRoute(setKey: string): string {
  return `/kitchen/admin/ai-evaluation/sets/${encodeURIComponent(setKey)}`
}
