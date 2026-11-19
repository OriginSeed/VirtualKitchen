import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  groupImageByItemKey,
  groupItemsByStoryboard,
  segmentItems,
  type GroupableItem,
  type StoryboardPanelRef,
} from '../src/features/recipe-tool/view/model/storyboardGroups.ts'

const step = (nodeId: string, imageUrl?: string): GroupableItem => ({ kind: 'step', key: `1:${nodeId}`, nodeId, imageUrl })
const check = (nodeId: string): GroupableItem => ({ kind: 'check', key: `1:${nodeId}` })
const panel = (panelId: string, sourceStepIds: string[], imageUrl: string | null = `https://store/${panelId}.png`): StoryboardPanelRef => ({
  panelId, description: `Panel ${panelId}`, imageUrl, sourceStepIds,
})
const keys = (groups: { itemKeys: string[] }[]) => groups.map((group) => group.itemKeys)

describe('steps shown under one storyboard panel image', () => {
  it('groups a panel\'s steps with its last step, which carries the image', () => {
    const items = [step('heat'), step('add'), step('cook', 'https://store/P1.png'), step('layer'), step('dum', 'https://store/P2.png')]
    const groups = groupItemsByStoryboard(items, [panel('P1', ['heat', 'add', 'cook']), panel('P2', ['layer', 'dum'])])

    assert.deepEqual(keys(groups), [['1:heat', '1:add', '1:cook'], ['1:layer', '1:dum']])
    assert.equal(groups[0].title, 'Panel P1')
    assert.equal(groups[0].imageUrl, 'https://store/P1.png')
    assert.equal(groups[0].key, 'group:1:cook')
  })

  it('a panel of one step is an ordinary step, not a group', () => {
    const items = [step('boil', 'https://store/P1.png'), step('drain', 'https://store/P2.png')]
    assert.deepEqual(groupItemsByStoryboard(items, [panel('P1', ['boil']), panel('P2', ['drain'])]), [])
  })

  it('forms no group once the panel image is no longer on its last step', () => {
    // The step was redrawn on its own, or the storyboard belongs to an older version of the recipe.
    const items = [step('heat'), step('add'), step('cook', 'https://store/own-image.png')]
    assert.deepEqual(groupItemsByStoryboard(items, [panel('P1', ['heat', 'add', 'cook'])]), [])
    assert.deepEqual(groupItemsByStoryboard([step('heat'), step('add')], [panel('P1', ['heat', 'add', 'gone'])]), [])
    assert.deepEqual(groupItemsByStoryboard(items, [panel('P1', ['heat', 'add', 'cook'], null)]), [])
  })

  it('only takes the steps directly before the last one, and stops at a step with its own image or of another panel', () => {
    const own = [step('heat'), step('add', 'https://store/add.png'), step('cook', 'https://store/P1.png')]
    assert.deepEqual(keys(groupItemsByStoryboard(own, [panel('P1', ['heat', 'add', 'cook'])])), [])

    const foreign = [step('heat'), step('other'), step('add'), step('cook', 'https://store/P1.png')]
    assert.deepEqual(keys(groupItemsByStoryboard(foreign, [panel('P1', ['heat', 'add', 'cook'])])), [['1:add', '1:cook']])
  })

  it('a check ends a group in the main process, and stays inside one in a preparation flow', () => {
    const items = [step('cook'), check('done'), step('rest'), step('serve', 'https://store/P1.png')]
    const panels = [panel('P1', ['cook', 'rest', 'serve'])]

    assert.deepEqual(keys(groupItemsByStoryboard(items, panels)), [['1:rest', '1:serve']])
    assert.deepEqual(keys(groupItemsByStoryboard(items, panels, { includeChecks: true })), [['1:cook', '1:done', '1:rest', '1:serve']])
    // A check with no panel step before it is not pulled in.
    const leading = [check('ready'), step('rest'), step('serve', 'https://store/P1.png')]
    assert.deepEqual(keys(groupItemsByStoryboard(leading, panels, { includeChecks: true })), [['1:rest', '1:serve']])
  })

  it('a step belongs to one group only, and groups come in page order', () => {
    const items = [step('a'), step('b', 'https://store/P2.png'), step('c'), step('d', 'https://store/P1.png')]
    const groups = groupItemsByStoryboard(items, [panel('P1', ['a', 'c', 'd']), panel('P2', ['a', 'b'])])
    assert.deepEqual(keys(groups), [['1:a', '1:b'], ['1:c', '1:d']])
  })

  it('cuts the page into segments: each group once, everything else on its own', () => {
    const items = [step('wash'), step('heat'), step('cook', 'https://store/P1.png'), check('done'), step('serve')]
    const groups = groupItemsByStoryboard(items, [panel('P1', ['heat', 'cook'])])
    const segments = segmentItems(items, groups)

    assert.deepEqual(segments.map((segment) => segment.items.map((item) => item.key)),
      [['1:wash'], ['1:heat', '1:cook'], ['1:done'], ['1:serve']])
    assert.equal(segments[1].group?.title, 'Panel P1')
    assert.equal(segments[0].group, undefined)
    assert.deepEqual(segmentItems(items, []).map((segment) => segment.items.length), [1, 1, 1, 1, 1])
  })

  it('gives every step of a group the group image, for one-step-at-a-time views', () => {
    const items = [step('heat'), step('cook', 'https://store/P1.png'), step('serve')]
    const images = groupImageByItemKey(groupItemsByStoryboard(items, [panel('P1', ['heat', 'cook'])]))
    assert.equal(images.get('1:heat'), 'https://store/P1.png')
    assert.equal(images.get('1:cook'), 'https://store/P1.png')
    assert.equal(images.get('1:serve'), undefined)
  })
})
