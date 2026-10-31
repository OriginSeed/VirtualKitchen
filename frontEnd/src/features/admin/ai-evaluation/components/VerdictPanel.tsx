import { useState } from 'react'
import { useDraft } from '../../../../shared/drafts/useDraft'
import { errorMessage } from '../../../../shared/errors'
import { useNotifications } from '../../../../shared/components/notifications/notificationContext'
import { AiEvaluationApi } from '../api/aiEvaluationApi'
import type { Rubric, SetEvaluation } from '../api/aiEvaluationApi'
import { sameVerdictForm, toggle } from '../model/evaluationScoring'
import type { VerdictForm } from '../model/evaluationScoring'

interface Props {
  setKey: string
  evaluation: SetEvaluation | null
  rubric: Rubric
  onSaved: (evaluation: SetEvaluation) => void
}

/** The admin's overall call on the recipe's images: accept or reject, with reason tags and a note. */
export default function VerdictPanel({ setKey, evaluation, rubric, onSaved }: Props) {
  const { notifySuccess, notifyError } = useNotifications()
  const saved: VerdictForm = {
    verdict: evaluation?.verdict ?? null,
    reasonTags: evaluation?.reasonTags ?? [],
    note: evaluation?.note ?? '',
  }
  const { value: draft, setValue: setDraft, clear } = useDraft<VerdictForm | null>(`ai-evaluation:verdict:${setKey}`, null)
  const form = draft ?? saved
  const dirty = draft !== null && !sameVerdictForm(draft, saved)
  const [saving, setSaving] = useState(false)
  const update = (patch: Partial<VerdictForm>) => setDraft({ ...form, ...patch })

  const save = async () => {
    setSaving(true)
    try {
      const result = await AiEvaluationApi.saveVerdict(setKey, {
        ...form,
        note: form.note.trim() || null,
        revision: evaluation?.revision ?? null,
      })
      clear()
      onSaved(result)
      notifySuccess('Verdict saved')
    } catch (error) {
      notifyError(errorMessage(error, 'Could not save the verdict'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <section className="aia-card aie-verdict" aria-label="Recipe verdict">
      <h3>Recipe verdict</h3>
      <div className="aia-choice-row" role="radiogroup" aria-label="Verdict">
        {(['ACCEPTED', 'REJECTED'] as const).map((verdict) => (
          <button key={verdict} type="button" role="radio" aria-checked={form.verdict === verdict}
            className={`aia-chip ${form.verdict === verdict ? 'is-active' : ''}`}
            onClick={() => update({ verdict: form.verdict === verdict ? null : verdict })}>
            {verdict === 'ACCEPTED' ? '✓ Accept' : '✕ Reject'}
          </button>
        ))}
      </div>
      <div className="aia-choice-row" role="group" aria-label="Reasons">
        <span className="aia-choice-label">Reasons</span>
        {rubric.reasonTags.map((tag) => (
          <button key={tag.id} type="button" aria-pressed={form.reasonTags.includes(tag.id)}
            className={`aia-chip ${form.reasonTags.includes(tag.id) ? 'is-active' : ''}`}
            onClick={() => update({ reasonTags: toggle(form.reasonTags, tag.id) })}>
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
        {dirty && <button type="button" className="aia-link" onClick={clear} disabled={saving}>Discard</button>}
        <button type="button" className="aia-button aia-button--primary" disabled={!dirty || saving} onClick={save}>
          {saving ? 'Saving…' : evaluation?.verdict ? 'Save changes' : 'Save verdict'}
        </button>
      </footer>
    </section>
  )
}
