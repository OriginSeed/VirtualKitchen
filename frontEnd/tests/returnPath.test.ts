import { describe, it } from 'node:test'
import assert from 'node:assert/strict'
import { authPathFor, isSafeReturnPath, returnPathOr } from '../src/app/session/returnPath.ts'

describe('isSafeReturnPath', () => {
  it('accepts kitchen paths with queries and hashes', () => {
    assert.equal(isSafeReturnPath('/kitchen'), true)
    assert.equal(isSafeReturnPath('/kitchen/recipes/12/tool/process'), true)
    assert.equal(isSafeReturnPath('/kitchen/shop?cart=open'), true)
    assert.equal(isSafeReturnPath('/kitchen#top'), true)
  })

  it('rejects anything outside the signed-in app', () => {
    for (const path of [null, undefined, '', '/', '/auth', '/kitchenware', 'kitchen', '//evil.example/kitchen',
      'https://evil.example/kitchen', '/kitchen//evil.example', '/kitchen/\\evil']) {
      assert.equal(isSafeReturnPath(path), false, String(path))
    }
  })
})

describe('returnPathOr', () => {
  it('falls back to My Kitchen', () => {
    assert.equal(returnPathOr('https://evil.example'), '/kitchen')
    assert.equal(returnPathOr(null), '/kitchen')
    assert.equal(returnPathOr('/kitchen/orders'), '/kitchen/orders')
  })
})

describe('authPathFor', () => {
  it('carries a deep link through sign-in', () => {
    assert.equal(authPathFor('/kitchen/recipes/12/tool'), '/auth?from=%2Fkitchen%2Frecipes%2F12%2Ftool')
  })

  it('uses plain /auth when there is nothing to return to', () => {
    assert.equal(authPathFor('/kitchen'), '/auth')
    assert.equal(authPathFor('/elsewhere'), '/auth')
  })
})
