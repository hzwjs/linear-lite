import { computed, onBeforeUnmount, readonly, ref, shallowReadonly, shallowRef } from 'vue'
import * as Y from 'yjs'
import { HocuspocusProvider } from '@hocuspocus/provider'
import { YSyncConfig } from 'y-codemirror.next'
import { ySyncPluginKey } from 'y-prosemirror'
import { documentCollaborationApi, type DocumentCollaborationRenewal, type DocumentCollaborationSession } from '../services/api/documentCollaboration'
import { acquireSharedCollaborationSocket } from './sharedCollaborationSocket'

export type CollaborationConnectionState = 'connecting' | 'connected' | 'offline' | 'error'
export type CollaborationSaveState = 'saved' | 'saving' | 'offline'

interface SavedEvent {
  event: 'saved'
  epoch: string
  counters: Record<string, number>
  version: number
  commitSeq: number
}

interface EpochChangedEvent {
  event: 'epochChanged'
  epoch: string
}

type CollaborationEvent = SavedEvent | EpochChangedEvent

export function isDocumentBodyTransaction(transaction: Y.Transaction): boolean {
  return transaction.origin === ySyncPluginKey || transaction.origin instanceof YSyncConfig
}

export function acknowledgeCounters(
  pendingCounters: ReadonlyMap<string, number>,
  acknowledgedCounters: Record<string, number>,
): Map<string, number> {
  const pending = new Map(pendingCounters)
  for (const [sessionId, count] of pending) {
    if ((acknowledgedCounters[sessionId] ?? 0) >= count) pending.delete(sessionId)
  }
  return pending
}

export function attachSessionCounterTracking(
  doc: Y.Doc,
  getSession: () => Pick<DocumentCollaborationSession, 'sessionId'> | null,
  canTrack: () => boolean,
  onCounter: (sessionId: string, counter: number) => void,
): () => void {
  const afterTransaction = (transaction: Y.Transaction) => {
    const current = getSession()
    if (!transaction.local || !isDocumentBodyTransaction(transaction) || current == null || !canTrack()) return
    // y-prosemirror opens transactions for selection updates. Count only transactions that changed the body.
    const body = doc.getXmlFragment('document-store')
    const html = doc.getText('html')
    if (![...transaction.changedParentTypes.keys()].some(type => Object.is(type, body) || Object.is(type, html))) return
    const counters = doc.getMap<number>('session-counters')
    const next = (counters.get(current.sessionId) ?? 0) + 1
    counters.set(current.sessionId, next)
    onCounter(current.sessionId, next)
  }
  doc.on('afterTransaction', afterTransaction)
  return () => doc.off('afterTransaction', afterTransaction)
}

export function isEpochChanged(currentEpoch: string, eventEpoch: string): boolean {
  return currentEpoch !== eventEpoch
}

function parseEvent(payload: string): CollaborationEvent | null {
  try {
    const event = JSON.parse(payload) as Partial<CollaborationEvent>
    if (event.event === 'saved' && typeof event.epoch === 'string' &&
      event.counters != null && typeof event.counters === 'object') return event as SavedEvent
    if (event.event === 'epochChanged' && typeof event.epoch === 'string') return event as EpochChangedEvent
  } catch {
    // Ignore stateless messages owned by other collaboration features.
  }
  return null
}

function decodeState(stateBase64: string): Uint8Array {
  const binary = atob(stateBase64)
  return Uint8Array.from(binary, (character) => character.charCodeAt(0))
}

export function useDocumentCollaboration(documentId: number, contentFormat: 'BLOCKNOTE' | 'HTML') {
  const ydoc = shallowRef(new Y.Doc())
  const previewDoc = shallowRef<Y.Doc | null>(null)
  const session = shallowRef<DocumentCollaborationSession | null>(null)
  const provider = shallowRef<HocuspocusProvider | null>(null)
  const connectionState = ref<CollaborationConnectionState>('connecting')
  const hasSynced = ref(false)
  const authFailed = ref(false)
  const savedCounter = ref(0)
  const localCounter = ref(0)
  const serverVersion = ref(0)
  const commitSeq = ref(0)
  const pendingCounters = ref(new Map<string, number>())
  const epochChanged = ref(false)
  const collaborators = ref<Array<{ id: number; name: string; color: string }>>([])
  const saveState = computed<CollaborationSaveState>(() => {
    if (connectionState.value === 'offline' || connectionState.value === 'error') return 'offline'
    return pendingCounters.value.size > 0 ? 'saving' : 'saved'
  })
  let disposed = false
  let renewTimer: ReturnType<typeof setTimeout> | null = null
  let sessionPromise: Promise<DocumentCollaborationSession> | null = null
  let refreshPromise: { sessionId: string; promise: Promise<DocumentCollaborationRenewal> } | null = null
  let releaseSocket: (() => void) | null = null
  let generation = 0

  function trackLocalEdits(doc: Y.Doc) {
    attachSessionCounterTracking(doc, () => session.value, () => !epochChanged.value && doc === ydoc.value, (sessionId, next) => {
      localCounter.value = next
      const pending = new Map(pendingCounters.value)
      pending.set(sessionId, next)
      pendingCounters.value = pending
    })
  }
  trackLocalEdits(ydoc.value)

  function setAwareness(providerValue: HocuspocusProvider, current: DocumentCollaborationSession) {
    providerValue.awareness?.setLocalStateField('user', current.user)
    const updateCollaborators = () => {
      const seen = new Map<number, { id: number; name: string; color: string }>()
      for (const state of providerValue.awareness?.getStates().values() ?? []) {
        const user = state.user as { id?: unknown; name?: unknown; color?: unknown } | undefined
        if (typeof user?.id === 'number' && typeof user.name === 'string' && typeof user.color === 'string') {
          seen.set(user.id, { id: user.id, name: user.name, color: user.color })
        }
      }
      collaborators.value = [...seen.values()]
    }
    providerValue.on('awarenessUpdate', updateCollaborators)
    updateCollaborators()
  }

  async function getSession() {
    if (sessionPromise != null) return sessionPromise
    sessionPromise = documentCollaborationApi.createSession(documentId).finally(() => { sessionPromise = null })
    return sessionPromise
  }

  async function refreshSession(sessionId: string) {
    if (refreshPromise?.sessionId === sessionId) return refreshPromise.promise
    const promise = documentCollaborationApi.refreshSession(documentId, sessionId).then((renewed) => {
      if (renewed.sessionId !== sessionId) throw new Error('Collaboration refresh changed the session identity')
      return renewed
    }).finally(() => {
      if (refreshPromise?.promise === promise) refreshPromise = null
    })
    refreshPromise = { sessionId, promise }
    return promise
  }

  function scheduleTokenRenewal(
    current: DocumentCollaborationSession,
    activeProvider: HocuspocusProvider,
    resolveToken: () => Promise<string>,
  ) {
    if (renewTimer != null) clearTimeout(renewTimer)
    const untilRenewal = Math.max(Date.parse(current.expiresAt) - Date.now() - 60_000, 1000)
    const isCurrent = () => !disposed && !epochChanged.value && provider.value === activeProvider &&
      session.value?.sessionId === current.sessionId && session.value.epoch === current.epoch
    renewTimer = setTimeout(async () => {
      if (!isCurrent()) return
      try {
        const renewed = await refreshSession(current.sessionId)
        if (!isCurrent()) return
        if (renewed.epoch !== current.epoch) {
          epochChanged.value = true
          connectionState.value = 'offline'
          return
        }
        session.value = { ...current, roomToken: renewed.roomToken, expiresAt: renewed.expiresAt }
        activeProvider.setConfiguration({ token: resolveToken })
        await activeProvider.sendToken()
        if (!isCurrent()) return
        scheduleTokenRenewal(session.value, activeProvider, resolveToken)
      } catch {
        if (isCurrent()) connectionState.value = 'offline'
      }
    }, untilRenewal)
  }

  async function createProvider(current: DocumentCollaborationSession) {
    if (disposed || epochChanged.value) return
    session.value = current
    serverVersion.value = current.version
    let activeProvider: HocuspocusProvider
    const isCurrent = () => !disposed && !epochChanged.value && provider.value === activeProvider &&
      session.value?.sessionId === current.sessionId && session.value.epoch === current.epoch
    const resolveToken = async () => {
      const activeSession = session.value ?? current
      if (Date.parse(activeSession.expiresAt) > Date.now() + 60_000) return activeSession.roomToken
      const renewed = await refreshSession(activeSession.sessionId)
      if (!isCurrent()) throw new Error('Collaboration room changed during token renewal')
      if (renewed.epoch !== activeSession.epoch) {
        epochChanged.value = true
        connectionState.value = 'offline'
        return renewed.roomToken
      }
      session.value = { ...activeSession, roomToken: renewed.roomToken, expiresAt: renewed.expiresAt }
      return renewed.roomToken
    }
    const lease = acquireSharedCollaborationSocket(current.websocketUrl, current.user.id)
    releaseSocket = lease.release
    activeProvider = new HocuspocusProvider({
      websocketProvider: lease.socket,
      name: current.roomName,
      document: ydoc.value,
      token: resolveToken,
      onStatus: ({ status }) => {
        if (!isCurrent()) return
        if (status !== 'connected') connectionState.value = 'connecting'
      },
      onSynced: () => {
        if (isCurrent()) {
          connectionState.value = 'connected'
          authFailed.value = false
          hasSynced.value = true
        }
      },
      onDisconnect: () => {
        if (isCurrent()) connectionState.value = 'offline'
      },
      onStateless: ({ payload }) => {
        if (!isCurrent()) return
        const event = parseEvent(payload)
        if (event == null) return
        if (event.event === 'epochChanged') {
          if (event.epoch === current.epoch) return
          epochChanged.value = true
          connectionState.value = 'offline'
          return
        }
        if (event.epoch !== current.epoch) return
        const acknowledged = event.counters[current.sessionId] ?? 0
        savedCounter.value = Math.max(savedCounter.value, acknowledged)
        pendingCounters.value = acknowledgeCounters(pendingCounters.value, event.counters)
        serverVersion.value = event.version
        commitSeq.value = event.commitSeq
      },
      onAuthenticationFailed: () => {
        if (!isCurrent()) return
        connectionState.value = 'error'
        authFailed.value = true
      },
      onOpen: () => {
        if (!isCurrent()) return
        connectionState.value = 'connecting'
      },
      onClose: () => {
        if (isCurrent()) connectionState.value = 'offline'
      }
    })
    provider.value = activeProvider
    activeProvider.attach()
    setAwareness(activeProvider, current)
    scheduleTokenRenewal(current, activeProvider, resolveToken)
  }

  async function start(initialSession?: Promise<DocumentCollaborationSession>) {
    const currentGeneration = ++generation
    try {
      const current = await (initialSession ?? getSession())
      if (disposed || currentGeneration !== generation) return
      if (current.documentId !== documentId || current.contentFormat !== contentFormat) {
        throw new Error('Collaboration checkpoint does not match the document')
      }
      const state = decodeState(current.stateBase64)
      const preview = new Y.Doc()
      Y.applyUpdate(preview, state)
      Y.applyUpdate(ydoc.value, state)
      previewDoc.value?.destroy()
      previewDoc.value = preview
      commitSeq.value = current.commitSeq
      await createProvider(current)
    } catch {
      if (currentGeneration === generation && !disposed) {
        provider.value?.destroy()
        provider.value = null
        releaseSocket?.()
        releaseSocket = null
        connectionState.value = 'error'
      }
    }
  }

  function retirePreview() {
    previewDoc.value?.destroy()
    previewDoc.value = null
  }

  async function restartForEpoch() {
    if (disposed) return
    generation += 1
    provider.value?.destroy()
    provider.value = null
    releaseSocket?.()
    releaseSocket = null
    retirePreview()
    ydoc.value.destroy()
    ydoc.value = new Y.Doc()
    trackLocalEdits(ydoc.value)
    session.value = null
    localCounter.value = 0
    savedCounter.value = 0
    pendingCounters.value = new Map()
    hasSynced.value = false
    authFailed.value = false
    epochChanged.value = false
    connectionState.value = 'connecting'
    if (renewTimer != null) clearTimeout(renewTimer)
    renewTimer = null
    await start()
  }

  function dispose() {
    disposed = true
    generation += 1
    if (renewTimer != null) clearTimeout(renewTimer)
    renewTimer = null
    provider.value?.destroy()
    provider.value = null
    releaseSocket?.()
    releaseSocket = null
    retirePreview()
    ydoc.value.destroy()
  }

  onBeforeUnmount(dispose)
  return {
    ydoc: shallowReadonly(ydoc),
    previewDoc: shallowReadonly(previewDoc),
    session: readonly(session),
    provider: shallowReadonly(provider),
    connectionState: readonly(connectionState),
    hasSynced: readonly(hasSynced),
    authFailed: readonly(authFailed),
    saveState,
    serverVersion: readonly(serverVersion),
    commitSeq: readonly(commitSeq),
    epochChanged: readonly(epochChanged),
    collaborators: readonly(collaborators),
    start,
    retirePreview,
    restartForEpoch,
    dispose
  }
}
