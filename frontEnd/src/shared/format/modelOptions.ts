import type { ModelDefinition } from '../../api/aiApi.ts'

/** How a model's price reads in the picker, e.g. "⚡ 2 credits" or "free". */
export function creditLabel(model: Pick<ModelDefinition, 'creditCost' | 'tier'>): string {
  const cost = model.tier === 'OPEN_SOURCE' ? 0 : model.creditCost
  if (cost <= 0) return 'free'
  return `⚡ ${cost} credit${cost === 1 ? '' : 's'}`
}

export interface ModelOption {
  value: string
  label: string
  description?: string
}

/**
 * Picker options for one capability: first "Default", which follows whatever the admin makes the default (named
 * when it is offered to users), then every model the user may choose.
 */
export function modelOptions(models: ModelDefinition[]): ModelOption[] {
  const fallback = models.find(model => model.defaultModel)
  const options: ModelOption[] = [{
    value: '',
    label: fallback ? `Default (${fallback.displayName} · ${creditLabel(fallback)})` : 'Default',
    description: 'Follows the default model your administrator picks.',
  }]
  for (const model of models) {
    options.push({ value: model.key, label: `${model.displayName} · ${creditLabel(model)}` })
  }
  return options
}

/**
 * Options for choosing a model for one run: first "Account setting", which keeps whatever the user's requests run on
 * now (`effectiveKey`, named when it is offered), then every model the user may choose.
 */
export function runModelOptions(models: ModelDefinition[], effectiveKey: string | null | undefined): ModelOption[] {
  const current = models.find(model => model.key === effectiveKey)
  return [
    { value: '', label: current ? `Account setting (${current.displayName} · ${creditLabel(current)})` : 'Account setting' },
    ...models.map(model => ({ value: model.key, label: `${model.displayName} · ${creditLabel(model)}` })),
  ]
}
