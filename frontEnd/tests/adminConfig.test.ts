import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import type { ConfigField } from '../src/features/admin/config/api/adminConfigApi.ts'
import {
  changedValues,
  displayValue,
  groupFields,
  isDirty,
  optionLabel,
} from '../src/features/admin/config/model/configForm.ts'
import { creditLabel, modelOptions, runModelOptions } from '../src/shared/format/modelOptions.ts'
import type { ModelDefinition } from '../src/api/aiApi.ts'

function field(overrides: Partial<ConfigField>): ConfigField {
  return {
    key: 'threads',
    label: 'Threads',
    group: 'Pools',
    type: 'SELECT',
    options: ['1', '4', '8'],
    baseline: '4',
    current: '4',
    overridden: false,
    help: null,
    ...overrides,
  }
}

describe('admin config form', () => {
  it('groups fields under their headings in backend order', () => {
    const groups = groupFields([
      field({ key: 'a', group: 'X' }),
      field({ key: 'b', group: 'Y' }),
      field({ key: 'c', group: 'X' }),
    ])
    assert.deepEqual(groups.map(g => [g.name, g.fields.map(f => f.key)]), [['X', ['a', 'c']], ['Y', ['b']]])
  })

  it('sends only values that differ from the live ones', () => {
    const fields = [field({ key: 'a', current: '4' }), field({ key: 'b', current: '8' })]
    assert.deepEqual(changedValues(fields, { a: '4', b: '1' }), { b: '1' })
    assert.equal(isDirty(fields, { a: '4' }), false)
    assert.equal(isDirty(fields, { a: '8' }), true)
  })

  it('never sends a blank secret and never shows a secret', () => {
    const secret = field({ key: 'k', type: 'SECRET', options: [], baseline: '••••1234', current: '••••1234' })
    assert.equal(displayValue(secret, {}), '')
    assert.deepEqual(changedValues([secret], { k: '   ' }), {})
    assert.deepEqual(changedValues([secret], { k: ' new-key ' }), { k: 'new-key' })
  })

  it('sends trimmed text only when it changed and is not blank', () => {
    const text = field({ key: 'from', type: 'TEXT', options: [], baseline: 'a@x', current: 'a@x' })
    assert.deepEqual(changedValues([text], { from: '' }), {})
    assert.deepEqual(changedValues([text], { from: ' a@x ' }), {})
    assert.deepEqual(changedValues([text], { from: 'b@x' }), { from: 'b@x' })
  })

  it('labels the baseline option and an empty option', () => {
    const f = field({ options: ['', '4'], baseline: '4' })
    assert.equal(optionLabel(f, '4'), '4 (default)')
    assert.equal(optionLabel(f, ''), 'None')
  })
})

function model(overrides: Partial<ModelDefinition>): ModelDefinition {
  return {
    key: 'm',
    capability: 'TEXT_TO_TEXT',
    tier: 'PAID',
    providerModelId: 'p',
    creditCost: 1,
    enabled: true,
    displayName: 'Model',
    userSelectable: true,
    defaultModel: false,
    ...overrides,
  }
}

describe('model picker options', () => {
  it('prices paid models in credits and open-source ones as free', () => {
    assert.equal(creditLabel(model({ creditCost: 1 })), '⚡ 1 credit')
    assert.equal(creditLabel(model({ creditCost: 3 })), '⚡ 3 credits')
    assert.equal(creditLabel(model({ tier: 'OPEN_SOURCE', creditCost: 2 })), 'free')
  })

  it('run options start with the account setting, named after what the user runs on now', () => {
    const models = [model({ key: 'a', displayName: 'Alpha' }), model({ key: 'b', displayName: 'Beta', creditCost: 4 })]
    assert.equal(runModelOptions(models, 'b')[0].label, 'Account setting (Beta · ⚡ 4 credits)')
    assert.equal(runModelOptions(models, 'not-offered')[0].label, 'Account setting')
    assert.deepEqual(runModelOptions(models, 'a').map(o => o.value), ['', 'a', 'b'])
  })

  it('starts with a Default option naming the default model', () => {
    const options = modelOptions([
      model({ key: 'a', displayName: 'Alpha', defaultModel: true }),
      model({ key: 'b', displayName: 'Beta', creditCost: 4 }),
    ])
    assert.deepEqual(options.map(o => [o.value, o.label]), [
      ['', 'Default (Alpha · ⚡ 1 credit)'],
      ['a', 'Alpha · ⚡ 1 credit'],
      ['b', 'Beta · ⚡ 4 credits'],
    ])
  })
})
