import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  flowFileName,
  imageLinksOf,
  parseFlowDoc,
  parseFlowText,
  stripVisuals,
  summarizeFlow,
  toFlowDoc,
  withTemporaryIds,
} from '../src/features/recipe-tool/model/recipeFlowFile.ts'
import {
  importRecipeFlow,
  type ImportApi,
} from '../src/features/recipe-tool/session/persistence/importRecipeFlow.ts'
import type { ProcessNodesRemapper } from '../src/features/recipe-tool/session/persistence/snapshotPersistence.ts'
import type { Process, ProcessBatchUpdateItem } from '../src/types/process.ts'

/** Test stand-in for remapActionOnProcessRefs: a step's `data.refs` are the process ids it uses. */
const remapNodes: ProcessNodesRemapper = (nodes, idMap) => {
  let changed = false
  const next = nodes.map((node) => {
    const refs = (node.data?.refs as number[] | undefined) ?? []
    const mapped = refs.map((ref) => idMap.get(ref) ?? ref)
    if (mapped.every((ref, i) => ref === refs[i])) return node
    changed = true
    return { ...node, data: { ...node.data, refs: mapped } }
  })
  return changed ? next : nodes
}

/** A recipe as the app holds it: a Preparation, and a MAIN whose second step uses it; some steps illustrated. */
const sourceRecipe = (): Process[] => [
  {
    id: 41, type: 'SUBPROCESS', recipeId: 7, name: 'Preparation', edges: [],
    nodes: [{ id: 'prep-1', kind: 'STEP', data: { title: 'Chop onions', visualizationAssetId: 5, imagePrompt: 'onions on a board', imageUrl: 'https://store/p.png' } }],
  },
  {
    id: 40, type: 'MAIN', recipeId: 7, name: 'Tomato Masala', edges: [{ id: 'e1', source: 'm-1', target: 'm-2' }],
    nodes: [
      { id: 'm-1', kind: 'STEP', data: { title: 'Add oil', visualization: { assetId: 6, imageUrl: 'https://store/m1.png', imagePrompt: 'oil in a pan', status: 'generated' } } },
      { id: 'm-2', kind: 'STEP', data: { title: 'Add onions', refs: [41] } },
      { id: 'c-1', kind: 'CONDITION', data: { title: 'Soft?' } },
    ],
  },
]

const exported = () => toFlowDoc({
  processes: sourceRecipe(), remapNodes, name: ' Tomato Masala ', description: 'A simple base', preparationNotes: 'Wash everything',
  nutrition: { calories: 120, servings: 2 },
  audio: [
    { processId: 40, stepId: 'm-1', url: 'https://store/a1.wav' },
    { processId: 41, stepId: 'prep-1', url: 'https://store/a2.wav' },
    { processId: 40, stepId: 'not-a-step', url: 'https://store/x.wav' },
    { processId: 999, stepId: 'm-1', url: 'https://store/y.wav' },
  ],
  now: new Date('2026-11-12T10:00:00Z'),
})

describe('recipe file: export', () => {
  it('holds the whole recipe with temporary ids, MAIN first, and step ids kept', () => {
    const doc = exported()

    assert.equal(doc.version, 1)
    assert.equal(doc.kind, 'virtual-kitchen-recipe')
    assert.equal(doc.name, 'Tomato Masala')
    assert.equal(doc.description, 'A simple base')
    assert.equal(doc.preparationNotes, 'Wash everything')
    assert.deepEqual(doc.nutrition, { calories: 120, servings: 2 })
    assert.equal(doc.exportedAt, '2026-11-12T10:00:00.000Z')
    assert.deepEqual(doc.processes.map((process) => [process.id, process.type, process.name]),
      [[-1, 'MAIN', 'Tomato Masala'], [-2, 'SUBPROCESS', 'Preparation']])
    assert.deepEqual(doc.processes[0].nodes.map((node) => node.id), ['m-1', 'm-2', 'c-1'])
    // The reference to the Preparation follows it to its temporary id; nothing of the source recipe's ids is left.
    assert.deepEqual(doc.processes[0].nodes[1].data?.refs, [-2])
    assert.ok(!JSON.stringify(doc).includes('"recipeId"'))
    assert.ok(!JSON.stringify(doc.processes).includes('41'))
  })

  it('lifts image links off the steps into assets, from both shapes, and maps audio to the file\'s ids', () => {
    const doc = exported()

    assert.deepEqual(doc.assets?.images, [
      { process: -1, stepId: 'm-1', url: 'https://store/m1.png', prompt: 'oil in a pan' },
      { process: -2, stepId: 'prep-1', url: 'https://store/p.png', prompt: 'onions on a board' },
    ])
    // Audio for a step or process the recipe doesn't have is left out.
    assert.deepEqual(doc.assets?.audio, [
      { process: -1, stepId: 'm-1', url: 'https://store/a1.wav' },
      { process: -2, stepId: 'prep-1', url: 'https://store/a2.wav' },
    ])
    const steps = JSON.stringify(doc.processes)
    for (const key of ['imageUrl', 'imagePrompt', 'visualizationAssetId', '"visualization"']) {
      assert.ok(!steps.includes(key), `no ${key} on the steps`)
    }
    assert.deepEqual(doc.processes[0].nodes[0].data, { title: 'Add oil' })
  })

  it('leaves the source untouched, and has no assets section when there is nothing to link', () => {
    const source = sourceRecipe()
    const before = JSON.stringify(source)
    toFlowDoc({ processes: source, remapNodes })
    assert.equal(JSON.stringify(source), before)

    const plain = toFlowDoc({ processes: stripVisuals(sourceRecipe()), remapNodes })
    assert.equal(plain.assets, undefined)
    const bench = toFlowDoc({ processes: sourceRecipe(), remapNodes, includeImages: false, recipe: 'tomato', textModel: 'gemini-flash' })
    assert.equal(bench.assets, undefined, 'a bench flow carries no images')
    assert.equal(bench.textModel, 'gemini-flash')
  })

  it('summarises and names the file', () => {
    assert.deepEqual(summarizeFlow(exported()), { processes: 2, steps: 3, images: 2, audio: 2 })
    assert.equal(flowFileName('Tomato Masala (v2)!'), 'tomato-masala-v2.recipe.json')
    assert.equal(flowFileName('  '), 'recipe.recipe.json')
    assert.equal(flowFileName(undefined), 'recipe.recipe.json')
  })

  it('reads image links from either node shape', () => {
    assert.deepEqual(imageLinksOf([{ id: 3, nodes: [
      { id: 'a', data: { imageUrl: ' https://x/a.png ' } },
      { id: 'b', data: { visualization: { imageUrl: 'https://x/b.png' }, imageUrl: 'https://x/old.png' } },
      { id: 'c', data: { visualization: { status: 'not_generated' } } },
      { id: 'd' },
    ] }]), [
      { process: 3, stepId: 'a', url: 'https://x/a.png' },
      { process: 3, stepId: 'b', url: 'https://x/b.png' },
    ])
  })

  it('gives temporary ids even to processes that already have them in another order', () => {
    const shuffled = [
      { ...sourceRecipe()[0], id: -1, nodes: [{ id: 'prep-1', kind: 'STEP' as const, data: {} }] },
      { ...sourceRecipe()[1], id: -2, nodes: [{ id: 'm-2', kind: 'STEP' as const, data: { refs: [-1] } }] },
    ]

    const result = withTemporaryIds(shuffled, remapNodes)

    assert.deepEqual(result.map((process) => [process.id, process.type]), [[-1, 'MAIN'], [-2, 'SUBPROCESS']])
    assert.deepEqual(result[0].nodes[0].data?.refs, [-2], 'the reference still points at the Preparation')
  })
})

describe('recipe file: reading', () => {
  it('round-trips through text unchanged', () => {
    const doc = exported()
    const read = parseFlowText(JSON.stringify(doc), 'tomato.recipe.json')

    // Fields that were undefined are simply absent from the text.
    assert.deepEqual(read.processes, JSON.parse(JSON.stringify(doc.processes)))
    assert.deepEqual(read.assets, doc.assets)
    assert.equal(read.name, 'Tomato Masala')
    assert.deepEqual(read.nutrition, { calories: 120, servings: 2 })
  })

  it('never lets image links travel on the steps, and drops links to steps the file does not have', () => {
    const tampered = JSON.parse(JSON.stringify(exported()))
    tampered.processes[0].nodes[0].data.imageUrl = 'https://someone-else/secret.png'
    tampered.assets.images.push({ process: -1, stepId: 'ghost', url: 'https://x/ghost.png' })
    tampered.assets.audio.push({ process: -9, stepId: 'm-1', url: 'https://x/ghost.wav' })

    const read = parseFlowDoc(tampered, 'x.json')

    assert.deepEqual(read.processes[0].nodes[0].data, { title: 'Add oil' })
    assert.equal(read.assets?.images.length, 2)
    assert.equal(read.assets?.audio.length, 2)
  })

  it('reads a bench flow file, which has no recipe details or assets', () => {
    const read = parseFlowDoc({
      version: 1, recipe: 'tomato-masala', recipeText: 'Chop onions.', textModel: 'gemini-flash', warnings: ['w'],
      processes: [{ id: -1, type: 'MAIN', name: 'Tomato Masala', nodes: [{ id: 'n1', kind: 'STEP' }], edges: [] }],
    }, 'x.flow.json')

    assert.equal(read.name, undefined)
    assert.equal(read.assets, undefined)
    assert.equal(read.textModel, 'gemini-flash')
    assert.deepEqual(read.warnings, ['w'])
  })

  it('rejects anything that is not a usable recipe file, saying why', () => {
    const valid = () => JSON.parse(JSON.stringify(exported()))
    assert.throws(() => parseFlowText('not json', 'a.json'), /a\.json: is not a recipe file \(not valid JSON\)/)
    assert.throws(() => parseFlowDoc([], 'a.json'), /is not a recipe file \(not a JSON object\)/)
    assert.throws(() => parseFlowDoc({ ...valid(), version: 2 }, 'a.json'), /has version 2; this app reads version 1/)
    assert.throws(() => parseFlowDoc({ recipeId: 1, processId: 2, nodes: [] }, 'process-2.json'), /older exports held one process only/)
    assert.throws(() => parseFlowDoc({ ...valid(), processes: [] }, 'a'), /has no "processes"/)
    assert.throws(() => parseFlowDoc({ ...valid(), processes: [valid().processes[1]] }, 'a'), /exactly one MAIN/)
    assert.throws(() => parseFlowDoc({ ...valid(), processes: [valid().processes[0], { ...valid().processes[1], id: -1 }] }, 'a'), /same id/)
    assert.throws(() => parseFlowDoc({ ...valid(), processes: [{ id: -1, type: 'MAIN', name: 'x', nodes: [{ kind: 'STEP' }], edges: [] }] }, 'a'), /needs an "id"/)
    assert.throws(() => parseFlowDoc({ ...valid(), assets: [] }, 'a'), /"assets" must be an object/)
    assert.throws(() => parseFlowDoc({ ...valid(), assets: { images: [{ process: -1, stepId: 'm-1', url: 'javascript:alert(1)' }] } }, 'a'),
      /assets\.images\[0\] needs a "process" id, a "stepId" and an http\(s\) "url"/)
    assert.throws(() => parseFlowDoc({ ...valid(), assets: { audio: [{ process: '-1', stepId: 'm-1', url: 'https://x/a.wav' }] } }, 'a'),
      /assets\.audio\[0\]/)
  })
})

// ---------------------------------------------------------------------------------------------------------

/** An in-memory stand-in for the backend's process endpoints of one recipe. */
const fakeBackend = (existing: Process[] = [], options: { failUpdate?: Error } = {}) => {
  let nextId = 500
  let revision = 0
  const processes = existing.map((process) => structuredClone(process))
  const calls: string[] = []
  const api: ImportApi = {
    loadRevision: async () => revision,
    listProcesses: async () => structuredClone(processes),
    createMainProcess: async () => {
      calls.push('createMain')
      const main = processes.find((process) => process.type === 'MAIN')
      if (main) return structuredClone(main)
      const created: Process = { id: nextId++, type: 'MAIN', recipeId: 100, name: 'new recipe', nodes: [], edges: [] }
      processes.push(created)
      return structuredClone(created)
    },
    createProcess: async (request) => {
      calls.push(`create:${request.name}`)
      const created: Process = { id: nextId++, type: request.type, recipeId: 100, name: request.name, nodes: [], edges: [] }
      processes.push(created)
      return structuredClone(created)
    },
    updateAll: async (items: ProcessBatchUpdateItem[], baseRevision: number) => {
      calls.push('updateAll')
      if (options.failUpdate) throw options.failUpdate
      assert.equal(baseRevision, revision, 'saved against the current revision')
      for (const item of items) {
        const target = processes.find((process) => process.id === item.processId)
        assert.ok(target, `process ${item.processId} belongs to the recipe`)
        Object.assign(target, { name: item.name, nodes: item.nodes, edges: item.edges })
      }
      revision += 1
      return { revision }
    },
    deleteProcess: async (id) => {
      calls.push(`delete:${id}`)
    },
  }
  return { api, calls, processes }
}

describe('recipe file: import', () => {
  it('builds the steps into a new recipe, re-pointing references and keeping step ids', async () => {
    const doc = parseFlowText(JSON.stringify(exported()), 'tomato.recipe.json')
    const backend = fakeBackend()

    const { processIds } = await importRecipeFlow(100, doc, backend.api, remapNodes)

    const main = backend.processes.find((process) => process.type === 'MAIN')!
    const preparation = backend.processes.find((process) => process.type === 'SUBPROCESS')!
    assert.deepEqual(backend.calls, ['createMain', 'create:Preparation', 'updateAll'])
    assert.equal(backend.processes.length, 2)
    assert.deepEqual(main.nodes.map((node) => node.id), ['m-1', 'm-2', 'c-1'])
    assert.deepEqual(preparation.nodes.map((node) => node.id), ['prep-1'])
    assert.deepEqual(main.nodes[1].data?.refs, [preparation.id], 'the step uses this recipe\'s own Preparation')
    assert.equal(main.edges.length, 1)
    assert.ok(!JSON.stringify(backend.processes).includes('imageUrl'), 'the steps start without images')
    // The file's ids map to the real ones, so its asset links can be sent for the right processes.
    assert.deepEqual([...processIds], [[-1, main.id], [-2, preparation.id]])
  })

  it('uses the MAIN a new recipe already has instead of making a second one', async () => {
    const doc = parseFlowText(JSON.stringify(exported()), 'tomato.recipe.json')
    const backend = fakeBackend([{ id: 77, type: 'MAIN', recipeId: 100, name: 'new recipe', nodes: [], edges: [] }])

    const { processIds } = await importRecipeFlow(100, doc, backend.api, remapNodes)

    assert.equal(backend.processes.filter((process) => process.type === 'MAIN').length, 1)
    assert.equal(processIds.get(-1), 77)
    assert.equal(backend.processes.find((process) => process.id === 77)!.nodes.length, 3)
    assert.ok(!backend.calls.some((call) => call.startsWith('delete')))
  })

  it('maps the file\'s own ids whatever they are, and fails as a whole when the save is refused', async () => {
    const doc = parseFlowDoc({
      version: 1,
      processes: [
        { id: 9, type: 'SUBPROCESS', name: 'Preparation', nodes: [{ id: 'p', kind: 'STEP' }], edges: [] },
        { id: 4, type: 'MAIN', name: 'Dish', nodes: [{ id: 'm', kind: 'STEP', data: { refs: [9] } }], edges: [] },
      ],
    }, 'hand-made.json')
    const backend = fakeBackend()

    const { processIds } = await importRecipeFlow(100, doc, backend.api, remapNodes)

    const main = backend.processes.find((process) => process.type === 'MAIN')!
    const preparation = backend.processes.find((process) => process.type === 'SUBPROCESS')!
    assert.deepEqual([...processIds].sort(), [[4, main.id], [9, preparation.id]])
    assert.deepEqual(main.nodes[0].data?.refs, [preparation.id])

    const refusing = fakeBackend([], { failUpdate: new Error('Validation failed: duplicate node id') })
    await assert.rejects(importRecipeFlow(100, doc, refusing.api, remapNodes), /duplicate node id/)
  })
})
