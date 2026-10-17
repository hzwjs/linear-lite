import { createApp, defineComponent, h, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import { YSyncConfig } from 'y-codemirror.next'
import { ySyncPluginKey } from 'y-prosemirror'
import { attachSessionCounterTracking, acknowledgeCounters, useDocumentCollaboration } from './useDocumentCollaboration'

const { mockProviders, MockProvider } = vi.hoisted(() => {
  const mockProviders: Array<{ config: Record<string, any>; destroy: ReturnType<typeof vi.fn> }> = []
  class MockProvider {
    awareness = { setLocalStateField: vi.fn(), getStates: () => new Map() }
    on = vi.fn()
    destroy = vi.fn()
    attach = vi.fn()
    sendToken = vi.fn().mockResolvedValue(undefined)
    setConfiguration = vi.fn((configuration: Record<string, any>) => { this.config = { ...this.config, ...configuration } })
    config: Record<string, any>

    constructor(config: Record<string, any>) {
      this.config = config
      mockProviders.push(this)
    }
  }
  return { mockProviders, MockProvider }
})

vi.mock('@hocuspocus/provider', () => ({ HocuspocusProvider: MockProvider }))
vi.mock('./sharedCollaborationSocket', () => ({
  acquireSharedCollaborationSocket: vi.fn(() => ({ socket: {}, release: vi.fn() })),
}))
vi.mock('../services/api/documentCollaboration', () => ({
  documentCollaborationApi: { createSession: vi.fn(), refreshSession: vi.fn() },
}))

import { documentCollaborationApi } from '../services/api/documentCollaboration'

const makeSession = (epoch: string) => ({
  documentId: 19,
  epoch,
  roomName: `doc:19:epoch:${epoch}`,
  websocketUrl: 'ws://collab.test/collaboration',
  roomToken: `token-${epoch}`,
  user: { id: 7, name: 'Ada', color: '#123456' },
  sessionId: `session-${epoch}`,
  expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString(),
  version: Number(epoch),
  commitSeq: Number(epoch),
  contentFormat: 'BLOCKNOTE' as const,
  stateBase64: btoa(String.fromCharCode(...Y.encodeStateAsUpdate(new Y.Doc()))),
})

let app: App | null = null
let state: ReturnType<typeof useDocumentCollaboration> | null = null

beforeEach(() => {
  vi.clearAllMocks()
  mockProviders.splice(0)
  app = null
  state = null
})

afterEach(() => {
  app?.unmount()
  app = null
  vi.useRealTimers()
})

describe('document collaboration transaction tracking', () => {
  it('does not mark a selection-only editor transaction as an edit', () => {
    const doc = new Y.Doc()
    const tracked: Array<[string, number]> = []
    const updates: Uint8Array[] = []
    doc.on('update', update => updates.push(update))
    const detach = attachSessionCounterTracking(doc, () => ({ sessionId: 'session-1' }), () => true,
      (id, count) => tracked.push([id, count]))

    doc.transact(() => {}, ySyncPluginKey)

    expect(tracked).toEqual([])
    expect(updates).toHaveLength(0)
    expect(doc.getMap('session-counters').size).toBe(0)
    detach()
    doc.destroy()
  })

  it('tracks local body changes without counting setup changes', () => {
    const doc = new Y.Doc()
    let session: { sessionId: string } | null = null
    const tracked: Array<[string, number]> = []
    const updates: Uint8Array[] = []
    doc.on('update', update => updates.push(update))
    const detach = attachSessionCounterTracking(doc, () => session, () => true, (id, count) => tracked.push([id, count]))

    const fragment = doc.getXmlFragment('document-store')
    doc.transact(() => fragment.insert(0, [new Y.XmlElement('paragraph')]), ySyncPluginKey)
    expect(updates).toHaveLength(1)
    expect(doc.getMap('session-counters').size).toBe(0)

    session = { sessionId: 'session-1' }
    doc.transact(() => (fragment.get(0) as Y.XmlElement).setAttribute('id', 'edited'), ySyncPluginKey)
    const syncOrigin = new YSyncConfig(doc.getText('html'), {} as any)
    doc.transact(() => doc.getText('html').insert(0, '<p>HTML</p>'), syncOrigin)

    expect(updates).toHaveLength(5)
    expect((fragment.get(0) as Y.XmlElement).getAttribute('id')).toBe('edited')
    expect(doc.getText('html').toString()).toBe('<p>HTML</p>')
    expect(doc.getMap<number>('session-counters').get('session-1')).toBe(2)
    expect(tracked).toEqual([['session-1', 1], ['session-1', 2]])
    detach()
    doc.destroy()
  })

  it('clears pending edits only through the saved counters acknowledgement', () => {
    const pending = new Map([['session-a', 3], ['session-b', 4]])
    expect(acknowledgeCounters(pending, { 'session-a': 2, 'session-b': 4 })).toEqual(new Map([['session-a', 3]]))
    expect(pending).toEqual(new Map([['session-a', 3], ['session-b', 4]]))
  })
})

describe('document collaboration epoch lifecycle', () => {
  it('shows an isolated checkpoint before live room sync', async () => {
    const checkpoint = new Y.Doc()
    checkpoint.getXmlFragment('document-store').insert(0, [new Y.XmlElement('paragraph')])
    const initial = {
      ...makeSession('1'),
      stateBase64: btoa(String.fromCharCode(...Y.encodeStateAsUpdate(checkpoint))),
    }
    checkpoint.destroy()
    app = createApp(defineComponent({
      setup() {
        state = useDocumentCollaboration(19, 'BLOCKNOTE')
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    await state!.start(Promise.resolve(initial))

    const preview = state!.previewDoc.value!
    expect(preview).not.toBe(state!.ydoc.value)
    expect(preview.getXmlFragment('document-store').length).toBe(1)
    expect(state!.ydoc.value.getXmlFragment('document-store').length).toBe(1)
    expect(state!.hasSynced.value).toBe(false)
    expect(documentCollaborationApi.createSession).not.toHaveBeenCalled()
    expect(mockProviders[0]?.attach).toHaveBeenCalledOnce()

    preview.getXmlFragment('document-store').insert(1, [new Y.XmlElement('paragraph')])
    expect(state!.ydoc.value.getXmlFragment('document-store').length).toBe(1)
    mockProviders[0]!.config.onSynced()
    state!.retirePreview()
    expect(preview.isDestroyed).toBe(true)
    expect(state!.hasSynced.value).toBe(true)
    mockProviders[0]!.config.onDisconnect()
    expect(state!.hasSynced.value).toBe(true)
    mockProviders[0]!.config.onAuthenticationFailed()
    expect(state!.authFailed.value).toBe(true)
  })

  it('discards the old Y.Doc and requests a new session after epochChanged', async () => {
    vi.mocked(documentCollaborationApi.createSession)
      .mockResolvedValueOnce(makeSession('1'))
      .mockResolvedValueOnce(makeSession('2'))
    app = createApp(defineComponent({
      setup() {
        state = useDocumentCollaboration(19, 'BLOCKNOTE')
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    await state!.start()

    const oldDoc = state!.ydoc.value
    const oldProvider = mockProviders[0]!
    oldProvider.config.onStateless({ payload: JSON.stringify({ event: 'epochChanged', epoch: '2' }) })
    expect(state!.epochChanged.value).toBe(true)

    await state!.restartForEpoch()

    expect(oldProvider.destroy).toHaveBeenCalledOnce()
    expect(oldDoc.isDestroyed).toBe(true)
    expect(state!.ydoc.value).not.toBe(oldDoc)
    expect(documentCollaborationApi.createSession).toHaveBeenCalledTimes(2)
    expect(mockProviders).toHaveLength(2)
    expect(mockProviders[1]?.config.name).toBe('doc:19:epoch:2')
    expect(state!.epochChanged.value).toBe(false)
  })

  it('shows saved state only after a session counter acknowledgement', async () => {
    vi.mocked(documentCollaborationApi.createSession).mockResolvedValue(makeSession('1'))
    app = createApp(defineComponent({
      setup() {
        state = useDocumentCollaboration(19, 'BLOCKNOTE')
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    await state!.start()
    const provider = mockProviders[0]!
    provider.config.onSynced()
    state!.ydoc.value.transact(() => state!.ydoc.value.getXmlFragment('document-store')
      .insert(0, [new Y.XmlElement('paragraph')]), ySyncPluginKey)

    expect(state!.saveState.value).toBe('saving')
    provider.config.onStateless({ payload: JSON.stringify({
      event: 'saved', epoch: '1', counters: { 'session-1': 1 }, version: 5, commitSeq: 9,
    }) })
    expect(state!.saveState.value).toBe('saved')
    expect(state!.serverVersion.value).toBe(5)
    expect(state!.commitSeq.value).toBe(9)
  })
})

describe('document collaboration session renewal', () => {
  it('refreshes the same session identity and sends the renewed token to the provider', async () => {
    vi.useFakeTimers()
    const initial = { ...makeSession('1'), expiresAt: new Date(Date.now() + 61_000).toISOString() }
    const renewed = { sessionId: initial.sessionId, epoch: initial.epoch, roomToken: 'renewed-token',
      expiresAt: new Date(Date.now() + 60 * 60 * 1000).toISOString() }
    vi.mocked(documentCollaborationApi.createSession).mockResolvedValue(initial)
    vi.mocked(documentCollaborationApi.refreshSession).mockResolvedValue(renewed)
    app = createApp(defineComponent({
      setup() {
        state = useDocumentCollaboration(19, 'BLOCKNOTE')
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    await state!.start()

    const provider = mockProviders[0]!
    await vi.advanceTimersByTimeAsync(1_000)

    expect(documentCollaborationApi.refreshSession).toHaveBeenCalledWith(19, initial.sessionId)
    expect(renewed.sessionId).toBe(initial.sessionId)
    expect(provider.setConfiguration).toHaveBeenCalledWith({ token: expect.any(Function) })
    expect(provider.sendToken).toHaveBeenCalledOnce()
    await expect(provider.config.token()).resolves.toBe(renewed.roomToken)
    expect(mockProviders).toHaveLength(1)
  })

  it('ignores a renewal response from the previous room after an epoch restart', async () => {
    vi.useFakeTimers()
    const first = { ...makeSession('1'), expiresAt: new Date(Date.now() + 61_000).toISOString() }
    const second = makeSession('2')
    vi.mocked(documentCollaborationApi.createSession)
      .mockResolvedValueOnce(first)
      .mockResolvedValueOnce(second)
    let resolveRenewal!: (value: { sessionId: string; epoch: string; roomToken: string; expiresAt: string }) => void
    vi.mocked(documentCollaborationApi.refreshSession).mockImplementationOnce(() => new Promise((resolve) => {
      resolveRenewal = resolve
    }))
    app = createApp(defineComponent({
      setup() {
        state = useDocumentCollaboration(19, 'BLOCKNOTE')
        return () => h('div')
      },
    }))
    app.mount(document.createElement('div'))
    await state!.start()

    await vi.advanceTimersByTimeAsync(1_000)
    expect(documentCollaborationApi.refreshSession).toHaveBeenCalledWith(19, first.sessionId)
    mockProviders[0]!.config.onStateless({ payload: JSON.stringify({ event: 'epochChanged', epoch: '2' }) })
    await state!.restartForEpoch()

    resolveRenewal({ sessionId: first.sessionId, epoch: first.epoch, roomToken: 'stale-token', expiresAt: first.expiresAt })
    await Promise.resolve()
    await Promise.resolve()

    expect(state!.session.value?.sessionId).toBe(second.sessionId)
    expect(state!.session.value?.roomToken).toBe(second.roomToken)
    expect(mockProviders[0]!.sendToken).not.toHaveBeenCalled()
    expect(mockProviders[1]!.sendToken).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(1)
  })
})
