/**
 * What a bench run will ask the AI for, worked out before anything runs: how many flows are
 * generated against how many are copied, how many image runs, and how many narrations are spoken
 * against how many are reused. Mirrors the sharing rules of run.ts.
 */

import type { Combo, RecipeInput, Stage } from './config.ts'

export type Spend = {
  flowsGenerated: number
  flowsReused: number
  flowsFromFile: number
  imageRuns: number
  narrations: number
  narrationsReused: number
}

const empty = (): Spend => ({ flowsGenerated: 0, flowsReused: 0, flowsFromFile: 0, imageRuns: 0, narrations: 0, narrationsReused: 0 })

/** The spend of each group, by group index, in running order. */
export function planSpend(combos: Combo[], recipes: RecipeInput[], repeats: number, stages: Stage[]): Map<number, Spend> {
  const byGroup = new Map<number, Spend>()
  const flows = new Map<number, Set<string>>()
  const narrations = new Map<number, Set<string>>()
  const seen = (store: Map<number, Set<string>>, group: number) => {
    if (!store.has(group)) store.set(group, new Set())
    return store.get(group)!
  }
  for (const combo of combos) {
    const spend = byGroup.get(combo.group) ?? empty()
    byGroup.set(combo.group, spend)
    for (const recipe of recipes) {
      for (let repeat = 0; repeat < repeats; repeat++) {
        const flowKey = recipe.flow ? `file ${recipe.id}` : `${recipe.id}\u0000${combo.flowText ?? ''}`
        if (recipe.flow) {
          spend.flowsFromFile++
        } else if (combo.shared && seen(flows, combo.group).has(flowKey)) {
          spend.flowsReused++
        } else {
          spend.flowsGenerated++
          if (combo.shared) seen(flows, combo.group).add(flowKey)
        }
        if (stages.includes('image') && combo.image) spend.imageRuns++
        if (stages.includes('speech') && combo.speech) {
          const narrationKey = `${flowKey}\u0000${combo.speech}`
          if (combo.shared && seen(narrations, combo.group).has(narrationKey)) {
            spend.narrationsReused++
          } else {
            spend.narrations++
            if (combo.shared) seen(narrations, combo.group).add(narrationKey)
          }
        }
      }
    }
  }
  return byGroup
}

export const total = (spends: Iterable<Spend>): Spend => {
  const sum = empty()
  for (const spend of spends) {
    for (const key of Object.keys(sum) as (keyof Spend)[]) sum[key] += spend[key]
  }
  return sum
}

/** "1 generated, 3 copied · 4 image runs · 1 narrated, 3 reused" */
export function describeSpend(spend: Spend): string {
  const flowParts = [
    spend.flowsGenerated ? `${spend.flowsGenerated} generated` : '',
    spend.flowsReused ? `${spend.flowsReused} copied` : '',
    spend.flowsFromFile ? `${spend.flowsFromFile} from file` : '',
  ].filter(Boolean)
  const narrationParts = [
    spend.narrations ? `${spend.narrations} narrated` : '',
    spend.narrationsReused ? `${spend.narrationsReused} reused` : '',
  ].filter(Boolean)
  return [
    `flows: ${flowParts.join(', ') || 'none'}`,
    `image runs: ${spend.imageRuns}`,
    `narration: ${narrationParts.join(', ') || 'none'}`,
  ].join(' · ')
}
