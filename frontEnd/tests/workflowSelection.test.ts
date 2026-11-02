import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  createWorkflowTaskSelection,
  isCompleteExperience,
  selectedWorkflowTasks,
  setCompleteExperience,
  toggleWorkflowTask,
  runModelCapabilities,
  toTaskSelectionRequest,
  workflowSelectionProblem,
  workflowTaskSelectionFrom,
} from '../src/features/recipe-tool/workflow/model/workflowSelection.ts'

describe('AI Recipe Creation task selection', () => {
  it('starts with the complete recipe experience selected', () => {
    const selection = createWorkflowTaskSelection()
    assert.equal(isCompleteExperience(selection), true)
    assert.deepEqual(selectedWorkflowTasks(selection), ['PROCESS', 'VISUALS', 'NARRATION'])
  })

  it('lets every task be deselected and reselected on its own', () => {
    let selection = createWorkflowTaskSelection()
    selection = toggleWorkflowTask(selection, 'VISUALS')
    assert.deepEqual(selectedWorkflowTasks(selection), ['PROCESS', 'NARRATION'])
    assert.equal(isCompleteExperience(selection), false, 'complete experience unticks when a task is removed')
    selection = toggleWorkflowTask(selection, 'VISUALS')
    assert.equal(isCompleteExperience(selection), true, 'and ticks again once every task is back')
  })

  it('supports each combination of tasks', () => {
    const only = (...tasks: Array<'PROCESS' | 'VISUALS' | 'NARRATION'>) =>
      tasks.reduce((selection, task) => toggleWorkflowTask(selection, task), createWorkflowTaskSelection(false))
    assert.deepEqual(selectedWorkflowTasks(only('PROCESS')), ['PROCESS'])
    assert.deepEqual(selectedWorkflowTasks(only('VISUALS')), ['VISUALS'])
    assert.deepEqual(selectedWorkflowTasks(only('NARRATION')), ['NARRATION'])
    assert.deepEqual(selectedWorkflowTasks(only('NARRATION', 'PROCESS')), ['PROCESS', 'NARRATION'], 'always in dependency order')
    assert.deepEqual(selectedWorkflowTasks(only('VISUALS', 'PROCESS')), ['PROCESS', 'VISUALS'])
  })

  it('builds a selection of exactly the given tasks (the editor AI menu presets)', () => {
    assert.deepEqual(selectedWorkflowTasks(workflowTaskSelectionFrom(['NARRATION'])), ['NARRATION'])
    assert.deepEqual(selectedWorkflowTasks(workflowTaskSelectionFrom(['PROCESS'])), ['PROCESS'])
    assert.equal(isCompleteExperience(workflowTaskSelectionFrom(['NARRATION', 'PROCESS', 'VISUALS'])), true)
    assert.deepEqual(selectedWorkflowTasks(workflowTaskSelectionFrom([])), [])
  })

  it('treats complete recipe experience as select-all / clear-all, never as an extra task', () => {
    const cleared = setCompleteExperience(createWorkflowTaskSelection(), false)
    assert.deepEqual(selectedWorkflowTasks(cleared), [])
    const all = setCompleteExperience(toggleWorkflowTask(cleared, 'VISUALS'), true)
    assert.deepEqual(toTaskSelectionRequest(all), { tasks: ['PROCESS', 'VISUALS', 'NARRATION'], completeExperience: true })
    assert.equal(new Set(toTaskSelectionRequest(all).tasks).size, 3, 'no duplicate tasks')
  })

  it('sends only the run models the selection uses, and only when they differ from the account setting', () => {
    const narrationOnly = workflowTaskSelectionFrom(['NARRATION'])
    assert.deepEqual(runModelCapabilities(narrationOnly), [])
    assert.equal(toTaskSelectionRequest(narrationOnly, { TEXT_TO_TEXT: 'premium' }).modelKeys, undefined)

    const processOnly = workflowTaskSelectionFrom(['PROCESS'])
    assert.deepEqual(runModelCapabilities(processOnly), ['TEXT_TO_TEXT'])
    assert.deepEqual(toTaskSelectionRequest(processOnly, { TEXT_TO_TEXT: 'premium', TEXT_TO_IMAGE: 'flux' }).modelKeys,
      { TEXT_TO_TEXT: 'premium' })

    const visuals = workflowTaskSelectionFrom(['VISUALS'])
    assert.deepEqual(runModelCapabilities(visuals), ['TEXT_TO_TEXT', 'TEXT_TO_IMAGE'], 'visuals write prompts with text')
    assert.deepEqual(toTaskSelectionRequest(visuals, { TEXT_TO_TEXT: '', TEXT_TO_IMAGE: 'flux' }).modelKeys,
      { TEXT_TO_IMAGE: 'flux' })
  })

  it('explains what is missing before Create Recipe can run', () => {
    const none = createWorkflowTaskSelection(false)
    assert.match(workflowSelectionProblem(none, { recipeText: '', existingStepCount: 4 }) ?? '', /at least one/)

    const all = createWorkflowTaskSelection()
    assert.match(workflowSelectionProblem(all, { recipeText: '   ', existingStepCount: 0 }) ?? '', /Describe your recipe/)
    assert.equal(workflowSelectionProblem(all, { recipeText: 'Boil 2 eggs', existingStepCount: 0 }), null)

    const visualsOnly = toggleWorkflowTask(none, 'VISUALS')
    assert.match(workflowSelectionProblem(visualsOnly, { recipeText: '', existingStepCount: 0 }) ?? '', /no steps yet/)
    assert.equal(workflowSelectionProblem(visualsOnly, { recipeText: '', existingStepCount: 3 }), null)
  })
})
