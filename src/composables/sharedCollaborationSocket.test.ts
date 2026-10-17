import { afterEach, describe, expect, it, vi } from 'vitest'

const { sockets, MockSocket } = vi.hoisted(() => {
  const sockets: Array<{ config: { url: string }; destroy: ReturnType<typeof vi.fn> }> = []
  class MockSocket {
    destroy = vi.fn()
    constructor(public config: { url: string }) { sockets.push(this) }
  }
  return { sockets, MockSocket }
})

vi.mock('@hocuspocus/provider', () => ({ HocuspocusProviderWebsocket: MockSocket }))

import { acquireSharedCollaborationSocket, destroySharedCollaborationSocket } from './sharedCollaborationSocket'

afterEach(() => {
  destroySharedCollaborationSocket()
  sockets.splice(0)
  vi.useRealTimers()
})

describe('shared collaboration transport', () => {
  it('reuses one socket across document switches and closes after its idle window', async () => {
    vi.useFakeTimers()
    const first = acquireSharedCollaborationSocket('wss://docs.test/collaboration', 7)
    first.release()
    const second = acquireSharedCollaborationSocket('wss://docs.test/collaboration', 7)
    expect(second.socket).toBe(first.socket)
    expect(sockets).toHaveLength(1)
    await vi.advanceTimersByTimeAsync(15_000)
    expect(sockets[0]?.destroy).not.toHaveBeenCalled()
    second.release()
    await vi.advanceTimersByTimeAsync(15_000)
    expect(sockets[0]?.destroy).toHaveBeenCalledOnce()
  })

  it('closes the old transport when the authenticated identity changes', () => {
    const first = acquireSharedCollaborationSocket('wss://docs.test/collaboration', 7)
    const second = acquireSharedCollaborationSocket('wss://docs.test/collaboration', 8)
    expect(second.socket).not.toBe(first.socket)
    expect(sockets[0]?.destroy).toHaveBeenCalledOnce()
    first.release()
    second.release()
  })
})
