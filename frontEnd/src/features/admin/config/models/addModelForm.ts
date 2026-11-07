import type { AdminModel, AdminModelRequest, ModelCapability, ModelTier } from '../api/adminConfigApi.ts'

/** The add / edit form as typed: prices stay text until saved, so a half-typed number isn't lost. */
export interface ModelForm {
  key: string
  capability: ModelCapability | ''
  providerBean: string
  providerModelId: string
  displayName: string
  tier: ModelTier
  creditCost: string
  fallbackModelKey: string
  enabled: boolean
  userSelectable: boolean
  inputUsdPerMillion: string
  outputUsdPerMillion: string
  cachedInputUsdPerMillion: string
  perImageUsd: string
}

export const EMPTY_MODEL_FORM: ModelForm = {
  key: '',
  capability: '',
  providerBean: '',
  providerModelId: '',
  displayName: '',
  tier: 'PAID',
  creditCost: '1',
  fallbackModelKey: '',
  enabled: true,
  userSelectable: false,
  inputUsdPerMillion: '',
  outputUsdPerMillion: '',
  cachedInputUsdPerMillion: '',
  perImageUsd: '',
}

export const CREDIT_COSTS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10']

const KEY_PATTERN = /^[a-z0-9][a-z0-9-]{1,47}$/

/** The form of an existing added model, for editing. */
export function formFrom(model: AdminModel): ModelForm {
  const text = (value: number | null) => (value == null ? '' : String(value))
  return {
    key: model.key,
    capability: model.capability,
    providerBean: model.providerBean,
    providerModelId: model.providerModelId,
    displayName: model.displayName,
    tier: model.tier,
    creditCost: String(model.creditCost),
    fallbackModelKey: model.fallbackModelKey ?? '',
    enabled: model.enabled,
    userSelectable: model.userSelectable,
    inputUsdPerMillion: text(model.inputUsdPerMillion),
    outputUsdPerMillion: text(model.outputUsdPerMillion),
    cachedInputUsdPerMillion: text(model.cachedInputUsdPerMillion),
    perImageUsd: text(model.perImageUsd),
  }
}

/**
 * A key suggestion from the provider and model id, e.g. "deepInfraAiClient" + "Qwen/Qwen3-235B-A22B" →
 * "deepinfra-qwen3-235b-a22b". Only the model id's last path segment is used.
 */
export function suggestKey(providerBean: string, providerModelId: string): string {
  const provider = providerBean.replace(/(Ai|Image|Tts)?(Client|Provider)$/, '').toLowerCase()
  const model = (providerModelId.split('/').pop() ?? '').toLowerCase()
  const slug = `${provider}-${model}`.replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '')
  return slug.slice(0, 48).replace(/-+$/, '')
}

/** A readable name from the model id's last segment, e.g. "Qwen/Qwen3-235B-A22B" → "Qwen3 235B A22B". */
export function suggestDisplayName(providerModelId: string): string {
  return (providerModelId.split('/').pop() ?? '').replace(/[-_:]+/g, ' ').trim()
}

/** Which price fields apply: tokens for text, per image for images, none for speech. */
export function priceFields(capability: ModelCapability | ''): ('tokens' | 'image')[] {
  if (capability === 'TEXT_TO_TEXT') return ['tokens']
  if (capability === 'TEXT_TO_IMAGE') return ['image']
  return []
}

function price(value: string): number | null | undefined {
  const trimmed = value.trim()
  if (trimmed === '') return null
  const parsed = Number(trimmed)
  return Number.isFinite(parsed) && parsed >= 0 ? parsed : undefined
}

/** Why the form can't be saved yet, or null. `takenKeys` excludes the key being edited. */
export function formProblem(form: ModelForm, takenKeys: string[], editing: boolean): string | null {
  if (!editing) {
    if (!form.capability) return 'Choose what the model does.'
    if (!form.providerBean) return 'Choose a provider.'
    if (!KEY_PATTERN.test(form.key)) return 'The key must be 2–48 lowercase letters, digits or dashes.'
    if (takenKeys.includes(form.key)) return `A model with key "${form.key}" already exists.`
  }
  if (!form.providerModelId.trim()) return 'Enter the provider model id.'
  if (!form.displayName.trim()) return 'Enter a display name.'
  const prices = [form.inputUsdPerMillion, form.outputUsdPerMillion, form.cachedInputUsdPerMillion, form.perImageUsd]
  if (prices.some((value) => price(value) === undefined)) return 'Prices must be numbers of 0 or more.'
  return null
}

/** The request body; only the prices that apply to the capability are sent. Assumes {@link formProblem} is null. */
export function toRequest(form: ModelForm): AdminModelRequest {
  const fields = priceFields(form.capability)
  const tokens = fields.includes('tokens')
  const image = fields.includes('image')
  return {
    key: form.key.trim(),
    capability: form.capability as ModelCapability,
    providerBean: form.providerBean,
    providerModelId: form.providerModelId.trim(),
    displayName: form.displayName.trim(),
    tier: form.tier,
    creditCost: form.tier === 'OPEN_SOURCE' ? 0 : Number(form.creditCost),
    fallbackModelKey: form.fallbackModelKey || null,
    enabled: form.enabled,
    userSelectable: form.userSelectable,
    inputUsdPerMillion: tokens ? price(form.inputUsdPerMillion) ?? null : null,
    outputUsdPerMillion: tokens ? price(form.outputUsdPerMillion) ?? null : null,
    cachedInputUsdPerMillion: tokens ? price(form.cachedInputUsdPerMillion) ?? null : null,
    perImageUsd: image ? price(form.perImageUsd) ?? null : null,
  }
}

/** The model key of a Models & Credits field ("model.<key>.enabled"), or null for the section-wide ones. */
export function modelKeyOfField(fieldKey: string): string | null {
  const match = /^model\.(.+)\.[^.]+$/.exec(fieldKey)
  return match ? match[1] : null
}
