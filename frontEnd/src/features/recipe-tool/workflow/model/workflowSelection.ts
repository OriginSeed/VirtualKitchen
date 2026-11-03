import type { RecipeAiTaskSelection, RecipeAiTaskType, RunModelCapability } from '../../../../types/recipeAiWorkflow'

/**
 * "What would you like AI to create?" — which tasks are ticked. "Complete Recipe Experience" is not a
 * task of its own: it is ticked exactly when every task is, ticking it selects them all and unticking
 * it clears them, so it can never add a task twice.
 */
export type WorkflowTaskSelection = Record<RecipeAiTaskType, boolean>

/** Display and dependency order (the process first; visuals and narration follow from it). */
export const WORKFLOW_TASK_ORDER: readonly RecipeAiTaskType[] = ['PROCESS', 'VISUALS', 'NARRATION']

export const createWorkflowTaskSelection = (selectAll = true): WorkflowTaskSelection => ({
  PROCESS: selectAll,
  VISUALS: selectAll,
  NARRATION: selectAll,
})

/** A selection with exactly `tasks` ticked — how the editor's AI menu opens AI Recipe Creation pre-set. */
export const workflowTaskSelectionFrom = (tasks: readonly RecipeAiTaskType[]): WorkflowTaskSelection => ({
  PROCESS: tasks.includes('PROCESS'),
  VISUALS: tasks.includes('VISUALS'),
  NARRATION: tasks.includes('NARRATION'),
})

export const isCompleteExperience =(selection: WorkflowTaskSelection) =>
  WORKFLOW_TASK_ORDER.every((task) => selection[task])

export const toggleWorkflowTask = (selection: WorkflowTaskSelection, task: RecipeAiTaskType): WorkflowTaskSelection => ({
  ...selection,
  [task]: !selection[task],
})

export const setCompleteExperience = (_selection: WorkflowTaskSelection, checked: boolean): WorkflowTaskSelection =>
  createWorkflowTaskSelection(checked)

/** The ticked tasks, each once, in {@link WORKFLOW_TASK_ORDER}. */
export const selectedWorkflowTasks = (selection: WorkflowTaskSelection): RecipeAiTaskType[] =>
  WORKFLOW_TASK_ORDER.filter((task) => selection[task])

/** Models picked for one run; '' (or missing) keeps the user's account setting. */
export type RunModels = Partial<Record<RunModelCapability, string>>

/**
 * Which run models a selection uses: text for the process and for the step image prompts, images for the visuals,
 * speech for the narration.
 */
export const runModelCapabilities = (selection: WorkflowTaskSelection): RunModelCapability[] => [
  ...(selection.PROCESS || selection.VISUALS ? ['TEXT_TO_TEXT' as const] : []),
  ...(selection.VISUALS ? ['TEXT_TO_IMAGE' as const] : []),
  ...(selection.NARRATION ? ['TEXT_TO_SPEECH' as const] : []),
]

/**
 * The request shape the backend expects (it normalises again, so duplicates are impossible either way). Only the run
 * models the selection uses, and that differ from the account setting, are sent.
 */
export const toTaskSelectionRequest = (selection: WorkflowTaskSelection, runModels: RunModels = {}): RecipeAiTaskSelection => {
  const modelKeys: RunModels = {}
  for (const capability of runModelCapabilities(selection)) {
    const key = runModels[capability]
    if (key) modelKeys[capability] = key
  }
  return {
    tasks: selectedWorkflowTasks(selection),
    completeExperience: isCompleteExperience(selection),
    ...(Object.keys(modelKeys).length > 0 ? { modelKeys } : {}),
  }
}

/**
 * Why "Create Recipe" can't run yet, or null when it can. Visuals and narration on their own work on
 * the recipe's existing process, so they need one with steps; generating a process needs recipe text.
 */
export const workflowSelectionProblem = (
  selection: WorkflowTaskSelection,
  context: { recipeText: string; existingStepCount: number },
): string | null => {
  const tasks = selectedWorkflowTasks(selection)
  if (tasks.length === 0) return 'Select at least one thing for AI to create.'
  if (selection.PROCESS) {
    return context.recipeText.trim() ? null : 'Describe your recipe so AI can create its process.'
  }
  return context.existingStepCount > 0 ? null : 'This recipe has no steps yet. Select Recipe Process to create them first.'
}
