import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { ERROR_TEXT, errorMessage, friendlyJobError, rateLimitedText } from '../src/shared/errors.ts'

/** Stands in for ApiError (the client module can't load under Node). */
const failedRequest = (message: string, requestId?: unknown, status = 500, extra: Record<string, unknown> = {}) =>
  Object.assign(new Error(message), { status, requestId, ...extra })

/** Stands in for NetworkError: no status, a NETWORK or TIMEOUT code. */
const noResponse = (code: 'NETWORK' | 'TIMEOUT') => Object.assign(new Error('raw'), { code })

describe('errorMessage', () => {
  it('appends the first 8 characters of the request id as a Ref', () => {
    const error = failedRequest('Recipe name is required', '3f2a9c1d-77b0-4e21-9a3c-0123456789ab', 400)
    assert.equal(errorMessage(error, 'Unable to save'), 'Recipe name is required (Ref: 3f2a9c1d)')
  })

  it('adds the Ref to the fallback when a validation error has no message', () => {
    assert.equal(errorMessage(failedRequest('', 'abcdef1234', 400), 'Unable to save'), 'Unable to save (Ref: abcdef12)')
  })

  it('leaves the message unchanged without a usable request id', () => {
    assert.equal(errorMessage(new Error('Request timed out'), 'Unable to load'), 'Request timed out')
    assert.equal(errorMessage(failedRequest('Too long', undefined, 422), 'x'), 'Too long')
    assert.equal(errorMessage(failedRequest('Too long', '', 422), 'x'), 'Too long')
    assert.equal(errorMessage(failedRequest('Too long', 42, 422), 'x'), 'Too long')
  })

  it('uses the fallback for anything that is not an Error', () => {
    assert.equal(errorMessage('boom', 'Something went wrong'), 'Something went wrong')
    assert.equal(errorMessage(undefined, 'Something went wrong'), 'Something went wrong')
    assert.equal(errorMessage(null, 'Something went wrong'), 'Something went wrong')
  })

  it('never shows raw server text or status codes for server failures', () => {
    assert.equal(errorMessage(failedRequest('NullPointerException at Foo.java:12'), 'x'), ERROR_TEXT.server)
    assert.equal(errorMessage(failedRequest('', undefined, 503), 'x'), ERROR_TEXT.server)
    assert.equal(errorMessage(failedRequest('', 'abcdef1234', 502), 'x'), `${ERROR_TEXT.server} (Ref: abcdef12)`)
  })

  it('maps each status to plain language', () => {
    assert.equal(errorMessage(failedRequest('Unauthorized', undefined, 401), 'x'), ERROR_TEXT.sessionExpired)
    assert.equal(errorMessage(failedRequest('Forbidden', undefined, 403), 'x'), ERROR_TEXT.forbidden)
    assert.equal(errorMessage(failedRequest('recipe 42 missing', undefined, 404), 'x'), ERROR_TEXT.notFound)
    assert.equal(errorMessage(failedRequest('revision 3 != 4', undefined, 409), 'x'), ERROR_TEXT.conflict)
    assert.equal(errorMessage(failedRequest('', undefined, 413), 'x'), ERROR_TEXT.tooLarge)
    assert.equal(errorMessage(failedRequest('', undefined, 418), 'Unable to save'), 'Unable to save')
  })

  it('says how long to wait when rate limited', () => {
    const limited = failedRequest('raw', undefined, 429, { code: 'RATE_LIMITED', retryAfterSeconds: 20 })
    assert.equal(errorMessage(limited, 'x'), "You're going a bit fast. Please try again in 20 seconds.")
    assert.equal(errorMessage(failedRequest('', undefined, 429), 'x'), rateLimitedText())
    assert.equal(rateLimitedText(1), "You're going a bit fast. Please try again in 1 second.")
    assert.equal(rateLimitedText(90), "You're going a bit fast. Please try again in 2 minutes.")
  })

  it('uses the backend code before the status', () => {
    assert.equal(errorMessage(failedRequest('', undefined, 429, { code: 'AI_BUSY' }), 'x'), ERROR_TEXT.aiBusy)
    assert.equal(errorMessage(failedRequest('You already have several AI tasks running.', undefined, 429, { code: 'AI_BUSY' }), 'x'),
      'You already have several AI tasks running.')
    assert.equal(errorMessage(failedRequest('Gemini said 500', undefined, 500, { code: 'AI_UNAVAILABLE' }), 'x'), ERROR_TEXT.aiUnavailable)
    assert.equal(errorMessage(failedRequest('raw', undefined, 413, { code: 'PAYLOAD_TOO_LARGE' }), 'x'), ERROR_TEXT.tooLarge)
    // An unverified account says so, instead of the generic 403 "no access" / 409 "changed elsewhere" text.
    assert.equal(errorMessage(failedRequest('raw', undefined, 403, { code: 'EMAIL_NOT_VERIFIED' }), 'x'), ERROR_TEXT.emailNotVerified)
    assert.equal(errorMessage(failedRequest('raw', undefined, 409, { code: 'EMAIL_NOT_VERIFIED' }), 'x'), ERROR_TEXT.emailNotVerified)
  })

  it('explains requests that never reached the server', () => {
    assert.equal(errorMessage(noResponse('NETWORK'), 'x'), ERROR_TEXT.network)
    assert.equal(errorMessage(noResponse('TIMEOUT'), 'x'), ERROR_TEXT.timeout)
    assert.equal(errorMessage(new TypeError('Failed to fetch'), 'x'), ERROR_TEXT.network)
  })
})

describe('friendlyJobError', () => {
  it('keeps short plain-language reasons', () => {
    assert.equal(friendlyJobError('This recipe has too many steps for a storyboard.', 'x'),
      'This recipe has too many steps for a storyboard.')
  })

  it('falls back when there is no reason', () => {
    assert.equal(friendlyJobError(null, 'Unable to visualize'), 'Unable to visualize')
    assert.equal(friendlyJobError('   ', 'Unable to visualize'), 'Unable to visualize')
    assert.equal(friendlyJobError(undefined), undefined)
  })

  it('replaces technical text', () => {
    assert.equal(friendlyJobError('AiCommunicationException: HTTP 500 {"error":"x"}', 'x'), ERROR_TEXT.job)
    assert.equal(friendlyJobError('Upload to https://x.supabase.co failed', 'x'), ERROR_TEXT.job)
    assert.equal(friendlyJobError('y'.repeat(400), 'x'), ERROR_TEXT.job)
  })

  it('names timeouts and throttling', () => {
    assert.equal(friendlyJobError('Request timed out after 120 s', 'x'), ERROR_TEXT.jobTimeout)
    assert.equal(friendlyJobError('Gemini TTS rate limit or quota exceeded (429)', 'x'), ERROR_TEXT.aiBusy)
  })
})
