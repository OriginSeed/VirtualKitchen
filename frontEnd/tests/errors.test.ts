import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { errorMessage } from '../src/shared/errors.ts'

/** Stands in for ApiError (the client module can't load under Node). */
const failedRequest = (message: string, requestId?: unknown) =>
  Object.assign(new Error(message), { status: 500, requestId })

describe('errorMessage', () => {
  it('appends the first 8 characters of the request id as a Ref', () => {
    const error = failedRequest('Recipe not found', '3f2a9c1d-77b0-4e21-9a3c-0123456789ab')
    assert.equal(errorMessage(error, 'Unable to load'), 'Recipe not found (Ref: 3f2a9c1d)')
  })

  it('adds the Ref to the fallback when the error has no message', () => {
    assert.equal(errorMessage(failedRequest('', 'abcdef1234'), 'Unable to save'), 'Unable to save (Ref: abcdef12)')
  })

  it('leaves the message unchanged without a usable request id', () => {
    assert.equal(errorMessage(new Error('Request timed out'), 'Unable to load'), 'Request timed out')
    assert.equal(errorMessage(failedRequest('Conflict'), 'x'), 'Conflict')
    assert.equal(errorMessage(failedRequest('Conflict', ''), 'x'), 'Conflict')
    assert.equal(errorMessage(failedRequest('Conflict', 42), 'x'), 'Conflict')
  })

  it('uses the fallback for anything that is not an Error', () => {
    assert.equal(errorMessage('boom', 'Something went wrong'), 'Something went wrong')
    assert.equal(errorMessage(undefined, 'Something went wrong'), 'Something went wrong')
    assert.equal(errorMessage(null, 'Something went wrong'), 'Something went wrong')
  })
})
