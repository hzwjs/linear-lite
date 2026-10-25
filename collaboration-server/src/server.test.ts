import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import type { AddressInfo } from 'node:net'
import { describe, it } from 'node:test'
import * as Y from 'yjs'
import { CollaborationNode } from './server.js'
import { roomName, type CollaborationConfig } from './types.js'

const config: CollaborationConfig = {
  host: '127.0.0.1',
  port: 0,
  javaBaseUrl: 'http://127.0.0.1:8080',
  internalKey: 'test-internal-key',
  blocknoteMaxBytes: 10 * 1024 * 1024,
  htmlMaxBytes: 5 * 1024 * 1024,
  requestTimeoutMs: 500,
  persistenceDebounceMs: 10,
}

describe('save acknowledgement', () => {
  it('broadcasts the persisted session counter instead of Yjs client clocks', async () => {
    const node = new CollaborationNode(config)
    const document = new Y.Doc() as Y.Doc & { broadcastStateless(payload: string): void }
    const sessionId = 'session-737-edit'
    document.getText('html').insert(0, '<p>edited</p>')
    document.getMap<number>('session-counters').set(sessionId, 2)
    let broadcast = ''
    let storedCounters: Record<string, number> = {}
    document.broadcastStateless = (payload) => { broadcast = payload }
    const runtime = {
      documentId: 737,
      epoch: 'epoch-737',
      roomName: 'doc:737:epoch:epoch-737',
      contentFormat: 'HTML' as const,
      commitSeq: 1,
      version: 1,
      stateBase64: '',
      lastEditorId: 7,
    }
    const internal = node as unknown as {
      persistDocument(
        document: Y.Doc,
        roomRuntime: typeof runtime,
      ): Promise<{ commitSeq: number; version: number }>
      java: { storeState(input: { counters: Record<string, number> }): Promise<{ commitSeq: number; version: number }> }
    }
    let enteredStore!: () => void
    const storeStarted = new Promise<void>((resolve) => { enteredStore = resolve })
    let finishStore!: (value: { commitSeq: number; version: number }) => void
    const storeResult = new Promise<{ commitSeq: number; version: number }>((resolve) => { finishStore = resolve })
    internal.java.storeState = async (input) => {
      storedCounters = input.counters
      enteredStore()
      return storeResult
    }

    const saving = internal.persistDocument(document, runtime)
    await storeStarted
    document.getMap<number>('session-counters').set(sessionId, 3)
    finishStore({ commitSeq: 2, version: 2 })
    await saving

    assert.deepEqual(storedCounters, { [sessionId]: 2 })
    assert.deepEqual(JSON.parse(broadcast).counters, { [sessionId]: 2 })
    document.destroy()
  })

  it('replays committed counters when a client reconnects', async () => {
    const node = new CollaborationNode(config)
    const sessionId = 'session-737-edit'
    const room = 'doc:737:epoch:epoch-737'
    const snapshot = new Y.Doc()
    snapshot.getMap<number>('session-counters').set(sessionId, 2)
    const stateBase64 = Buffer.from(Y.encodeStateAsUpdate(snapshot)).toString('base64')
    snapshot.destroy()
    const internal = node as unknown as {
      getOrCreateRuntime(state: {
        documentId: number; epoch: string; roomName: string; contentFormat: 'HTML'; commitSeq: number; version: number
      }, stateBase64: string): { validationState: Y.Doc }
    }
    const runtime = internal.getOrCreateRuntime({
      documentId: 737, epoch: 'epoch-737', roomName: room, contentFormat: 'HTML', commitSeq: 17, version: 33,
    }, stateBase64)
    let broadcast = ''

    await node.httpServer.configuration.connected?.({
      documentName: room,
      connection: { sendStateless: (payload: string) => { broadcast = payload } },
    } as never)

    assert.deepEqual(JSON.parse(broadcast), {
      event: 'saved', epoch: 'epoch-737', counters: { [sessionId]: 2 }, version: 33, commitSeq: 17,
    })
    runtime.validationState.destroy()
  })
})

describe('document title metadata', () => {
  it('advances the collaboration runtime version and ignores stale metadata', async () => {
    const node = new CollaborationNode({ ...config, port: 0 })
    await node.listen()
    const documentId = 738
    const epoch = 'epoch-738'
    const room = roomName(documentId, epoch)
    const validationState = new Y.Doc()
    const runtime = {
      epoch,
      version: 1,
      lastEditorId: 7,
      validationState,
    }
    const internal = node as unknown as {
      runtime: Map<string, typeof runtime>
    }
    internal.runtime.set(room, runtime)

    try {
      const address = node.httpServer.httpServer.address() as AddressInfo
      const baseUrl = `http://127.0.0.1:${address.port}/internal/collaboration/documents/${documentId}/metadata`
      const postMetadata = (version: number, editorId: number) => fetch(baseUrl, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Collaboration-Internal-Key': config.internalKey,
        },
        body: JSON.stringify({ epoch, version, title: `Title ${version}`, editorId }),
      })

      assert.equal((await postMetadata(2, 8)).status, 204)
      assert.equal(runtime.version, 2)
      assert.equal(runtime.lastEditorId, 8)

      assert.equal((await postMetadata(1, 9)).status, 204)
      assert.equal(runtime.version, 2)
      assert.equal(runtime.lastEditorId, 8)
    } finally {
      await node.destroy()
    }
  })
})

describe('collaboration transition fence', () => {
  it('accepts delete close-room transitions and rejects unsupported operations', () => {
    const node = new CollaborationNode(config)
    const internal = node as unknown as {
      validateCloseRoomInput(input: {
        epoch: string
        nextEpoch: string
        operation: 'archive' | 'unarchive' | 'delete'
        transitionId: string
        deadlineEpochMs: number
      }): void
    }
    const closeInput = {
      epoch: 'old',
      nextEpoch: 'deleted',
      operation: 'delete' as const,
      transitionId: 'delete-transition',
      deadlineEpochMs: Date.now() + 1000,
    }

    assert.doesNotThrow(() => internal.validateCloseRoomInput(closeInput))
    assert.throws(
      () => internal.validateCloseRoomInput({ ...closeInput, operation: 'restore' as 'delete' }),
      /CLOSE_ROOM_FIELDS_INVALID/,
    )
  })

  it('reconciles a completed prior transition before closing the room for a new delete', async () => {
    const node = new CollaborationNode(config)
    const documentId = 46
    const internal = node as unknown as {
      closeRoom(
        documentId: number,
        input: { epoch: string; nextEpoch: string; operation: 'delete'; transitionId: string; deadlineEpochMs: number },
        instance: {
          documents: Map<string, Y.Doc>
          closeConnections(name: string): void
          unloadDocument(document: Y.Doc): Promise<void>
        },
      ): Promise<{ epoch: string; commitSeq: number; version: number; transitionId: string }>
      transitionFences: Map<number, { transitionId: string; documentId: number; epoch: string; nextEpoch: string; operation: 'archive' | 'unarchive' | 'delete'; deadlineEpochMs: number; inFlight: number }>
      java: {
        getTransition(transitionId: string): Promise<{ transitionId: string; status: 'FINALIZED'; documents: Array<{ documentId: number; epoch: string }> }>
        loadState(documentId: number, epoch: string, transitionId?: string): Promise<{ stateBase64: string; commitSeq: number; version: number; contentFormat: 'HTML' }>
      }
    }
    internal.transitionFences.set(documentId, {
      transitionId: 'prior-transition', documentId, epoch: 'old', nextEpoch: 'current',
      operation: 'archive', deadlineEpochMs: Date.now() - 1000, inFlight: 0,
    })
    internal.java.getTransition = async (transitionId) => ({
      transitionId,
      status: 'FINALIZED',
      documents: [{ documentId, epoch: 'current' }],
    })
    const durableDocument = new Y.Doc()
    const durableStateBase64 = Buffer.from(Y.encodeStateAsUpdate(durableDocument)).toString('base64')
    durableDocument.destroy()
    internal.java.loadState = async () => ({
      stateBase64: durableStateBase64,
      commitSeq: 5,
      version: 8,
      contentFormat: 'HTML',
    })
    const result = await internal.closeRoom(documentId, {
      epoch: 'current',
      nextEpoch: 'deleted',
      operation: 'delete',
      transitionId: 'delete-transition',
      deadlineEpochMs: Date.now() + 1000,
    }, {
      documents: new Map(),
      closeConnections: () => undefined,
      unloadDocument: async () => undefined,
    })

    assert.equal(result.commitSeq, 5)
    assert.equal(result.version, 8)
    assert.equal(result.transitionId, 'delete-transition')
    assert.equal(internal.transitionFences.get(documentId)?.transitionId, 'delete-transition')
    internal.transitionFences.delete(documentId)
  })

  it('serves POST /internal/collaboration/encode over HTTP and leaves the server available', async () => {
    const node = new CollaborationNode({ ...config, port: 0 })
    await node.listen()
    try {
      const address = node.httpServer.httpServer.address() as AddressInfo
      const baseUrl = `http://127.0.0.1:${address.port}`
      const response = await fetch(`${baseUrl}/internal/collaboration/encode`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'X-Collaboration-Internal-Key': config.internalKey,
        },
        body: JSON.stringify({ contentFormat: 'HTML', content: '<p>direct HTTP</p>' }),
      })
      assert.equal(response.status, 200)
      const encoded = await response.json() as { content: string; stateSha256: string }
      assert.equal(encoded.content, '<p>direct HTTP</p>')
      assert.match(encoded.stateSha256, /^[a-f0-9]{64}$/)

      const health = await fetch(baseUrl)
      assert.equal(health.status, 200)
      assert.equal(await health.text(), 'Welcome to Hocuspocus!')
    } finally {
      await node.destroy()
    }
  })

  it('rejects room work and drains frames admitted before the fence', async () => {
    const node = new CollaborationNode(config)
    const internal = node as unknown as {
      enterInFlight(documentId: number): void
      leaveInFlight(documentId: number): void
      waitForInFlight(documentId: number, fence: { inFlight: number }): Promise<void>
      assertRoomNotFenced(room: string): void
      releaseTransition(input: { transitionId: string; status: 'FINALIZED' | 'ABORTED'; documents: Array<{ documentId: number; epoch: string }> }): Promise<void>
      transitionFences: Map<number, unknown>
      runtime: Map<string, { validationState: { destroy(): void } }>
    }
    const documentId = 47
    const room = `doc:${documentId}:epoch:old`

    internal.enterInFlight(documentId)
    const fence = {
      transitionId: 'transition-1',
      documentId,
      epoch: 'old',
      nextEpoch: 'new',
      operation: 'archive' as const,
      deadlineEpochMs: Date.now() + 1000,
      inFlight: 1,
      drainWaiters: [],
    }
    internal.transitionFences.set(documentId, fence)
    assert.throws(() => internal.assertRoomNotFenced(room), /ROOM_TRANSITION_ACTIVE/)

    let drained = false
    const drain = internal.waitForInFlight(documentId, fence).then(() => { drained = true })
    await Promise.resolve()
    assert.equal(drained, false)
    internal.leaveInFlight(documentId)
    await drain
    assert.equal(drained, true)
    assert.equal(fence.inFlight, 0)
    internal.transitionFences.delete(documentId)
  })

  it('releases an aborted fence only at its old epoch and finalization removes the old runtime', async () => {
    const node = new CollaborationNode(config)
    const internal = node as unknown as {
      releaseTransition(input: { transitionId: string; status: 'FINALIZED' | 'ABORTED'; documents: Array<{ documentId: number; epoch: string }> }): Promise<void>
      transitionFences: Map<number, { transitionId: string; documentId: number; epoch: string; nextEpoch: string; operation: 'archive'; deadlineEpochMs: number; inFlight: number }>
      runtime: Map<string, { validationState: { destroy(): void } }>
    }
    const documentId = 48
    const room = `doc:${documentId}:epoch:old`
    const fence = { transitionId: 'transition-2', documentId, epoch: 'old', nextEpoch: 'new', operation: 'archive' as const, deadlineEpochMs: 1000, inFlight: 0 }
    internal.transitionFences.set(documentId, fence)
    let destroyed = 0
    internal.runtime.set(room, { validationState: { destroy: () => { destroyed += 1 } } })

    await assert.rejects(
      internal.releaseTransition({ transitionId: fence.transitionId, status: 'ABORTED', documents: [{ documentId, epoch: 'new' }] }),
      /TRANSITION_RELEASE_EPOCH_MISMATCH/,
    )
    assert.equal(internal.transitionFences.has(documentId), true)

    await internal.releaseTransition({ transitionId: fence.transitionId, status: 'ABORTED', documents: [{ documentId, epoch: 'old' }] })
    assert.equal(internal.transitionFences.has(documentId), false)
    assert.equal(internal.runtime.has(room), true)
    assert.equal(destroyed, 0)

    internal.transitionFences.set(documentId, fence)
    await internal.releaseTransition({ transitionId: fence.transitionId, status: 'FINALIZED', documents: [{ documentId, epoch: 'new' }] })
    assert.equal(internal.transitionFences.has(documentId), false)
    assert.equal(internal.runtime.has(room), false)
    assert.equal(destroyed, 1)
  })

  it('encodes content with a local state hash and materialized readback', () => {
    const node = new CollaborationNode(config)
    const internal = node as unknown as { encodeResponse(input: { contentFormat: 'HTML'; content: string }): { stateBase64: string; stateSha256: string; content: string } }
    const content = '<article>本地转换</article>'
    const encoded = internal.encodeResponse({ contentFormat: 'HTML', content })
    assert.equal(encoded.content, content)
    assert.equal(
      encoded.stateSha256,
      createHash('sha256').update(Buffer.from(encoded.stateBase64, 'base64')).digest('hex'),
    )
  })

  it('retries a dirty old-epoch room after Java aborts its transition', async () => {
    const node = new CollaborationNode(config)
    const documentId = 49
    const room = `doc:${documentId}:epoch:old`
    const document = new Y.Doc() as Y.Doc & { broadcastStateless(payload: string): void }
    document.getText('html').insert(0, '<p>保留的在途编辑</p>')
    document.broadcastStateless = () => undefined
    const persistedBefore = new Y.Doc()
    const stateBase64 = Buffer.from(Y.encodeStateAsUpdate(persistedBefore)).toString('base64')
    persistedBefore.destroy()
    const runtime = {
      documentId,
      epoch: 'old',
      roomName: room,
      contentFormat: 'HTML' as const,
      commitSeq: 1,
      version: 2,
      stateBase64,
      lastEditorId: 7,
      validationState: new Y.Doc(),
    }
    const internals = node as unknown as {
      releaseTransition(input: { transitionId: string; status: 'FINALIZED' | 'ABORTED'; documents: Array<{ documentId: number; epoch: string }> }): Promise<void>
      transitionFences: Map<number, { transitionId: string; documentId: number; epoch: string; nextEpoch: string; operation: 'archive'; deadlineEpochMs: number; inFlight: number }>
      runtime: Map<string, typeof runtime>
      httpServer: { hocuspocus: { documents: Map<string, Y.Doc> } }
      java: { storeState(input: Record<string, unknown>): Promise<{ commitSeq: number; version: number }> }
    }
    internals.runtime.set(room, runtime)
    internals.httpServer.hocuspocus.documents.set(room, document)
    internals.transitionFences.set(documentId, {
      transitionId: 'transition-3', documentId, epoch: 'old', nextEpoch: 'new',
      operation: 'archive', deadlineEpochMs: Date.now() + 1000, inFlight: 0,
    })
    let savedTransitionId: unknown = 'unset'
    internals.java.storeState = async (input) => {
      savedTransitionId = input.transitionId
      return { commitSeq: 2, version: 3 }
    }

    await internals.releaseTransition({
      transitionId: 'transition-3', status: 'ABORTED', documents: [{ documentId, epoch: 'old' }],
    })
    assert.equal(savedTransitionId, undefined)
    assert.equal(runtime.commitSeq, 2)
    assert.equal(runtime.version, 3)
    assert.equal(internals.transitionFences.has(documentId), false)
    runtime.validationState.destroy()
    document.destroy()
  })
})
