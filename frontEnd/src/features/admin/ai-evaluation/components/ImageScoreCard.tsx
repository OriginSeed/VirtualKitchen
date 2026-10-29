import { useState } from 'react'
import { useDraft } from '../../../../shared/drafts/useDraft'
import { errorMessage } from '../../../../shared/errors'
import { useNotifications } from '../../../../shared/components/notifications/notificationContext'
import { formatCost, formatMs } from '../../ai-analytics/model/analyticsFormat'
import { AiEvaluationApi } from '../api/aiEvaluationApi'
import type { ImageEvaluation, ImageItem, ImageSaveResult, Rubric } from '../api/aiEvaluationApi'
import { formatScore } from '../model/evaluationFormat'
import { EMPTY_IMAGE_FORM, RATING_FIELDS, imageScore, isHardFail, sameImageForm, toggle } from '../model/evaluationScoring'
import type { ImageForm } from '../model/evaluationScoring'
import { mediaUrl } from './media'

interface Props {
  setKey: string
  image: ImageItem
  rubric: Rubric
  onSaved: (result: ImageSaveResult) => void
  onEnlarge: (url: string, caption: string) => void
}

function fromEvaluation(evaluation: ImageEvaluation | null): ImageForm {
  if (!evaluation) return EMPTY_IMAGE_FORM
  return {
    consistency: evaluation.consistency,
    stepCorrectness: evaluation.stepCorrectness,
    stateContinuity: evaluation.stateContinuity,
    realism: evaluation.realism,
    defectTags: evaluation.defectTags ?? [],
    note: evaluation.note ?? '',
  }
}

/**
 * One step image with its captured metrics and the admin's rating form. Unsaved edits are kept as a draft (they
 * survive navigation) until saved; the saved values always come from the server.
 */
export default function ImageScoreCard({ setKey, image, rubric, onSaved, onEnlarge }: Props) {
  const { notifySuccess, notifyError } = useNotifications()
  const saved = fromEvaluation(image.evaluation)
  const [draft, setDraft, clearDraft] = useImageDraft(image.requestId)
  const form = draft ?? saved
  const dirty = draft !== null && !sameImageForm(draft, saved)
  const [saving, setSaving] = useState(false)
  const score = imageScore(form, rubric)
  const label = image.stepIndex ? `Step ${image.stepIndex}` : image.stepId
  const dimension = (id: string) => rubric.dimensions.find((d) => d.id === id)

  const update = (patch: Partial<ImageForm>) => setDraft({ ...form, ...patch })

  const save = async () => {
    setSaving(true)
    try {
      const result = await AiEvaluationApi.scoreImage(setKey, image.requestId, {
        ...form,
        note: form.note.trim() || null,
        revision: image.evaluation?.revision ?? null,
      })
      clearDraft()
      onSaved(result)
      notifySuccess(`${label} scored`)
    } catch (error) {
      notifyError(errorMessage(error, 'Could not save the scores'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <article className="aia-card aie-image-card" aria-label={label}>
      <header className="aie-image-head">
        <div>
          <h3>{label}{image.stepTitle ? ` · ${image.stepTitle}` : ''}</h3>
          {image.processName && <p className="aia-muted aia-small">{image.processName}</p>}
        </div>
        <span className="aie-score" title="Image score (0–100) under the current rubric">{formatScore(score)}</span>
      </header>

      {image.imageUrl ? (
        <button type="button" className="aie-image-button"
          onClick={() => onEnlarge(mediaUrl(image.imageUrl as string), `${label}${image.stepTitle ? ` · ${image.stepTitle}` : ''}`)}>
          <img src={mediaUrl(image.imageUrl)} alt={`Generated image for ${label}`} loading="lazy" />
        </button>
      ) : (
        <p className="aia-empty">Image not retained.</p>
      )}

      <div className="aia-efficiency aie-metrics">
        <span><b>Model</b>{image.modelKey ?? '—'}{image.usedFallback && <span className="aia-tag aia-tag--warn">fallback</span>}</span>
        <span><b>Cost</b>{formatCost(image.totalCost, image.currency)}</span>
        <span><b>Time</b>{formatMs(image.totalMs)}</span>
        {image.attemptCount !== null && image.attemptCount > 1 && <span><b>Attempts</b>{image.attemptCount}</span>}
      </div>

      {(image.stepDescription || image.imagePrompt) && (
        <details className="aia-details">
          <summary>Step and prompt</summary>
          {image.stepDescription && <p className="aia-small">{image.stepDescription}</p>}
          {image.imagePrompt && <p className="aia-small aia-muted">{image.imagePrompt}</p>}
        </details>
      )}

      <div className="aie-ratings">
        {RATING_FIELDS.map(({ field, dimension: id }) => (
          <div key={field} className="aie-rating" role="radiogroup" aria-label={dimension(id)?.label ?? id}>
            <span className="aie-rating-label" title={dimension(id)?.description ?? undefined}>{dimension(id)?.label ?? id}</span>
            <span className="aie-rating-buttons">
              {[1, 2, 3, 4, 5].map((value) => (
                <button key={value} type="button" role="radio" aria-checked={form[field] === value}
                  className={`aie-rating-button ${form[field] === value ? 'is-active' : ''}`}
                  onClick={() => update({ [field]: form[field] === value ? null : value })}>
                  {value}
                </button>
              ))}
            </span>
          </div>
        ))}
      </div>
      {isHardFail(form, rubric) && (
        <p className="aia-provenance aia-provenance--warn">
          Step correctness of {rubric.hardFailStepCorrectness} or less fails the whole recipe.
        </p>
      )}

      <div className="aia-choice-row" role="group" aria-label="Defects">
        <span className="aia-choice-label">Defects</span>
        {rubric.defectTags.map((tag) => (
          <button key={tag.id} type="button" aria-pressed={form.defectTags.includes(tag.id)}
            className={`aia-chip ${form.defectTags.includes(tag.id) ? 'is-active' : ''} ${tag.major ? 'aie-chip--major' : ''}`}
            title={tag.major ? 'Major defect' : 'Minor defect'}
            onClick={() => update({ defectTags: toggle(form.defectTags, tag.id) })}>
            {tag.label}
          </button>
        ))}
      </div>

      <label className="aie-note">
        <span className="aia-choice-label">Note</span>
        <textarea rows={2} maxLength={2000} value={form.note} onChange={(e) => update({ note: e.target.value })} />
      </label>

      <footer className="aie-card-actions">
        {dirty && <span className="aia-muted aia-small">Unsaved changes</span>}
        {dirty && <button type="button" className="aia-link" onClick={clearDraft} disabled={saving}>Discard</button>}
        <button type="button" className="aia-button aia-button--primary" disabled={!dirty || saving} onClick={save}>
          {saving ? 'Saving…' : image.evaluation ? 'Save changes' : 'Save scores'}
        </button>
      </footer>
    </article>
  )
}

/** The unsaved edits of one image's form (null: nothing edited), kept across navigation until saved. */
function useImageDraft(requestId: string): [ImageForm | null, (form: ImageForm) => void, () => void] {
  const { value, setValue, clear } = useDraft<ImageForm | null>(`ai-evaluation:image:${requestId}`, null)
  return [value, setValue, clear]
}
