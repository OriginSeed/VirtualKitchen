/**
 * Building a recipe file's processes into a recipe, through the same save path as the editor
 * (`persistSnapshot`): the processes the recipe doesn't have yet are created, references between them
 * are rewritten to the new ids, and everything is saved in one batch. No AI call, and step ids are kept.
 *
 * Framework- and fetch-free: the API calls and the Action On reference rewriting are injected, so it is
 * tested directly (frontEnd/tests/importRecipeFlow.test.ts) and reused by tools/ai-bench.
 */

import type { Process } from '../../../../types/process'
import { withTemporaryIds, type FlowDoc } from '../../model/recipeFlowFile.ts'
import { createPersistedState, persistSnapshot, type PersistenceApi, type ProcessNodesRemapper } from './snapshotPersistence.ts'

export type ImportApi = PersistenceApi & {
  /** The recipe's current process revision (0 for a new recipe). */
  loadRevision: () => Promise<number>
  /** The processes the recipe already has (a new recipe may already have its MAIN). */
  listProcesses: () => Promise<Process[]>
}

export type ImportedFlow = {
  /** The file's temporary process id -> the id of the process it became in this recipe. */
  processIds: Map<number, number>
}

/**
 * Builds `flow` into the recipe. The recipe should be a fresh one: any Preparation it already has that the
 * file doesn't is removed, exactly as when the editor saves a session without it.
 */
export async function importRecipeFlow(
  recipeId: number, flow: FlowDoc, api: ImportApi, remapNodes: ProcessNodesRemapper,
): Promise<ImportedFlow> {
  const [revision, existing] = await Promise.all([api.loadRevision(), api.listProcesses()])
  // Temporary ids again, whatever the file holds: they must not collide with the recipe's own process ids.
  const snapshot = withTemporaryIds(flow.processes.map((process) => ({ ...process, recipeId }) as unknown as Process), remapNodes)
  const fileIds = [...flow.processes].sort((a, b) => Number(b.type === 'MAIN') - Number(a.type === 'MAIN')).map((process) => process.id)
  const processIds = new Map<number, number>()
  const state = createPersistedState(existing, revision)
  await persistSnapshot(snapshot, state, api, remapNodes, (assigned) => {
    // `assigned` maps the snapshot's temporary id (-1, -2, …, MAIN first) to the real one.
    assigned.forEach((real, temporary) => processIds.set(fileIds[-temporary - 1], real))
  })
  return { processIds }
}
