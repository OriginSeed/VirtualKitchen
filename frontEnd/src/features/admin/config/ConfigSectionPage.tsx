import { useState } from 'react'
import { useParams } from 'react-router-dom'
import { useConfirm } from '../../../shared/components/confirm/confirmContext'
import { useNotifications } from '../../../shared/components/notifications/notificationContext'
import { errorMessage } from '../../../shared/errors'
import { useApiData } from '../../../shared/hooks/useApiData'
import { AdminConfigApi, type ConfigField } from './api/adminConfigApi'
import GrantCreditsCard from './components/GrantCreditsCard'
import { changedValues, displayValue, groupFields, isDirty, optionLabel, type ConfigDraft } from './model/configForm'
import '../ai-analytics/aiAnalytics.css'
import './adminConfig.css'

function FieldControl({ field, value, disabled, onChange }: {
  field: ConfigField
  value: string
  disabled: boolean
  onChange: (value: string) => void
}) {
  const id = `cfg-${field.key}`
  if (field.type === 'TOGGLE') {
    return (
      <label className="acf-toggle" htmlFor={id}>
        <input id={id} type="checkbox" checked={value === 'true'} disabled={disabled}
          onChange={(event) => onChange(String(event.target.checked))} />
        <span>{value === 'true' ? 'On' : 'Off'}</span>
      </label>
    )
  }
  if (field.type === 'TEXT') {
    return (
      <input id={id} type="text" value={value} disabled={disabled} maxLength={200}
        onChange={(event) => onChange(event.target.value)} />
    )
  }
  if (field.type === 'SECRET') {
    return (
      <input id={id} type="password" autoComplete="new-password" value={value} disabled={disabled}
        placeholder={field.current ? `Set (${field.current}) · type to replace` : 'Not set · type to set'}
        onChange={(event) => onChange(event.target.value)} />
    )
  }
  return (
    <select id={id} value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
      {field.options.map(option => (
        <option key={option} value={option}>{optionLabel(field, option)}</option>
      ))}
    </select>
  )
}

/**
 * One runtime config section (models, threads, generation…), rendered from the backend's catalog: only the offered
 * values can be picked, changes apply live on save, and "Reset to defaults" restores application properties.
 */
export default function ConfigSectionPage() {
  const { sectionId = '' } = useParams()
  const section = useApiData(`config:${sectionId}`, () => AdminConfigApi.section(sectionId))
  const [draft, setDraft] = useState<ConfigDraft>({})
  const [saving, setSaving] = useState(false)
  const confirm = useConfirm()
  const { notifySuccess, notifyError } = useNotifications()

  // A different section starts with no pending edits.
  const [draftFor, setDraftFor] = useState(sectionId)
  if (draftFor !== sectionId) {
    setDraftFor(sectionId)
    setDraft({})
  }

  const view = section.data
  const fields = view?.fields ?? []
  const dirty = isDirty(fields, draft)
  const editable = Boolean(view?.available) && !saving
  const overriddenCount = fields.filter(field => field.overridden).length

  const save = async () => {
    if (!view) return
    setSaving(true)
    try {
      const updated = await AdminConfigApi.update(view.id, changedValues(fields, draft))
      section.mutate(() => updated)
      setDraft({})
      notifySuccess(`${view.label} saved and applied`)
    } catch (error) {
      notifyError(errorMessage(error, 'Could not save the settings'))
    } finally {
      setSaving(false)
    }
  }

  const reset = async () => {
    if (!view) return
    const ok = await confirm({
      eyebrow: view.label,
      title: 'Reset to defaults?',
      message: 'Every override in this section is removed and the values from application properties apply again.',
      confirmLabel: 'Reset',
    })
    if (!ok) return
    setSaving(true)
    try {
      const updated = await AdminConfigApi.reset(view.id)
      section.mutate(() => updated)
      setDraft({})
      notifySuccess(`${view.label} reset to defaults`)
    } catch (error) {
      notifyError(errorMessage(error, 'Could not reset the settings'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="aia-root">
      <header className="aia-header acf-header">
        <div>
          <h1>{view?.label ?? 'Settings'}</h1>
          {view && <p className="aia-muted">{view.description}</p>}
        </div>
        {view?.available && (
          <div className="acf-actions">
            <button type="button" className="aia-button" disabled={!editable || overriddenCount === 0} onClick={reset}>
              Reset to defaults
            </button>
            <button type="button" className="aia-button" disabled={!dirty || saving} onClick={() => setDraft({})}>
              Discard
            </button>
            <button type="button" className="aia-button aia-button--primary" disabled={!dirty || !editable} onClick={save}>
              {saving ? 'Saving…' : 'Save & apply'}
            </button>
          </div>
        )}
      </header>

      {section.error && <div className="aia-alert aia-alert--bad" role="alert">{section.error}</div>}
      {section.loading && !view && <div className="aia-loading">Loading…</div>}
      {view && !view.available && (
        <div className="aia-alert aia-alert--bad" role="status">{view.unavailableReason}</div>
      )}

      {view && (
        <div className="acf-groups">
          {sectionId === 'models' && view.available && <GrantCreditsCard />}
          {groupFields(fields).map(group => (
            <section key={group.name} className="aia-card acf-group">
              <h3>{group.name}</h3>
              <div className="acf-fields">
                {group.fields.map(field => (
                  <div key={field.key} className={`acf-field ${field.key in draft ? 'is-edited' : ''}`}>
                    <label htmlFor={`cfg-${field.key}`} className="acf-label">
                      {field.label}
                      {field.overridden && <span className="aia-badge aia-badge--info acf-badge">overridden</span>}
                    </label>
                    <FieldControl
                      field={field}
                      value={displayValue(field, draft)}
                      disabled={!editable}
                      onChange={(value) => setDraft(previous => ({ ...previous, [field.key]: value }))}
                    />
                    {field.help && <small className="aia-muted acf-help">{field.help}</small>}
                  </div>
                ))}
              </div>
            </section>
          ))}
        </div>
      )}
    </div>
  )
}
