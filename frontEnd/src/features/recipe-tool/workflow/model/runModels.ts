/**
 * Picking the AI models for one run, shared by every editor action that starts AI work (AI Recipe Creation and its
 * approval/retries, Edit Process with AI, Generate Step Visuals). '' keeps the user's account setting
 * (Account Settings), which each select shows as its first option with the model it currently resolves to.
 */
import { AiApi } from '../../../../api/aiApi'
import type { RunModelCapability } from '../../../../types/recipeAiWorkflow'
import { useApiData } from '../../../../shared/hooks/useApiData'
import type { RunModels } from './workflowSelection'

export const RUN_MODEL_LABELS: Record<RunModelCapability, string> = {
  TEXT_TO_TEXT: 'Text model',
  TEXT_TO_IMAGE: 'Image model',
  TEXT_TO_SPEECH: 'Narration model',
}

/** What the run model picker offers: the models the user may choose, and what their requests run on now. */
export async function loadRunModelChoices() {
  const [preferences, text, image, speech] = await Promise.all([
    AiApi.getMyPreferences(),
    AiApi.getSelectableModels('TEXT_TO_TEXT'),
    AiApi.getSelectableModels('TEXT_TO_IMAGE'),
    AiApi.getSelectableModels('TEXT_TO_SPEECH'),
  ])
  return { effective: preferences.effective, models: { TEXT_TO_TEXT: text, TEXT_TO_IMAGE: image, TEXT_TO_SPEECH: speech } }
}

export type RunModelChoices = Awaited<ReturnType<typeof loadRunModelChoices>>

export const useRunModelChoices = () => useApiData('run-model-choices', loadRunModelChoices)

/** Only the capabilities asked for, without blanks: what a start request sends. */
export const pickRunModels = (models: RunModels, capabilities: RunModelCapability[]): RunModels => {
  const picked: RunModels = {}
  for (const capability of capabilities) {
    const key = models[capability]
    if (key) picked[capability] = key
  }
  return picked
}

/**
 * Every capability asked for, '' where the account setting was kept: for approval and retry, where '' must clear
 * a choice the run was started with (the backend treats blank as "account setting").
 */
export const runModelsFor = (models: RunModels, capabilities: RunModelCapability[]): RunModels =>
  Object.fromEntries(capabilities.map((capability) => [capability, models[capability] ?? ''])) as RunModels

/** Models a retry of one task runs on: the text model writes prompts and processes, then images or speech. */
export const taskRunModelCapabilities = (task: 'PROCESS' | 'VISUALS' | 'NARRATION'): RunModelCapability[] =>
  task === 'PROCESS' ? ['TEXT_TO_TEXT'] : task === 'VISUALS' ? ['TEXT_TO_TEXT', 'TEXT_TO_IMAGE'] : ['TEXT_TO_SPEECH']
