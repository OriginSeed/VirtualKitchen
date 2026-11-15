import { test } from 'node:test'
import assert from 'node:assert/strict'
import { parseFlowDoc, stripVisuals } from '../src/flowDoc.ts'

type Node = { id: string; kind: string; data?: Record<string, unknown> }
const step = (id: string, data: Record<string, unknown>): Node => ({ id, kind: 'STEP', data })

test('stripVisuals removes both shapes of a generated image and nothing else', () => {
  const processes = [{
    id: 7, type: 'MAIN', name: 'Tomato Masala', edges: [],
    nodes: [
      step('n1', { title: 'Chop onions', step: { action: 'chop' }, visualization: { assetId: 1, imageUrl: 'a.png', status: 'generated' } }),
      step('n2', { title: 'Add oil', visualizationAssetId: 2, imagePrompt: 'oil in a pan', imageUrl: 'b.png' }),
      step('n3', { title: 'Add onions' }),
      { id: 'c1', kind: 'CONDITION' } as Node,
    ],
  }]

  const stripped = stripVisuals(processes)

  assert.deepEqual(stripped[0].nodes.map((node) => node.data), [
    { title: 'Chop onions', step: { action: 'chop' } },
    { title: 'Add oil' },
    { title: 'Add onions' },
    undefined,
  ])
  assert.equal(stripped[0].nodes[2], processes[0].nodes[2], 'a node without images is not copied')
  assert.equal(processes[0].nodes[0].data?.visualization !== undefined, true, 'the input is left as it was')
  assert.deepEqual(stripped[0].nodes.map((node) => node.id), ['n1', 'n2', 'n3', 'c1'], 'step ids are kept')
})

const valid = () => ({
  version: 1, recipe: 'tomato-masala', recipeText: 'Chop onions.', textModel: 'gemini-flash',
  processes: [
    { id: -1, type: 'MAIN', name: 'Tomato Masala', nodes: [step('n1', { title: 'Chop', imageUrl: 'x.png' })], edges: [] },
    { id: -2, type: 'SUBPROCESS', name: 'Preparation', nodes: [], edges: [] },
  ],
})

test('a flow file is read with its images dropped and missing extras defaulted', () => {
  const flow = parseFlowDoc(valid(), 'x.flow.json')

  assert.equal(flow.textModel, 'gemini-flash')
  assert.deepEqual(flow.warnings, [])
  assert.deepEqual(flow.processes[0].nodes[0].data, { title: 'Chop' })
  assert.equal(parseFlowDoc({ ...valid(), textModel: '' }, 'x').textModel, null)
})

test('an unusable flow file is rejected with its name', () => {
  assert.throws(() => parseFlowDoc([], 'x.flow.json'), /x\.flow\.json: is not a recipe file \(not a JSON object\)/)
  assert.throws(() => parseFlowDoc({ ...valid(), version: 2 }, 'x'), /has version 2; this app reads version 1/)
  assert.throws(() => parseFlowDoc({ ...valid(), processes: [] }, 'x'), /has no "processes"/)
  assert.throws(() => parseFlowDoc({ ...valid(), processes: [valid().processes[1]] }, 'x'), /exactly one MAIN/)
  assert.throws(() => parseFlowDoc({ ...valid(), processes: [{ id: 1, type: 'MAIN' }] }, 'x'), /needs "nodes" and "edges"/)
})
