/**
 * Admin runtime config API. The backend owns the catalog: every field's allowed values, its configured baseline and
 * its live value. Changes apply live and survive restarts; resetting a section restores application properties.
 */

import { apiDelete, apiGet, apiPost, apiPut } from '../../../../api/client'

const BASE = '/api/v1/admin/config'

/** TEXT is free text (a sender address, an SMTP user name); everything listable is a SELECT. */
export type ConfigFieldType = 'SELECT' | 'TOGGLE' | 'TEXT' | 'SECRET'

export interface ConfigField {
  key: string
  label: string
  group: string
  type: ConfigFieldType
  /** The only values the backend accepts. '' means "none". Empty for secrets. */
  options: string[]
  /** From application properties; masked for secrets. */
  baseline: string
  /** Live value; masked for secrets. */
  current: string
  overridden: boolean
  help: string | null
}

export interface ConfigSectionView {
  id: string
  label: string
  description: string
  available: boolean
  unavailableReason: string | null
  fields: ConfigField[]
}

export type ModelCapability = 'TEXT_TO_TEXT' | 'TEXT_TO_IMAGE' | 'TEXT_TO_SPEECH'
export type ModelTier = 'PAID' | 'OPEN_SOURCE'

export interface Choice {
  value: string
  label: string
}

/** What the "add model" form's dropdowns offer. */
export interface AddModelOptions {
  capabilities: ModelCapability[]
  /** Per capability, the providers a new model can use (those whose model id can be chosen). */
  providers: Record<ModelCapability, Choice[]>
  tiers: ModelTier[]
  /** Per capability, the models a new one can fall back to. */
  fallbacks: Record<ModelCapability, Choice[]>
  /** Every key in use. */
  takenKeys: string[]
}

/** A model added from the admin panel, as stored. */
export interface AdminModel {
  key: string
  capability: ModelCapability
  tier: ModelTier
  providerBean: string
  providerModelId: string
  displayName: string
  creditCost: number
  fallbackModelKey: string | null
  enabled: boolean
  userSelectable: boolean
  inputUsdPerMillion: number | null
  outputUsdPerMillion: number | null
  cachedInputUsdPerMillion: number | null
  perImageUsd: number | null
}

export type AdminModelRequest = Omit<AdminModel, 'fallbackModelKey'> & { fallbackModelKey: string | null }

/** A provider's model list; `error` says why it couldn't be loaded, `supported` false when it has none at all. */
export interface ProviderModelListing {
  models: { id: string; label: string }[]
  supported: boolean
  error: string | null
}

const CATALOG = `${BASE}/models/catalog`

/** Models added from the admin panel: they join the configured ones and work at once. */
export const AdminModelApi = {
  options: () => apiGet<AddModelOptions>(`${CATALOG}/options`),
  list: () => apiGet<AdminModel[]>(CATALOG),
  providerModels: (providerBean: string, capability: ModelCapability) =>
    apiGet<ProviderModelListing>(`${CATALOG}/provider-models`, { params: { providerBean, capability } }),
  add: (model: AdminModelRequest) => apiPost<AdminModel>(CATALOG, model),
  /** Only the provider model id, display name and prices change on edit. */
  update: (key: string, model: AdminModelRequest) => apiPut<AdminModel>(`${CATALOG}/${encodeURIComponent(key)}`, model),
  remove: (key: string) => apiDelete<void>(`${CATALOG}/${encodeURIComponent(key)}`),
}

export const AdminConfigApi = {
  list: () => apiGet<ConfigSectionView[]>(BASE),
  section: (id: string) => apiGet<ConfigSectionView>(`${BASE}/${encodeURIComponent(id)}`),
  update: (id: string, values: Record<string, string>) =>
    apiPut<ConfigSectionView>(`${BASE}/${encodeURIComponent(id)}`, { values }),
  reset: (id: string) => apiDelete<ConfigSectionView>(`${BASE}/${encodeURIComponent(id)}`),
  /** Positive grants, negative deducts; the backend refuses to push a balance below zero. */
  grantCredits: (userId: number, amount: number) =>
    apiPost<unknown>(`/api/v1/admin/users/${userId}/ai-credits/grant`, { amount }),
}
