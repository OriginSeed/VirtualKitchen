import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtempSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { comboGroups, loadConfig, resolveFlowSources } from '../src/config.ts'
import { planSpend, total, describeSpend } from '../src/plan.ts'

const AUTH = { user: { tokenEnv: 'BENCH_TOKEN' } }
const RECIPE = { id: 'tomato-masala', text: 'Chop onions. Add oil. Add onions. Add tomatoes. Add turmeric and stir. Cook until soft.' }

const FLOW = {
  version: 1, recipe: 'tomato-masala', recipeText: 'Chop onions.', textModel: 'gemini-flash', generatedAt: '2026-11-11T00:00:00Z',
  warnings: [], repairs: [],
  processes: [{ id: -1, type: 'MAIN', name: 'Tomato Masala', nodes: [{ id: 'n1', kind: 'STEP', data: { title: 'Chop', imageUrl: 'x' } }], edges: [] }],
}

function load(overrides: Record<string, unknown>, files: Record<string, unknown> = {}) {
  const dir = mkdtempSync(join(tmpdir(), 'ai-bench-'))
  for (const [name, content] of Object.entries(files)) writeFileSync(join(dir, name), JSON.stringify(content))
  const file = join(dir, 'bench.json')
  writeFileSync(file, JSON.stringify({ auth: AUTH, recipes: [RECIPE], ...overrides }))
  return loadConfig(file)
}

const FOUR_MODES = [[
  { name: 'prompt', text: 'gemini-flash', image: 'gemini-image', speech: 'gemini-tts', focusedView: true, anchor: false, reference: false },
  { name: 'anchor', image: 'gemini-image', speech: 'gemini-tts', focusedView: true, anchor: true, reference: false },
  { name: 'reference', image: 'gemini-image', speech: 'gemini-tts', focusedView: true, anchor: false, reference: true },
  { name: 'anchor+reference', image: 'gemini-image', speech: 'gemini-tts', focusedView: true, anchor: true, reference: true },
]]

test('a flat list of combos is unchanged: each is a group of one that shares nothing', () => {
  const config = load({ combos: [
    { name: 'a', text: 'gemini-flash', image: 'gemini-image' },
    { name: 'b', text: 'ollama-qwen', adminConfig: { images: { 'sceneAnchor.enabled': false } } },
  ] })

  assert.deepEqual(config.combos.map((combo) => [combo.name, combo.group, combo.shared, combo.flowText]),
    [['a', 0, false, 'gemini-flash'], ['b', 1, false, 'ollama-qwen']])
  assert.deepEqual(config.combos[1].adminConfig, { images: { 'sceneAnchor.enabled': 'false' } })
  assert.equal(config.combos[0].adminConfig, undefined)
})

test('a combo on its own still needs a text model', () => {
  assert.throws(() => load({ combos: [{ name: 'a', image: 'gemini-image' }] }), /combo "a" needs a "text" model/)
})

test('combos listed together share the flow of the first text model in the group', () => {
  const config = load({ combos: FOUR_MODES })

  assert.equal(comboGroups(config.combos).length, 1)
  for (const combo of config.combos) {
    assert.equal(combo.shared, true)
    assert.equal(combo.flowText, 'gemini-flash')
  }
  assert.deepEqual(config.combos.map((combo) => combo.text), ['gemini-flash', undefined, undefined, undefined])
})

test('the image booleans become admin settings of the images section', () => {
  const config = load({ combos: FOUR_MODES })

  assert.deepEqual(config.combos[0].adminConfig, { images: {
    'sceneAnchor.enabled': 'false', 'referenceChain.enabled': 'false', 'focusedView.enabled': 'true' } })
  assert.deepEqual(config.combos[3].adminConfig, { images: {
    'sceneAnchor.enabled': 'true', 'referenceChain.enabled': 'true', 'focusedView.enabled': 'true' } })
})

test('an omitted image boolean leaves the server setting alone, and they merge with adminConfig', () => {
  const config = load({ combos: [{
    name: 'before', text: 'gemini-flash', image: 'gemini-image', focusedView: false,
    adminConfig: { images: { 'profile.default.width': 768 }, generation: { 'promptBatch.temperature': 0.2 } },
  }] })

  assert.deepEqual(config.combos[0].adminConfig, {
    images: { 'profile.default.width': '768', 'focusedView.enabled': 'false' },
    generation: { 'promptBatch.temperature': '0.2' },
  })
})

test('naming an image setting twice, or a non-boolean, is rejected', () => {
  assert.throws(() => load({ combos: [{ name: 'a', text: 't', anchor: true, adminConfig: { images: { 'sceneAnchor.enabled': false } } }] }),
    /sets images\.sceneAnchor\.enabled twice/)
  assert.throws(() => load({ combos: [{ name: 'a', text: 't', anchor: 'yes' }] }), /"combos\[0\]\.anchor" must be true or false/)
})

test('stepsPerImage and skipLookAlike set the image cost settings for a combo', () => {
  const config = load({ combos: [{ name: 'cheap', text: 't', image: 'gemini-image', stepsPerImage: 4, skipLookAlike: true }] })
  assert.deepEqual(config.combos[0].adminConfig, {
    images: { 'lookAlike.skip': 'true', 'preparation.stepsPerImage': '4' },
  })
  assert.throws(() => load({ combos: [{ name: 'a', text: 't', stepsPerImage: 3 }] }),
    /"combos\[0\]\.stepsPerImage" must be one of 1, 2, 4/)
  assert.throws(() => load({ combos: [{ name: 'a', text: 't', stepsPerImage: 2, adminConfig: { images: { 'preparation.stepsPerImage': 4 } } }] }),
    /sets images\.preparation\.stepsPerImage twice/)
})

test('reference without the focused view is rejected: it would silently not happen', () => {
  assert.throws(() => load({ combos: [{ name: 'a', text: 't', image: 'i', reference: true, focusedView: false }] }),
    /"reference": true with "focusedView": false/)
  // Not naming focusedView leaves it to the server, so it can't be judged here.
  assert.doesNotThrow(() => load({ combos: [{ name: 'a', text: 't', image: 'i', reference: true }] }))
})

test('null models are the same as leaving them out: that stage is not run', () => {
  const config = load({ combos: [[
    { name: 'gen', text: 'gemini-flash', image: null, speech: null },
    { name: 'img', text: null, image: 'gemini-image', speech: null },
  ]] })

  assert.deepEqual(config.combos.map((combo) => [combo.text, combo.image, combo.speech]),
    [['gemini-flash', undefined, undefined], [undefined, 'gemini-image', undefined]])
})

test('a group whose first combo has no text model has no flow to copy', () => {
  assert.throws(() => load({ combos: [[
    { name: 'img', image: 'gemini-image' },
    { name: 'gen', text: 'gemini-flash', image: 'gemini-image' },
  ]] }), /combo "img" has no "text" model and no combo before it in its group generates the flow/)
})

test('filtering away the generating combo is caught when the sources are resolved again', () => {
  const config = load({ combos: FOUR_MODES })
  const left = config.combos.filter((combo) => combo.name === 'anchor')

  assert.throws(() => resolveFlowSources(left, config.recipes), /combo "anchor" has no "text" model/)
})

test('a recipe from a flow file needs no text model anywhere, and its images are dropped', () => {
  const config = load({
    recipes: [{ id: 'saved', flow: 'saved.flow.json' }],
    combos: [{ name: 'img', image: 'gemini-image' }],
  }, { 'saved.flow.json': FLOW })

  assert.equal(config.recipes[0].text, 'Chop onions.')
  assert.equal(config.recipes[0].flow?.textModel, 'gemini-flash')
  assert.deepEqual(config.recipes[0].flow?.processes[0].nodes[0].data, { title: 'Chop' })
  assert.equal(config.combos[0].flowText, undefined)
})

test('a mix of text and flow-file recipes still needs a text model for the text ones', () => {
  assert.throws(() => load({
    recipes: [RECIPE, { id: 'saved', flow: 'saved.flow.json' }],
    combos: [{ name: 'img', image: 'gemini-image' }],
  }, { 'saved.flow.json': FLOW }), /combo "img" needs a "text" model/)
})

test('the four image modes cost one flow and one narration', () => {
  const config = load({ combos: FOUR_MODES })

  const spend = total(planSpend(config.combos, config.recipes, 1, ['text', 'image', 'speech']).values())

  assert.deepEqual(spend, { flowsGenerated: 1, flowsReused: 3, flowsFromFile: 0, imageRuns: 4, narrations: 1, narrationsReused: 3 })
  assert.equal(describeSpend(spend), 'flows: 1 generated, 3 copied · image runs: 4 · narration: 1 narrated, 3 reused')
})

test('repeats inside a group reuse the flow too; combos on their own regenerate every time', () => {
  const grouped = load({ combos: FOUR_MODES })
  const flat = load({ combos: [{ name: 'a', text: 'gemini-flash', image: 'gemini-image', speech: 'gemini-tts' }] })

  assert.deepEqual(total(planSpend(grouped.combos, grouped.recipes, 3, ['text', 'image', 'speech']).values()),
    { flowsGenerated: 1, flowsReused: 11, flowsFromFile: 0, imageRuns: 12, narrations: 1, narrationsReused: 11 })
  assert.deepEqual(total(planSpend(flat.combos, flat.recipes, 3, ['text', 'image', 'speech']).values()),
    { flowsGenerated: 3, flowsReused: 0, flowsFromFile: 0, imageRuns: 3, narrations: 3, narrationsReused: 0 })
})

test('a second text or speech model in a group gets its own flow and narration; --stages limits the rest', () => {
  const config = load({ combos: [[
    { name: 'a', text: 'gemini-flash', image: 'gemini-image', speech: 'gemini-tts' },
    { name: 'b', text: 'deepseek', image: 'gemini-image', speech: 'gemini-tts' },
    { name: 'c', image: 'gemini-image', speech: 'mimo-tts' },
  ]] })

  assert.equal(config.combos[2].flowText, 'gemini-flash')
  assert.deepEqual(total(planSpend(config.combos, config.recipes, 1, ['text', 'image', 'speech']).values()),
    { flowsGenerated: 2, flowsReused: 1, flowsFromFile: 0, imageRuns: 3, narrations: 3, narrationsReused: 0 })
  assert.deepEqual(total(planSpend(config.combos, config.recipes, 1, ['text', 'image']).values()),
    { flowsGenerated: 2, flowsReused: 1, flowsFromFile: 0, imageRuns: 3, narrations: 0, narrationsReused: 0 })
})
