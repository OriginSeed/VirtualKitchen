import type {
  RecipeStoryboardPanel,
  RecipeStoryboardResponse,
  RecipeStoryboardStage,
  RecipeStoryboardStep,
} from '../../../types/recipe'

/**
 * How a recipe storyboard (the whole recipe as panels in one image) is worded and summarised for the UI.
 * Pure, so it is unit-tested; the components only render what these return.
 */

const STAGES: RecipeStoryboardStage[] = ['PLAN', 'PROMPT', 'IMAGE', 'CROP']

/** What the generation is doing at each stage, as shown while it runs. */
export const STORYBOARD_STAGE_LABELS: Record<RecipeStoryboardStage, string> = {
  PLAN: 'Planning the panels…',
  PROMPT: 'Writing the image prompt…',
  IMAGE: 'Drawing the storyboard…',
  CROP: 'Cutting the panels and adding them to the steps…',
}

/** What failed, for the error line of a failed generation. */
const FAILED_STAGE_LABELS: Record<RecipeStoryboardStage, string> = {
  PLAN: 'The panels could not be planned',
  PROMPT: 'The image prompt could not be written',
  IMAGE: 'The storyboard image could not be drawn',
  CROP: 'The storyboard could not be cut into panels',
}

export const isStoryboardRunning = (storyboard: Pick<RecipeStoryboardResponse, 'status'> | null | undefined): boolean =>
  storyboard?.status === 'QUEUED' || storyboard?.status === 'IN_PROGRESS'

export type StoryboardProgress = { percent: number; label: string; stageNumber: number; stageCount: number }

/** Where a running generation is: the stage in words, and how far through the stages it is. */
export const storyboardProgress = (storyboard: Pick<RecipeStoryboardResponse, 'status' | 'stage'>): StoryboardProgress => {
  const stageCount = STAGES.length
  if (storyboard.status === 'COMPLETED') return { percent: 100, label: 'Storyboard ready', stageNumber: stageCount, stageCount }
  if (storyboard.status === 'QUEUED' || !storyboard.stage) {
    return { percent: 5, label: 'Waiting to start…', stageNumber: 1, stageCount }
  }
  const index = Math.max(0, STAGES.indexOf(storyboard.stage))
  // Each stage is a quarter; a running stage counts as half done, so the bar never sits at 0 or reaches 100 early.
  const percent = Math.round(((index + 0.5) / stageCount) * 100)
  return { percent, label: STORYBOARD_STAGE_LABELS[storyboard.stage], stageNumber: index + 1, stageCount }
}

/** e.g. "Storyboard… 3/4", for the AI menu's item and button while a storyboard is being generated. */
export const storyboardProgressBadge = (storyboard: Pick<RecipeStoryboardResponse, 'status' | 'stage'>): string => {
  const progress = storyboardProgress(storyboard)
  return `${progress.stageNumber}/${progress.stageCount}`
}

/** The error line of a failed generation: what failed, then the backend's reason when it gave one. */
export const storyboardFailureText = (storyboard: Pick<RecipeStoryboardResponse, 'failedStage' | 'errorMessage'>): string => {
  const what = storyboard.failedStage ? FAILED_STAGE_LABELS[storyboard.failedStage] : 'The storyboard could not be generated'
  const reason = storyboard.errorMessage?.trim()
  return reason ? `${what}: ${reason}` : `${what}. Please try again.`
}

/** "3. Marinate chicken" */
export const storyboardPanelTitle = (panel: Pick<RecipeStoryboardPanel, 'sequence' | 'description'>): string =>
  `${panel.sequence}. ${panel.description.trim() || 'Panel'}`

/** "Preparation · covers 3 steps" (how many recipe nodes one panel stands for). */
export const storyboardPanelMeta = (panel: Pick<RecipeStoryboardPanel, 'group' | 'sourceStepIds'>): string => {
  const count = panel.sourceStepIds.length
  const group = panel.group === 'PREPARATION' ? 'Preparation' : 'Cooking'
  return `${group} · covers ${count} step${count === 1 ? '' : 's'}`
}

/** "7 panels in a 3 × 3 grid" */
export const storyboardLayoutText = (storyboard: Pick<RecipeStoryboardResponse, 'panels' | 'rows' | 'columns'>): string => {
  const count = storyboard.panels.length
  const panels = `${count} panel${count === 1 ? '' : 's'}`
  return storyboard.rows && storyboard.columns ? `${panels} in a ${storyboard.rows} × ${storyboard.columns} grid` : panels
}

/** The steps a storyboard gave a panel image to (a step that kept its own image is not one of them). */
export const storyboardStepImages = (storyboard: Pick<RecipeStoryboardResponse, 'steps'>): RecipeStoryboardStep[] =>
  (storyboard.steps ?? []).filter((step) => step.attached && Boolean(step.imageUrl))

/** e.g. "Panel images were added to 9 steps; 3 kept the image they already had." Empty when no step is covered. */
export const storyboardStepSummary = (storyboard: Pick<RecipeStoryboardResponse, 'steps'>): string => {
  const steps = storyboard.steps ?? []
  if (steps.length === 0) return ''
  const attached = storyboardStepImages(storyboard).length
  const kept = steps.length - attached
  const stepsText = (count: number) => `${count} step${count === 1 ? '' : 's'}`
  if (attached === 0) return `No step was changed: ${stepsText(kept)} kept the image ${kept === 1 ? 'it' : 'they'} already had.`
  const added = `Panel images were added to ${stepsText(attached)}`
  return kept > 0 ? `${added}; ${kept} kept the image ${kept === 1 ? 'it' : 'they'} already had.` : `${added}.`
}
