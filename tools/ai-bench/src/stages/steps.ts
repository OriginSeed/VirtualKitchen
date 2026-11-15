/** Reading the saved processes back as plain step summaries (for quality checks and the report). */

import type { Process } from '../../../../frontEnd/src/types/process'
import { RECIPE_NODE_TYPES } from '../../../../frontEnd/src/features/recipe-tool/model/recipeNodeTypes'
import type { StepSummary } from '../types'

export const isStep = (node: Process['nodes'][number]) => node.type === RECIPE_NODE_TYPES.step
export const isCondition = (node: Process['nodes'][number]) => node.type === RECIPE_NODE_TYPES.condition

export const hasSteps = (process: Process) => process.nodes.some(isStep)

const str = (value: unknown) => (typeof value === 'string' ? value.trim() : '')

export function summarizeSteps(processes: Process[]): StepSummary[] {
  // MAIN first, then subprocesses; nodes in saved order (the converter writes them in reading order).
  const ordered = [...processes].sort((a, b) => (a.type === 'MAIN' ? -1 : 0) - (b.type === 'MAIN' ? -1 : 0))
  return ordered.flatMap((process) => process.nodes
    .filter((node) => isStep(node) || isCondition(node))
    .map((node): StepSummary => {
      const data = (node.data ?? {}) as Record<string, unknown>
      const step = (data.step ?? {}) as Record<string, unknown>
      const condition = isCondition(node)
      const durationValue = str(step.durationValue)
      return {
        processId: process.id,
        processName: process.name,
        processType: process.type,
        nodeId: node.id,
        kind: condition ? 'condition' : 'step',
        title: str(data.title),
        description: condition ? str(data.description) : str(step.actionDescription),
        duration: durationValue ? `${durationValue} ${str(step.durationUnit)}`.trim() : '',
      }
    }))
}
