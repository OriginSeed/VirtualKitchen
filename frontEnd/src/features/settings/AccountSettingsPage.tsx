import { useState } from 'react'
import { AiApi, type AiCapability, type AiPreferences } from '../../api'
import SearchableSelect from '../../shared/components/SearchableSelect'
import { useNotifications } from '../../shared/components/notifications/notificationContext'
import { errorMessage } from '../../shared/errors'
import { useApiData } from '../../shared/hooks/useApiData'
import { modelOptions } from '../../shared/format/modelOptions'
import './AccountSettingsPage.css'

const CAPABILITIES: { capability: AiCapability; label: string; help: string }[] = [
  { capability: 'TEXT_TO_TEXT', label: 'Text model', help: 'Writes recipe processes, edits and image prompts.' },
  { capability: 'TEXT_TO_IMAGE', label: 'Image model', help: 'Draws the step images.' },
]

async function loadModelChoices() {
  const [preferences, text, image] = await Promise.all([
    AiApi.getMyPreferences(),
    AiApi.getSelectableModels('TEXT_TO_TEXT'),
    AiApi.getSelectableModels('TEXT_TO_IMAGE'),
  ])
  return { preferences, models: { TEXT_TO_TEXT: text, TEXT_TO_IMAGE: image } as Record<AiCapability, typeof text> }
}

/**
 * Account settings: the user's own AI model choices. Each AI request is charged the chosen model's credits; when the
 * balance can't cover a paid model, the request falls back as configured by the admin.
 */
export default function AccountSettingsPage() {
  const choices = useApiData('ai-model-choices', loadModelChoices)
  const credits = useApiData('ai-credits', () => AiApi.getMyCredits())
  const [saving, setSaving] = useState<AiCapability | null>(null)
  const { notifySuccess, notifyError } = useNotifications()

  const loaded = choices.data

  const save = async (capability: AiCapability, key: string) => {
    setSaving(capability)
    try {
      const updated: AiPreferences = await AiApi.saveMyPreferences({ [capability]: key || null })
      choices.mutate(data => ({ ...data, preferences: updated }))
      notifySuccess('Model preference saved')
    } catch (error) {
      notifyError(errorMessage(error, 'Could not save your model preference'))
    } finally {
      setSaving(null)
    }
  }

  return (
    <div className="account-settings">
      <header>
        <h1>Account settings</h1>
        <p className="account-settings-muted">Choose the AI models your recipes use. Each request costs the chosen model's credits.</p>
      </header>

      <section className="account-settings-card">
        <h2>AI credits</h2>
        {credits.data
          ? (
            <p>
              <strong>⚡ {credits.data.availableBalance}</strong> available of {credits.data.monthlyAllocation} this month
              {credits.data.reservedBalance > 0 && <> · {credits.data.reservedBalance} held by running requests</>}
            </p>
          )
          : <p className="account-settings-muted">{credits.error ?? 'Loading…'}</p>}
      </section>

      <section className="account-settings-card">
        <h2>AI models</h2>
        {choices.error && <p className="account-settings-error" role="alert">{choices.error}</p>}
        {!choices.data && !choices.error && <p className="account-settings-muted">Loading…</p>}
        {choices.data?.preferences.staleChoice && (
          <p className="account-settings-note" role="status">
            A model you chose is no longer offered, so the default model is used instead.
          </p>
        )}
        {loaded && CAPABILITIES.map(({ capability, label, help }) => {
          const models = loaded.models[capability]
          const selected = loaded.preferences.modelKeys[capability] ?? ''
          return (
            <div key={capability} className="account-settings-field">
              <span className="account-settings-label">{label}</span>
              <SearchableSelect
                value={selected}
                onChange={(value) => { if (value !== selected) void save(capability, value) }}
                options={modelOptions(models)}
                className={saving === capability ? 'is-saving' : undefined}
              />
              <small className="account-settings-muted">{help}</small>
            </div>
          )
        })}
      </section>
    </div>
  )
}
