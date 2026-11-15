/**
 * Server-side measurements for one bench recipe, from the admin AI analytics API
 * (ai_request_analytics): queue wait vs. execution time, tokens, image/audio usage and cost for
 * every AI call the recipe caused — including the hidden image-prompt text calls.
 */

import { sleep, type ApiClient } from '../api'
import type { MetricBucket, ServerMetrics } from '../types'

type Usage = {
  inputTokens?: number | null
  outputTokens?: number | null
  totalTokens?: number | null
  imageCount?: number | null
  audioDurationMs?: number | null
}

type AnalyticsRow = {
  requestType?: string
  operationContext?: string
  resolvedModelKey?: string | null
  usedFallback?: boolean | null
  status?: string
  queueWaitMs?: number | null
  executionMs?: number | null
  totalMs?: number | null
  attemptCount?: number | null
  usageTotals?: Usage | null
  totalCost?: number | null
  costTotals?: { currency?: string | null } | null
}

type Page<T> = { items: T[]; total: number; page: number; size: number }

/** AnalyticsStatus values. */
const PENDING = new Set(['QUEUED', 'RUNNING'])
const FAILED = new Set(['FAILED', 'TIMED_OUT', 'CANCELLED', 'REJECTED'])

export const emptyBucket = (): MetricBucket => ({
  calls: 0, failed: 0, fallbacks: 0, queueWaitMs: 0, executionMs: 0, totalMs: 0, maxTotalMs: 0,
  inputTokens: 0, outputTokens: 0, totalTokens: 0, imageCount: 0, audioMs: 0, cost: 0, attempts: 0,
})

export function addRow(bucket: MetricBucket, row: AnalyticsRow) {
  const usage = row.usageTotals ?? {}
  bucket.calls++
  if (row.status && FAILED.has(row.status)) bucket.failed++
  if (row.usedFallback) bucket.fallbacks++
  bucket.queueWaitMs += row.queueWaitMs ?? 0
  bucket.executionMs += row.executionMs ?? 0
  bucket.totalMs += row.totalMs ?? 0
  bucket.maxTotalMs = Math.max(bucket.maxTotalMs, row.totalMs ?? 0)
  bucket.inputTokens += usage.inputTokens ?? 0
  bucket.outputTokens += usage.outputTokens ?? 0
  bucket.totalTokens += usage.totalTokens ?? 0
  bucket.imageCount += usage.imageCount ?? 0
  bucket.audioMs += usage.audioDurationMs ?? 0
  bucket.cost += row.totalCost ?? 0
  bucket.attempts += row.attemptCount ?? 0
}

async function fetchAll(admin: ApiClient, recipeId: number): Promise<AnalyticsRow[]> {
  const rows: AnalyticsRow[] = []
  for (let page = 0; ; page++) {
    const result = await admin.get<Page<AnalyticsRow>>(
      `/api/v1/admin/ai-analytics/requests?recipeId=${recipeId}&page=${page}&size=200&sort=createdAt&direction=asc`)
    rows.push(...result.items)
    if (rows.length >= result.total || result.items.length === 0) return rows
  }
}

/** Waits briefly for analytics rows that are still being finalized, then aggregates them. */
export async function collectServerMetrics(admin: ApiClient, recipeId: number): Promise<ServerMetrics> {
  let rows = await fetchAll(admin, recipeId)
  for (let wait = 0; wait < 5 && rows.some((row) => row.status && PENDING.has(row.status)); wait++) {
    await sleep(2000)
    rows = await fetchAll(admin, recipeId)
  }
  const byOperation: ServerMetrics['byOperation'] = {}
  const totals = emptyBucket()
  let currency: string | null = null
  for (const row of rows) {
    const key = `${row.requestType ?? '?'}/${row.operationContext ?? '?'}`
    const bucket = byOperation[key] ??= { ...emptyBucket(), models: [] }
    addRow(bucket, row)
    addRow(totals, row)
    if (row.resolvedModelKey && !bucket.models.includes(row.resolvedModelKey)) bucket.models.push(row.resolvedModelKey)
    currency ??= row.costTotals?.currency ?? null
  }
  return { byOperation, totals, currency, rows: rows.length }
}
