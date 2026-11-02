/**
 * AI model registry & credits API
 * Self-service view of the current user's AI credit balance/history and model
 * choices, and the read-only AI model registry.
 */

import { apiGet, apiPut } from './client'
import { API } from './endpoints'

export type ModelTier = 'PAID' | 'OPEN_SOURCE'
export type AiCapability = 'TEXT_TO_TEXT' | 'TEXT_TO_IMAGE'

export interface ModelDefinition {
  key: string
  capability: AiCapability
  tier: ModelTier
  providerModelId: string
  creditCost: number
  enabled: boolean
  displayName: string
  userSelectable: boolean
  /** What runs when the user hasn't chosen a model. */
  defaultModel: boolean
}

/** The user's own model choices; credits are charged at the chosen model's cost. */
export interface AiPreferences {
  /** The user's usable choice per capability; null means the default model. */
  modelKeys: Partial<Record<AiCapability, string | null>>
  /** The model each capability's requests run on right now. */
  effective: Partial<Record<AiCapability, string | null>>
  /** A saved choice is no longer offered, so the default applies instead. */
  staleChoice: boolean
}

export interface UserAiCredit {
  userId: number
  monthlyAllocation: number
  availableBalance: number
  reservedBalance: number
  cycleStart: string
  cycleEnd: string
}

export type CreditTransactionType = 'RESERVE' | 'CONSUME' | 'RELEASE' | 'MONTHLY_GRANT' | 'ADMIN_ADJUSTMENT'

export interface AiCreditTransaction {
  id: string
  requestId?: string
  type: CreditTransactionType
  amount: number
  balanceAfter: number
  capability?: AiCapability
  modelKey?: string
  tier?: ModelTier
  createdAt: string
}

export const AiApi = {
  async getModels(capability?: AiCapability): Promise<ModelDefinition[]> {
    return apiGet<ModelDefinition[]>(API.ai.models, { params: capability ? { capability } : undefined })
  },

  /** Models the user may choose for `capability` (enabled and offered to users by the admin). */
  async getSelectableModels(capability: AiCapability): Promise<ModelDefinition[]> {
    return apiGet<ModelDefinition[]>(API.ai.selectableModels, { params: { capability } })
  },

  async getMyPreferences(): Promise<AiPreferences> {
    return apiGet<AiPreferences>(API.ai.myPreferences)
  },

  /** A null key clears the choice (back to the default model). */
  async saveMyPreferences(modelKeys: Partial<Record<AiCapability, string | null>>): Promise<AiPreferences> {
    return apiPut<AiPreferences>(API.ai.myPreferences, { modelKeys })
  },

  async getMyCredits(): Promise<UserAiCredit> {
    return apiGet<UserAiCredit>(API.ai.myCredits)
  },

  async getMyCreditHistory(): Promise<AiCreditTransaction[]> {
    return apiGet<AiCreditTransaction[]>(API.ai.myCreditHistory)
  },
}
