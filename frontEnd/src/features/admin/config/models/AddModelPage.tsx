import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import SearchableSelect from '../../../../shared/components/SearchableSelect'
import { useNotifications } from '../../../../shared/components/notifications/notificationContext'
import { errorMessage } from '../../../../shared/errors'
import { useApiData } from '../../../../shared/hooks/useApiData'
import { AdminModelApi, type ModelCapability, type ProviderModelListing } from '../api/adminConfigApi'
import {
  CREDIT_COSTS,
  EMPTY_MODEL_FORM,
  formFrom,
  formProblem,
  priceFields,
  suggestDisplayName,
  suggestKey,
  toRequest,
  type ModelForm,
} from './addModelForm'
import '../../ai-analytics/aiAnalytics.css'
import '../adminConfig.css'

const MODELS_PAGE = '/kitchen/admin/config/models'

const CAPABILITY_LABELS: Record<ModelCapability, string> = {
  TEXT_TO_TEXT: 'Text (recipes, edits, image prompts)',
  TEXT_TO_IMAGE: 'Image (step images)',
  TEXT_TO_SPEECH: 'Speech (narration)',
}

async function loadPage(modelKey: string | undefined) {
  const [options, added] = await Promise.all([AdminModelApi.options(), AdminModelApi.list()])
  const editing = modelKey ? added.find((model) => model.key === modelKey) ?? null : null
  return { options, editing, notFound: Boolean(modelKey) && !editing }
}

/**
 * Add a model for a provider the app already has a client for (or edit an added one): each choice narrows the next,
 * the model id comes from the provider's own list (or is typed), and the model works as soon as it is saved.
 */
export default function AddModelPage() {
  const { modelKey } = useParams()
  const editing = Boolean(modelKey)
  const page = useApiData(`add-model:${modelKey ?? 'new'}`, () => loadPage(modelKey))
  const navigate = useNavigate()
  const { notifySuccess, notifyError } = useNotifications()
  const [form, setForm] = useState<ModelForm | null>(null)
  const [keyTouched, setKeyTouched] = useState(false)
  const [nameTouched, setNameTouched] = useState(false)
  const [listing, setListing] = useState<ProviderModelListing | null>(null)
  const [loadingModels, setLoadingModels] = useState(false)
  const [typing, setTyping] = useState(editing)
  const [saving, setSaving] = useState(false)
  const [touched, setTouched] = useState(false)

  const data = page.data
  // The form starts once the page data is in: empty for a new model, the stored one when editing.
  if (data && form === null && !data.notFound) {
    setForm(data.editing ? formFrom(data.editing) : EMPTY_MODEL_FORM)
  }

  const update = (changes: Partial<ModelForm>) => setForm((current) => {
    if (!current) return current
    const next = { ...current, ...changes }
    if (!editing) {
      if (!keyTouched && next.providerBean && next.providerModelId) next.key = suggestKey(next.providerBean, next.providerModelId)
      if (!nameTouched && next.providerModelId) next.displayName = suggestDisplayName(next.providerModelId)
    }
    return next
  })

  const loadModels = async () => {
    if (!form?.providerBean || !form.capability) return
    setLoadingModels(true)
    try {
      const result = await AdminModelApi.providerModels(form.providerBean, form.capability)
      setListing(result)
      setTyping(result.models.length === 0)
    } catch (error) {
      setListing({ models: [], supported: true, error: errorMessage(error, 'Could not load the provider’s models') })
      setTyping(true)
    } finally {
      setLoadingModels(false)
    }
  }

  if (page.error) return <div className="aia-root"><div className="aia-alert aia-alert--bad" role="alert">{page.error}</div></div>
  if (data?.notFound) {
    return (
      <div className="aia-root">
        <div className="aia-alert aia-alert--bad" role="alert">“{modelKey}” is not a model added from the admin panel.</div>
        <Link to={MODELS_PAGE} className="aia-link">Back to Models & Credits</Link>
      </div>
    )
  }
  if (!data || !form) return <div className="aia-root"><div className="aia-loading">Loading…</div></div>

  const { options } = data
  const providers = form.capability ? options.providers[form.capability] ?? [] : []
  const fallbacks = form.capability ? (options.fallbacks[form.capability] ?? []).filter((choice) => choice.value !== form.key) : []
  const takenKeys = options.takenKeys.filter((key) => key !== modelKey)
  const problem = formProblem(form, takenKeys, editing)
  const prices = priceFields(form.capability)

  const save = async () => {
    setTouched(true)
    if (problem) return
    setSaving(true)
    try {
      if (editing && modelKey) {
        await AdminModelApi.update(modelKey, toRequest(form))
        notifySuccess(`${form.displayName} updated`)
      } else {
        await AdminModelApi.add(toRequest(form))
        notifySuccess(`${form.displayName} added and ready to use`)
      }
      navigate(MODELS_PAGE)
    } catch (error) {
      notifyError(errorMessage(error, 'Could not save the model'))
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="aia-root">
      <header className="aia-header acf-header">
        <div>
          <h1>{editing ? `Edit ${form.displayName || modelKey}` : 'Add a model'}</h1>
          <p className="aia-muted">
            {editing
              ? 'Change the provider model id, name or prices. Credits, fallback and availability are set on Models & Credits.'
              : 'For a provider the app already supports. The model works as soon as it is saved; a new provider still needs code.'}
          </p>
        </div>
        <div className="acf-actions">
          <Link to={MODELS_PAGE} className="aia-button">Cancel</Link>
          <button type="button" className="aia-button aia-button--primary" disabled={saving} onClick={() => void save()}>
            {saving ? 'Saving…' : editing ? 'Save' : 'Add model'}
          </button>
        </div>
      </header>

      {touched && problem && <div className="aia-alert aia-alert--bad" role="alert">{problem}</div>}

      <div className="acf-groups">
        <section className="aia-card acf-group">
          <h3>Model</h3>
          <div className="acf-fields">
            <div className="acf-field">
              <label className="acf-label" htmlFor="am-capability">What it does</label>
              <select id="am-capability" value={form.capability} disabled={editing}
                onChange={(event) => {
                  setListing(null)
                  update({ capability: event.target.value as ModelCapability, providerBean: '', fallbackModelKey: '' })
                }}>
                <option value="" disabled>Choose…</option>
                {options.capabilities.map((capability) => (
                  <option key={capability} value={capability}>{CAPABILITY_LABELS[capability]}</option>
                ))}
              </select>
            </div>
            <div className="acf-field">
              <label className="acf-label" htmlFor="am-provider">Provider</label>
              <select id="am-provider" value={form.providerBean} disabled={editing || !form.capability}
                onChange={(event) => {
                  setListing(null)
                  setTyping(false)
                  update({ providerBean: event.target.value })
                }}>
                <option value="" disabled>{form.capability ? 'Choose…' : 'Choose what it does first'}</option>
                {providers.map((provider) => <option key={provider.value} value={provider.value}>{provider.label}</option>)}
              </select>
            </div>
            <div className="acf-field">
              <span className="acf-label">Provider model id</span>
              {!typing && listing && listing.models.length > 0 ? (
                <SearchableSelect
                  value={form.providerModelId}
                  onChange={(value) => update({ providerModelId: value })}
                  options={listing.models.map((model) => ({ value: model.id, label: model.label }))}
                  placeholder="Search the provider’s models"
                />
              ) : (
                <input type="text" value={form.providerModelId} maxLength={200} placeholder="e.g. Qwen/Qwen3-235B-A22B"
                  onChange={(event) => update({ providerModelId: event.target.value })} />
              )}
              <div className="acf-inline-actions">
                <button type="button" className="aia-link" disabled={!form.providerBean || loadingModels} onClick={() => void loadModels()}>
                  {loadingModels ? 'Loading models…' : listing ? 'Reload models' : 'Load models from provider'}
                </button>
                {listing && listing.models.length > 0 && (
                  <button type="button" className="aia-link" onClick={() => setTyping((current) => !current)}>
                    {typing ? 'Pick from the list' : 'Type it instead'}
                  </button>
                )}
              </div>
              {listing?.error && <small className="aia-muted acf-help" role="status">{listing.error}</small>}
            </div>
            <div className="acf-field">
              <label className="acf-label" htmlFor="am-key">Key</label>
              <input id="am-key" type="text" value={form.key} disabled={editing} maxLength={48}
                onChange={(event) => {
                  setKeyTouched(true)
                  update({ key: event.target.value.toLowerCase() })
                }} />
              <small className="aia-muted acf-help">Stable id used in settings and analytics; can’t be changed later.</small>
            </div>
            <div className="acf-field">
              <label className="acf-label" htmlFor="am-name">Display name</label>
              <input id="am-name" type="text" value={form.displayName} maxLength={200}
                onChange={(event) => {
                  setNameTouched(true)
                  update({ displayName: event.target.value })
                }} />
              <small className="aia-muted acf-help">What users see in the model picker.</small>
            </div>
          </div>
        </section>

        <section className="aia-card acf-group">
          <h3>Billing & availability</h3>
          <div className="acf-fields">
            <div className="acf-field">
              <label className="acf-label" htmlFor="am-tier">Tier</label>
              <select id="am-tier" value={form.tier} disabled={editing}
                onChange={(event) => update({ tier: event.target.value as ModelForm['tier'] })}>
                {options.tiers.map((tier) => <option key={tier} value={tier}>{tier === 'PAID' ? 'Paid (costs credits)' : 'Open source (free)'}</option>)}
              </select>
            </div>
            {form.tier === 'PAID' && (
              <div className="acf-field">
                <label className="acf-label" htmlFor="am-credits">Credits per request</label>
                <select id="am-credits" value={form.creditCost} disabled={editing}
                  onChange={(event) => update({ creditCost: event.target.value })}>
                  {CREDIT_COSTS.map((cost) => <option key={cost} value={cost}>{cost}</option>)}
                </select>
              </div>
            )}
            <div className="acf-field">
              <label className="acf-label" htmlFor="am-fallback">Fallback when out of credits</label>
              <select id="am-fallback" value={form.fallbackModelKey} disabled={editing || !form.capability}
                onChange={(event) => update({ fallbackModelKey: event.target.value })}>
                <option value="">None (the request fails)</option>
                {fallbacks.map((choice) => <option key={choice.value} value={choice.value}>{choice.label}</option>)}
              </select>
            </div>
            {!editing && (
              <>
                <label className="acf-toggle">
                  <input type="checkbox" checked={form.enabled} onChange={(event) => update({ enabled: event.target.checked })} />
                  <span>Enabled</span>
                </label>
                <label className="acf-toggle">
                  <input type="checkbox" checked={form.userSelectable} onChange={(event) => update({ userSelectable: event.target.checked })} />
                  <span>Users can choose it</span>
                </label>
              </>
            )}
          </div>
        </section>

        {prices.length > 0 && (
          <section className="aia-card acf-group">
            <h3>Provider prices (USD)</h3>
            <div className="acf-fields">
              {prices.includes('tokens') && (
                <>
                  <PriceField id="am-in" label="Input, per million tokens" value={form.inputUsdPerMillion} onChange={(value) => update({ inputUsdPerMillion: value })} />
                  <PriceField id="am-out" label="Output, per million tokens" value={form.outputUsdPerMillion} onChange={(value) => update({ outputUsdPerMillion: value })} />
                  <PriceField id="am-cached" label="Cached input, per million tokens" value={form.cachedInputUsdPerMillion} onChange={(value) => update({ cachedInputUsdPerMillion: value })} />
                </>
              )}
              {prices.includes('image') && (
                <PriceField id="am-image" label="Per image" value={form.perImageUsd} onChange={(value) => update({ perImageUsd: value })} />
              )}
              <small className="aia-muted acf-help">Optional. Used only for cost analytics; without them the cost shows as unknown.</small>
            </div>
          </section>
        )}
      </div>
    </div>
  )
}

function PriceField({ id, label, value, onChange }: { id: string; label: string; value: string; onChange: (value: string) => void }) {
  return (
    <div className="acf-field">
      <label className="acf-label" htmlFor={id}>{label}</label>
      <input id={id} type="text" inputMode="decimal" value={value} placeholder="e.g. 0.20" onChange={(event) => onChange(event.target.value)} />
    </div>
  )
}
