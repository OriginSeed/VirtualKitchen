/**
 * Live score preview while an admin rates an image. Mirrors the backend's EvaluationScoring for the rubric it
 * serves (weights and tag severities come from `GET /rubric`); the stored score is always the backend's.
 */

import type { RatingField, Rubric } from '../api/aiEvaluationApi'

export interface ImageForm {
  consistency: number | null
  stepCorrectness: number | null
  stateContinuity: number | null
  realism: number | null
  defectTags: string[]
  note: string
}

export interface VerdictForm {
  verdict: 'ACCEPTED' | 'REJECTED' | null
  reasonTags: string[]
  note: string
}

export const RATING_FIELDS: { field: RatingField; dimension: string }[] = [
  { field: 'consistency', dimension: 'CONSISTENCY' },
  { field: 'stepCorrectness', dimension: 'STEP_CORRECTNESS' },
  { field: 'stateContinuity', dimension: 'STATE_CONTINUITY' },
  { field: 'realism', dimension: 'REALISM' },
]

export const EMPTY_IMAGE_FORM: ImageForm = {
  consistency: null, stepCorrectness: null, stateContinuity: null, realism: null, defectTags: [], note: '',
}

export function toPercent(rating: number): number {
  return ((rating - 1) / 4) * 100
}

/** 5 without tags, 3 for one minor tag, 1 for a major tag or two or more tags. */
export function defectRating(tags: string[], rubric: Rubric): number {
  if (tags.length === 0) return 5
  const major = new Set(rubric.defectTags.filter((t) => t.major).map((t) => t.id))
  if (tags.length >= 2 || tags.some((t) => major.has(t))) return 1
  return 3
}

/** 0–100, or null when no dimension is rated yet. */
export function imageScore(form: ImageForm, rubric: Rubric): number | null {
  if (RATING_FIELDS.every(({ field }) => form[field] === null)) return null
  const weight = (dimension: string) => rubric.dimensions.find((d) => d.id === dimension)?.weight ?? 0
  let weighted = 0
  let weights = 0
  const rated: { rating: number | null; dimension: string }[] = [
    ...RATING_FIELDS.map(({ field, dimension }) => ({ rating: form[field], dimension })),
    { rating: defectRating(form.defectTags, rubric), dimension: 'DEFECTS' },
  ]
  for (const { rating, dimension } of rated) {
    if (rating === null) continue
    weighted += toPercent(rating) * weight(dimension)
    weights += weight(dimension)
  }
  return weights === 0 ? null : weighted / weights
}

/** True when the step-correctness rating alone fails the recipe. */
export function isHardFail(form: ImageForm, rubric: Rubric): boolean {
  return form.stepCorrectness !== null && form.stepCorrectness <= rubric.hardFailStepCorrectness
}

const sameTags = (a: string[], b: string[]) => a.length === b.length && [...a].sort().join() === [...b].sort().join()

export function sameImageForm(a: ImageForm, b: ImageForm): boolean {
  return RATING_FIELDS.every(({ field }) => a[field] === b[field])
    && sameTags(a.defectTags, b.defectTags) && a.note.trim() === b.note.trim()
}

export function sameVerdictForm(a: VerdictForm, b: VerdictForm): boolean {
  return a.verdict === b.verdict && sameTags(a.reasonTags, b.reasonTags) && a.note.trim() === b.note.trim()
}

export function toggle(values: string[], value: string): string[] {
  return values.includes(value) ? values.filter((v) => v !== value) : [...values, value]
}
