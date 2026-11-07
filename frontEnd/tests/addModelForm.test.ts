import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import {
  EMPTY_MODEL_FORM,
  formProblem,
  modelKeyOfField,
  priceFields,
  suggestDisplayName,
  suggestKey,
  toRequest,
  type ModelForm,
} from '../src/features/admin/config/models/addModelForm.ts'

const filled: ModelForm = {
  ...EMPTY_MODEL_FORM,
  key: 'deepinfra-qwen3-235b',
  capability: 'TEXT_TO_TEXT',
  providerBean: 'deepInfraAiClient',
  providerModelId: 'Qwen/Qwen3-235B',
  displayName: 'Qwen3 235B',
  creditCost: '3',
  inputUsdPerMillion: '0.2',
  outputUsdPerMillion: ' 0.6 ',
  perImageUsd: '0.05',
}

describe('add model form', () => {
  it('suggests a key and a name from the provider and model id', () => {
    assert.equal(suggestKey('deepInfraAiClient', 'Qwen/Qwen3-235B-A22B'), 'deepinfra-qwen3-235b-a22b')
    assert.equal(suggestKey('geminiImageClient', 'gemini-3.1-flash-image'), 'gemini-gemini-3-1-flash-image')
    assert.equal(suggestKey('ollamaAiClient', 'qwen3:4b'), 'ollama-qwen3-4b')
    assert.equal(suggestDisplayName('Qwen/Qwen3-235B-A22B'), 'Qwen3 235B A22B')
  })

  it('explains what is missing, in form order', () => {
    assert.match(formProblem(EMPTY_MODEL_FORM, [], false) ?? '', /what the model does/)
    assert.match(formProblem({ ...filled, key: 'Bad Key' }, [], false) ?? '', /lowercase/)
    assert.match(formProblem(filled, ['deepinfra-qwen3-235b'], false) ?? '', /already exists/)
    assert.match(formProblem({ ...filled, inputUsdPerMillion: 'abc' }, [], false) ?? '', /numbers/)
    assert.equal(formProblem(filled, [], false), null)
  })

  it('does not check the key or provider when editing', () => {
    assert.equal(formProblem({ ...filled, key: 'Bad Key', providerBean: '' }, [], true), null)
  })

  it('sends only the prices that apply to the capability', () => {
    assert.deepEqual(priceFields('TEXT_TO_TEXT'), ['tokens'])
    assert.deepEqual(priceFields('TEXT_TO_IMAGE'), ['image'])
    assert.deepEqual(priceFields('TEXT_TO_SPEECH'), [])

    const request = toRequest(filled)
    assert.equal(request.creditCost, 3)
    assert.equal(request.inputUsdPerMillion, 0.2)
    assert.equal(request.outputUsdPerMillion, 0.6)
    assert.equal(request.cachedInputUsdPerMillion, null)
    assert.equal(request.perImageUsd, null, 'a text model has no per-image price')
    assert.equal(request.fallbackModelKey, null)
  })

  it('makes open-source models free', () => {
    assert.equal(toRequest({ ...filled, tier: 'OPEN_SOURCE', creditCost: '5' }).creditCost, 0)
  })

  it('finds the model key of a Models & Credits field', () => {
    assert.equal(modelKeyOfField('model.deepinfra-qwen3.enabled'), 'deepinfra-qwen3')
    assert.equal(modelKeyOfField('default.TEXT_TO_TEXT'), null)
    assert.equal(modelKeyOfField('credits.monthlyAllocation'), null)
  })
})
