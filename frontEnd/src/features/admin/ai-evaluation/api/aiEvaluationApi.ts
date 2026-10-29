/**
 * Admin AI evaluation API: human quality scores for generated step images, and per-model comparison of quality,
 * cost and time. Cost and time come from the backend's analytics records; only the human scores are stored for it.
 * Nullable fields are null when the value was never rated or recorded — never treat them as zero.
 */

import { apiGet, apiPut } from '../../../../api/client'

const BASE = '/api/v1/admin/ai-evaluation'

export type SetStatus = 'UNSCORED' | 'PARTIAL' | 'SCORED'
export type Verdict = 'ACCEPTED' | 'REJECTED'
export type RatingField = 'consistency' | 'stepCorrectness' | 'stateContinuity' | 'realism'

export interface RubricOption {
  id: string
  label: string
  description: string | null
  weight: number | null
  major: boolean | null
}

export interface Rubric {
  version: string
  dimensions: RubricOption[]
  defectTags: RubricOption[]
  reasonTags: RubricOption[]
  meanWeight: number
  worstWeight: number
  hardFailStepCorrectness: number
  lowSampleSets: number
}

export interface Stat {
  value: number | null
  samples: number
}

export interface Page<T> {
  items: T[]
  total: number
  page: number
  size: number
}

export interface SetSummary {
  setKey: string
  recipeId: string | null
  recipeName: string | null
  workflowId: string | null
  modelKey: string | null
  provider: string | null
  tier: string | null
  models: string[]
  mixedModels: boolean
  imageCount: number
  failedRequests: number
  totalCost: number | null
  currency: string | null
  costComplete: boolean
  spanMs: number | null
  startedAt: string | null
  status: SetStatus
  scoredImages: number
  verdict: Verdict | null
  recipeScore: number | null
  accepted: boolean | null
  hardFail: boolean | null
}

export interface SetPage {
  page: Page<SetSummary>
  setsScanned: number
  truncated: boolean
}

export interface ImageEvaluation {
  id: string
  revision: number
  consistency: number | null
  stepCorrectness: number | null
  stateContinuity: number | null
  realism: number | null
  defectTags: string[]
  note: string | null
  imageScore: number | null
  rubricVersion: string | null
  evaluatedAt: string | null
  updatedAt: string | null
  edits: unknown[]
}

export interface SetEvaluation {
  id: string
  revision: number
  verdict: Verdict | null
  reasonTags: string[]
  note: string | null
  meanScore: number | null
  worstScore: number | null
  recipeScore: number | null
  hardFail: boolean
  accepted: boolean
  scoredImages: number
  totalImages: number
  rubricVersion: string | null
  updatedAt: string | null
}

export interface ImageItem {
  requestId: string
  stepId: string
  stepIndex: number | null
  processName: string | null
  stepTitle: string | null
  stepDescription: string | null
  imagePrompt: string | null
  imageUrl: string | null
  provenance: string | null
  modelKey: string | null
  provider: string | null
  tier: string | null
  usedFallback: boolean | null
  attemptCount: number | null
  totalCost: number | null
  currency: string | null
  totalMs: number | null
  queueWaitMs: number | null
  imageWidth: number | null
  imageHeight: number | null
  createdAt: string | null
  evaluation: ImageEvaluation | null
}

export interface SetDetail {
  summary: SetSummary
  sceneImageUrl: string | null
  images: ImageItem[]
  evaluation: SetEvaluation | null
  rubricVersion: string
}

export interface ImageScoreInput {
  consistency: number | null
  stepCorrectness: number | null
  stateContinuity: number | null
  realism: number | null
  defectTags: string[]
  note: string | null
  /** The revision loaded; null to create. */
  revision: number | null
}

export interface VerdictInput {
  verdict: Verdict | null
  reasonTags: string[]
  note: string | null
  revision: number | null
}

export interface ImageSaveResult {
  image: ImageEvaluation
  set: SetEvaluation
}

export interface TagCount {
  tag: string
  count: number
}

export interface ModelComparison {
  modelKey: string
  provider: string | null
  tier: string | null
  sets: number
  images: number
  failedRequests: number
  currency: string | null
  costPerImage: Stat
  costPerSet: Stat
  costPerAcceptedSet: number | null
  p50ImageMs: Stat
  p95ImageMs: Stat
  p50SetMs: Stat
  p95SetMs: Stat
  avgQueueWaitMs: Stat
  scoredSets: number
  scoredImages: number
  verdictSets: number
  recipeScore: Stat
  acceptanceRate: number | null
  hardFailRate: number | null
  dimensions: Record<string, Stat>
  defectRate: number | null
  topDefects: TagCount[]
  qualityPerCost: number | null
  lowSample: boolean
}

export interface Comparison {
  models: ModelComparison[]
  setsScanned: number
  truncated: boolean
  rubricVersion: string
}

export type QueryParams = Record<string, string | number | boolean>

const setPath = (setKey: string) => `${BASE}/sets/${encodeURIComponent(setKey)}`

export const AiEvaluationApi = {
  rubric: () => apiGet<Rubric>(`${BASE}/rubric`),
  sets: (params: QueryParams) => apiGet<SetPage>(`${BASE}/sets`, { params }),
  set: (setKey: string) => apiGet<SetDetail>(setPath(setKey)),
  scoreImage: (setKey: string, requestId: string, input: ImageScoreInput) =>
    apiPut<ImageSaveResult>(`${setPath(setKey)}/images/${encodeURIComponent(requestId)}`, input),
  saveVerdict: (setKey: string, input: VerdictInput) => apiPut<SetEvaluation>(`${setPath(setKey)}/verdict`, input),
  compare: (params: QueryParams) => apiGet<Comparison>(`${BASE}/compare`, { params }),
}
