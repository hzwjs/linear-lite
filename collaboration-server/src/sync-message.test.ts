import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { isDocumentUpdateSyncType } from './sync-message.js'

describe('Hocuspocus beforeSync update types', () => {
  it('validates SyncStep2 and Yjs Update payloads but skips SyncStep1 state vectors', () => {
    assert.equal(isDocumentUpdateSyncType(0), false)
    assert.equal(isDocumentUpdateSyncType(1), true)
    assert.equal(isDocumentUpdateSyncType(2), true)
  })
})
