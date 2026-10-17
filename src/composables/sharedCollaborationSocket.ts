import { HocuspocusProviderWebsocket } from '@hocuspocus/provider'
import { AUTH_SESSION_CHANGED_EVENT, JWT_STORAGE_KEY, USER_STORAGE_KEY } from '../services/api/constants'

const IDLE_CLOSE_MS = 15_000

type SharedSocket = {
  key: string
  socket: HocuspocusProviderWebsocket
  leases: Set<symbol>
  idleTimer: ReturnType<typeof setTimeout> | null
}

let shared: SharedSocket | null = null

export function destroySharedCollaborationSocket() {
  if (shared == null) return
  if (shared.idleTimer != null) clearTimeout(shared.idleTimer)
  shared.socket.destroy()
  shared = null
}

if (typeof window !== 'undefined') {
  window.addEventListener(AUTH_SESSION_CHANGED_EVENT, destroySharedCollaborationSocket)
  window.addEventListener('storage', (event) => {
    if (event.key === JWT_STORAGE_KEY || event.key === USER_STORAGE_KEY) destroySharedCollaborationSocket()
  })
}

export function acquireSharedCollaborationSocket(websocketUrl: string, userId: number) {
  const key = `${userId}:${websocketUrl}`
  if (shared != null && shared.key !== key) destroySharedCollaborationSocket()
  if (shared == null) {
    shared = {
      key,
      socket: new HocuspocusProviderWebsocket({ url: websocketUrl }),
      leases: new Set(),
      idleTimer: null,
    }
  }
  const current = shared
  if (current.idleTimer != null) clearTimeout(current.idleTimer)
  current.idleTimer = null
  const lease = Symbol('collaboration room')
  current.leases.add(lease)
  let released = false
  return {
    socket: current.socket,
    release() {
      if (released) return
      released = true
      current.leases.delete(lease)
      if (current.leases.size > 0 || shared !== current) return
      // Keep the transport through a document switch; close it after the idle window.
      current.idleTimer = setTimeout(() => {
        if (shared === current && current.leases.size === 0) destroySharedCollaborationSocket()
      }, IDLE_CLOSE_MS)
    },
  }
}
