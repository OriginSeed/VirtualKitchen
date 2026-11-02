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
