/** Cheap, deterministic quality signals for one run (no AI judging). */

import type { Process } from '../../../frontEnd/src/types/process'
import type { Quality, RunRecord, StepSummary } from './types'

const ratio = (part: number, whole: number) => (whole > 0 ? Math.round((part / whole) * 1000) / 1000 : null)

export function assessQuality(record: RunRecord, processes: Process[], steps: StepSummary[], problems: string[]): Quality {
  let danglingEdges = 0
  let isolatedNodes = 0
  for (const process of processes) {
    const ids = new Set(process.nodes.map((node) => node.id))
    const linked = new Set<string>()
    for (const edge of process.edges) {
      if (!ids.has(edge.source) || !ids.has(edge.target)) danglingEdges++
      linked.add(edge.source)
      linked.add(edge.target)
    }
    if (process.nodes.length > 1) isolatedNodes += process.nodes.filter((node) => !linked.has(node.id)).length
  }

  const stepRows = steps.filter((step) => step.kind === 'step')
  const titleCounts = new Map<string, number>()
  for (const step of steps) {
    const title = step.title.toLowerCase()
    if (title) titleCounts.set(title, (titleCounts.get(title) ?? 0) + 1)
  }

  const { text, image, speech } = record.stages
  const readyAudio = speech?.steps.filter((step) => step.status === 'READY' && step.text && step.durationSeconds) ?? []
  const chars = readyAudio.reduce((sum, step) => sum + (step.text?.length ?? 0), 0)
  const seconds = readyAudio.reduce((sum, step) => sum + (step.durationSeconds ?? 0), 0)

  return {
    problems,
    processCount: processes.length,
    stepCount: stepRows.length,
    conditionCount: steps.length - stepRows.length,
    emptyTitles: steps.filter((step) => !step.title).length,
    duplicateTitles: [...titleCounts.values()].filter((count) => count > 1).reduce((sum, count) => sum + count - 1, 0),
    emptyDescriptions: stepRows.filter((step) => !step.description).length,
    stepsWithoutDuration: stepRows.filter((step) => !step.duration).length,
    danglingEdges,
    isolatedNodes,
    warningCount: text?.warnings.length ?? 0,
    repairCount: text?.repairs.length ?? 0,
    imageSuccessRate: image && image.status !== 'skipped' ? ratio(image.succeeded, image.totalSteps) : null,
    narrationSuccessRate: speech && speech.status !== 'skipped' ? ratio(speech.ready, speech.narratable) : null,
    audioSecondsPer100Chars: chars > 0 ? Math.round((seconds / chars) * 100 * 100) / 100 : null,
  }
}
