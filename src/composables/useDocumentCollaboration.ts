import { computed, onBeforeUnmount, readonly, ref, shallowReadonly, shallowRef, watch } from 'vue'
import { isAxiosError } from 'axios'
import * as Y from 'yjs'
import { HocuspocusProvider } from '@hocuspocus/provider'
import { YSyncConfig } from 'y-codemirror.next'
import { ySyncPluginKey } from 'y-prosemirror'
import { documentCollaborationApi, type DocumentCollaborationRenewal, type DocumentCollaborationSession } from '../services/api/documentCollaboration'
import { acquireSharedCollaborationSocket, resetSharedCollaborationSocket } from './sharedCollaborationSocket'
import { useAuthStore } from '../store/authStore'
import {
  clearDocumentDraft,
  createDocumentDraftId,
  decodeBase64ToUint8Array,
  documentDraftId,
  encodeUint8ArrayToBase64,
  listDocumentDrafts,
  readDocumentDraft,
  saveDocumentDraft,
  type DocumentLocalDraft,
} from '../utils/documentDraftStorage'

export type CollaborationConnectionState = 'connecting' | 'connected' | 'offline' | 'error'
export type CollaborationSaveState = 'connecting' | 'saved' | 'saving' | 'offline'

export interface DocumentCollaborationOptions {
  /** 可选纯内容投影获取器（BlockNote 编辑器 JSON），用于支持跨 epoch 草稿恢复。 */
  getContentSnapshot?: () => string | undefined
  /** 草稿列表因自动清理或源草稿落盘删除而变化时触发。 */
  onDraftsChanged?: () => void
}

export interface RestoreDraftOptions {
  sourceDraftId?: string
  /** 经用户确认覆盖当前未保存修改，或跨 epoch 应用纯内容快照。 */
  force?: boolean
  /** BlockNote 跨 epoch 应用内容快照回调。 */
  applyCrossEpochContent?: (snapshot: string) => boolean
}

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

export function useDocumentCollaboration(
  documentId: number,
  contentFormat: 'BLOCKNOTE' | 'HTML',
  options: DocumentCollaborationOptions = {},
) {
  const auth = useAuthStore()
  const ownerId = auth.currentUser?.id ?? null
  const hasOwner = () => ownerId != null && auth.getConsistentAccessToken() != null && auth.currentUser?.id === ownerId
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
  const draftRestored = ref(false)
  const isLocallyCached = ref(false)
  const draftCacheFailed = ref(false)
  const hasUnsavedBody = computed(() => pendingCounters.value.size > 0 || draftRestored.value)
  const saveState = computed<CollaborationSaveState>(() => {
    if (connectionState.value === 'offline' || connectionState.value === 'error') return 'offline'
    if (connectionState.value === 'connecting' || !hasSynced.value) return 'connecting'
    return pendingCounters.value.size > 0 ? 'saving' : 'saved'
  })
  let disposed = false
  let renewTimer: ReturnType<typeof setTimeout> | null = null
  let draftSaveTimer: ReturnType<typeof setTimeout> | null = null
  let sessionPromise: Promise<DocumentCollaborationSession> | null = null
  let refreshPromise: { sessionId: string; promise: Promise<DocumentCollaborationRenewal> } | null = null
  let releaseSocket: (() => void) | null = null
  let generation = 0
  const restoredSourceDraftIds = new Set<string>()

  function captureContentSnapshot(): string | undefined {
    try {
      if (contentFormat === 'HTML') return ydoc.value.getText('html').toString()
      return options.getContentSnapshot?.()
    } catch {
      return undefined
    }
  }

  function extractHtmlFromDraft(draft: DocumentLocalDraft): string | null {
    if (typeof draft.contentSnapshot === 'string') return draft.contentSnapshot
    if (!draft.ydocUpdate) return null
    const doc = new Y.Doc()
    try {
      Y.applyUpdate(doc, decodeBase64ToUint8Array(draft.ydocUpdate))
      return doc.getText('html').toString()
    } catch {
      return null
    } finally {
      doc.destroy()
    }
  }

  function readDocBodyFingerprint(doc: Y.Doc): string {
    return contentFormat === 'HTML'
      ? doc.getText('html').toString()
      : doc.getXmlFragment('document-store').toJSON()
  }

  /** 同步成功后，自动清理同 epoch 且已与当前服务端内容完全一致的冗余草稿，彻底避免孤儿草稿堆积。 */
  function pruneRedundantDrafts() {
    const current = session.value
    if (disposed || !hasOwner() || ownerId == null || current == null || !hasSynced.value || pendingCounters.value.size > 0) return
    const candidates = listDocumentDrafts(ownerId, documentId)
    if (candidates.length === 0) return
    const currentState = Y.encodeStateAsUpdate(ydoc.value)
    const currentFingerprint = readDocBodyFingerprint(ydoc.value)
    let changed = false
    for (const entry of candidates) {
      if (entry.draftId === documentDraftId) continue
      if (entry.draft.format !== contentFormat || entry.draft.titleDirty) continue
      if (!entry.draft.ydocUpdate) {
        clearDocumentDraft(ownerId, documentId, entry.draftId)
        changed = true
        continue
      }
      if (entry.draft.ydocEpoch !== current.epoch) continue
      const probe = new Y.Doc()
      try {
        Y.applyUpdate(probe, currentState)
        Y.applyUpdate(probe, decodeBase64ToUint8Array(entry.draft.ydocUpdate))
        if (readDocBodyFingerprint(probe) === currentFingerprint) {
          clearDocumentDraft(ownerId, documentId, entry.draftId)
          changed = true
        }
      } catch {
        // 忽略损坏草稿，留待用户手动删除。
      } finally {
        probe.destroy()
      }
    }
    if (changed) options.onDraftsChanged?.()
  }

  function clearRestoredSourceDrafts(serverVer: number) {
    if (!hasOwner() || ownerId == null || restoredSourceDraftIds.size === 0) return
    let changed = false
    for (const sourceId of restoredSourceDraftIds) {
      if (sourceId === documentDraftId) continue
      const source = readDocumentDraft(ownerId, documentId, sourceId)
      if (!source) continue
      if (!source.titleDirty) {
        clearDocumentDraft(ownerId, documentId, sourceId)
      } else {
        saveDocumentDraft(ownerId, sourceId, {
          ...source,
          ydocUpdate: undefined,
          ydocEpoch: undefined,
          contentSnapshot: undefined,
          serverVersion: serverVer,
          savedAt: Date.now(),
        })
      }
      changed = true
    }
    restoredSourceDraftIds.clear()
    if (changed) options.onDraftsChanged?.()
  }

  function flushLocalDraft(forceWrite = false) {
    if (!forceWrite && draftSaveTimer == null) return
    if (draftSaveTimer != null) {
      clearTimeout(draftSaveTimer)
      draftSaveTimer = null
    }
    if (disposed || !hasOwner() || ownerId == null) return
    try {
      const existing = readDocumentDraft(ownerId, documentId, documentDraftId)
      const epoch = session.value?.epoch ?? existing?.ydocEpoch
      if (!epoch) return
      const updateBase64 = encodeUint8ArrayToBase64(Y.encodeStateAsUpdate(ydoc.value))
      const contentSnapshot = captureContentSnapshot()
      const cached = saveDocumentDraft(ownerId, documentDraftId, {
        documentId,
        format: contentFormat,
        title: existing?.title,
        titleDirty: existing?.titleDirty === true,
        ydocUpdate: updateBase64,
        ydocEpoch: epoch,
        contentSnapshot,
        serverVersion: serverVersion.value,
        savedAt: Date.now(),
      })
      isLocallyCached.value = cached
      draftCacheFailed.value = !cached
    } catch {
      draftCacheFailed.value = true
    }
  }

  function scheduleLocalDraftSave() {
    if (draftSaveTimer != null) clearTimeout(draftSaveTimer)
    isLocallyCached.value = false
    draftSaveTimer = setTimeout(flushLocalDraft, 400)
  }

  function trackLocalEdits(doc: Y.Doc) {
    attachSessionCounterTracking(doc, () => session.value, () => !epochChanged.value && doc === ydoc.value, (sessionId, next) => {
      localCounter.value = next
      const pending = new Map(pendingCounters.value)
      pending.set(sessionId, next)
      pendingCounters.value = pending
      scheduleLocalDraftSave()
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
    retryAttempt = 0,
    delayOverrideMs?: number,
  ) {
    if (renewTimer != null) clearTimeout(renewTimer)
    const untilRenewal = delayOverrideMs ?? Math.max(Date.parse(current.expiresAt) - Date.now() - 60_000, 1000)
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
        scheduleTokenRenewal(session.value, activeProvider, resolveToken, 0)
      } catch (cause) {
        if (!isCurrent()) return
        const status = isAxiosError(cause) ? cause.response?.status : undefined
        if (status === 401 || status === 403) {
          authFailed.value = true
          connectionState.value = 'error'
          return
        }
        if (status === 404 || status === 409) {
          epochChanged.value = true
          connectionState.value = 'offline'
          return
        }
        // 网络抖动或服务暂不可用：按指数退避持续重试，绝不放弃续期导致 15 分钟后静默死掉。
        connectionState.value = 'offline'
        const backoffMs = Math.min(5_000 * (2 ** retryAttempt), 30_000)
        scheduleTokenRenewal(session.value ?? current, activeProvider, resolveToken, retryAttempt + 1, backoffMs)
      }
    }, untilRenewal)
  }

  async function createProvider(current: DocumentCollaborationSession) {
    if (disposed || epochChanged.value) return
    session.value = current
    serverVersion.value = current.version
    let activeProvider: HocuspocusProvider
    let epochCheckAttempt = 0
    const isCurrent = () => !disposed && !epochChanged.value && provider.value === activeProvider &&
      session.value?.sessionId === current.sessionId && session.value.epoch === current.epoch
    const checkRoomEpoch = () => {
      const attempt = ++epochCheckAttempt
      // 房间可能在连接前被历史版本恢复替换，核对 epoch 后沿用草稿保护流程。
      void refreshSession(current.sessionId).then((renewed) => {
        if (!isCurrent() || attempt !== epochCheckAttempt || connectionState.value === 'connected') return
        if (renewed.epoch !== current.epoch) {
          epochChanged.value = true
          connectionState.value = 'offline'
        }
      }).catch(() => {
        // 保留连接状态，等待网络恢复或用户处理。
      })
    }
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
          epochCheckAttempt += 1
          connectionState.value = 'connected'
          authFailed.value = false
          hasSynced.value = true
          pruneRedundantDrafts()
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
        if (pendingCounters.value.size === 0) {
          if (draftSaveTimer != null) clearTimeout(draftSaveTimer)
          draftSaveTimer = null
          if (!hasOwner() || ownerId == null) return
          const draft = readDocumentDraft(ownerId, documentId, documentDraftId)
          if (draft) {
            if (!draft.titleDirty) {
              clearDocumentDraft(ownerId, documentId, documentDraftId)
              isLocallyCached.value = false
            } else {
              saveDocumentDraft(ownerId, documentDraftId, {
                ...draft,
                ydocUpdate: undefined,
                ydocEpoch: undefined,
                contentSnapshot: undefined,
                serverVersion: event.version,
                savedAt: Date.now(),
              })
            }
            draftRestored.value = false
            draftCacheFailed.value = false
          }
          clearRestoredSourceDrafts(event.version)
          pruneRedundantDrafts()
        }
      },
      onAuthenticationFailed: () => {
        if (!isCurrent()) return
        connectionState.value = 'error'
        authFailed.value = true
        checkRoomEpoch()
      },
      onOpen: () => {
        if (!isCurrent()) return
        connectionState.value = 'connecting'
      },
      onClose: () => {
        if (!isCurrent()) return
        connectionState.value = 'offline'
        checkRoomEpoch()
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
      if (!hasOwner()) throw new Error('Collaboration account is unavailable')
      const current = await (initialSession ?? getSession())
      if (disposed || currentGeneration !== generation || !hasOwner()) return
      if (current.documentId !== documentId || current.contentFormat !== contentFormat || current.user.id !== ownerId) {
        throw new Error('Collaboration checkpoint does not match the document')
      }
      const state = decodeState(current.stateBase64)
      const preview = new Y.Doc()
      Y.applyUpdate(preview, state)
      Y.applyUpdate(ydoc.value, state)

      // Restore local draft update if available
      const draft = ownerId == null ? null : readDocumentDraft(ownerId, documentId, documentDraftId)
      if (draft?.ydocUpdate && draft.format === contentFormat && draft.ydocEpoch === current.epoch) {
        try {
          const draftBytes = decodeBase64ToUint8Array(draft.ydocUpdate)
          Y.applyUpdate(preview, draftBytes)
          Y.applyUpdate(ydoc.value, draftBytes)
          // 恢复内容必须取得本会话的服务端保存确认，才能清除本地正文草稿。
          const counters = ydoc.value.getMap<number>('session-counters')
          const counter = (counters.get(current.sessionId) ?? 0) + 1
          counters.set(current.sessionId, counter)
          localCounter.value = counter
          pendingCounters.value = new Map(pendingCounters.value).set(current.sessionId, counter)
          draftRestored.value = true
          isLocallyCached.value = true
        } catch {
          /* ignore corrupted draft bytes */
        }
      }

      previewDoc.value?.destroy()
      previewDoc.value = preview
      commitSeq.value = current.commitSeq
      await createProvider(current)
    } catch (cause) {
      if (currentGeneration === generation && !disposed) {
        // 只有网络错误允许读取本账号草稿；鉴权和服务端错误保留错误状态。
        const isNetworkError = isAxiosError(cause) && cause.response == null && cause.code !== 'ERR_CANCELED'
        const draft = isNetworkError && hasOwner() && ownerId != null ? readDocumentDraft(ownerId, documentId, documentDraftId) : null
        if (draft?.ydocUpdate && draft.format === contentFormat) {
          try {
            const draftBytes = decodeBase64ToUint8Array(draft.ydocUpdate)
            const preview = new Y.Doc()
            Y.applyUpdate(preview, draftBytes)
            Y.applyUpdate(ydoc.value, draftBytes)
            previewDoc.value?.destroy()
            previewDoc.value = preview
            hasSynced.value = true
            draftRestored.value = true
            isLocallyCached.value = true
          } catch {
            /* ignore */
          }
        }
        provider.value?.destroy()
        provider.value = null
        releaseSocket?.()
        releaseSocket = null
        connectionState.value = draft?.ydocUpdate ? 'offline' : 'error'
        if (isAxiosError(cause) && (cause.response?.status === 401 || cause.response?.status === 403)) authFailed.value = true
      }
    }
  }

  function retirePreview() {
    previewDoc.value?.destroy()
    previewDoc.value = null
  }

  function restoreDraft(
    draft: DocumentLocalDraft,
    restoreOptions: RestoreDraftOptions = {},
  ): 'restored' | 'epoch-mismatch' | 'busy' | 'invalid' {
    if (!hasOwner() || ownerId == null || draft.documentId !== documentId || draft.format !== contentFormat) return 'invalid'
    if (!restoreOptions.force) {
      if (pendingCounters.value.size > 0 || draftRestored.value) return 'busy'
      const currentDraft = readDocumentDraft(ownerId, documentId, documentDraftId)
      if (currentDraft?.titleDirty || currentDraft?.ydocUpdate) return 'busy'
    }
    if (!draft.ydocUpdate) return 'restored'
    const current = session.value
    const recordSource = () => {
      if (restoreOptions.sourceDraftId && restoreOptions.sourceDraftId !== documentDraftId) {
        restoredSourceDraftIds.add(restoreOptions.sourceDraftId)
      }
    }

    // 离线/未完成首次同步时，经用户确认覆盖（force）允许直接应用到本地编辑器并缓存，待连接恢复后自动推送。
    if (current == null || !hasSynced.value || epochChanged.value) {
      if (!restoreOptions.force || epochChanged.value) return 'invalid'
      try {
        const bytes = decodeBase64ToUint8Array(draft.ydocUpdate)
        const check = new Y.Doc()
        try { Y.applyUpdate(check, bytes) } finally { check.destroy() }
        if (!saveDocumentDraft(ownerId, documentDraftId, { ...draft, savedAt: Date.now() })) {
          draftCacheFailed.value = true
          return 'invalid'
        }
        Y.applyUpdate(ydoc.value, bytes)
        if (previewDoc.value) Y.applyUpdate(previewDoc.value, bytes)
        draftRestored.value = true
        isLocallyCached.value = true
        recordSource()
        return 'restored'
      } catch {
        return 'invalid'
      }
    }

    // 跨 epoch 草稿：默认返回 epoch-mismatch 以便预览确认；确认覆盖（force）时将纯内容快照写入当前新 epoch。
    if (draft.ydocEpoch !== current.epoch) {
      if (!restoreOptions.force) return 'epoch-mismatch'
      try {
        if (contentFormat === 'HTML') {
          const html = extractHtmlFromDraft(draft)
          if (html == null) return 'invalid'
          ydoc.value.transact(() => {
            const text = ydoc.value.getText('html')
            text.delete(0, text.length)
            if (html.length > 0) text.insert(0, html)
          })
        } else {
          if (!draft.contentSnapshot || !restoreOptions.applyCrossEpochContent) return 'epoch-mismatch'
          if (!restoreOptions.applyCrossEpochContent(draft.contentSnapshot)) return 'invalid'
        }
        const counters = ydoc.value.getMap<number>('session-counters')
        const counter = (counters.get(current.sessionId) ?? 0) + 1
        counters.set(current.sessionId, counter)
        localCounter.value = counter
        pendingCounters.value = new Map(pendingCounters.value).set(current.sessionId, counter)
        const nextUpdateBase64 = encodeUint8ArrayToBase64(Y.encodeStateAsUpdate(ydoc.value))
        const existing = readDocumentDraft(ownerId, documentId, documentDraftId)
        if (!saveDocumentDraft(ownerId, documentDraftId, {
          documentId,
          format: contentFormat,
          title: draft.titleDirty ? draft.title : existing?.title,
          titleDirty: draft.titleDirty || existing?.titleDirty === true,
          ydocUpdate: nextUpdateBase64,
          ydocEpoch: current.epoch,
          contentSnapshot: captureContentSnapshot(),
          serverVersion: serverVersion.value,
          savedAt: Date.now(),
        })) {
          draftCacheFailed.value = true
          return 'invalid'
        }
        draftRestored.value = true
        isLocallyCached.value = true
        recordSource()
        return 'restored'
      } catch {
        return 'invalid'
      }
    }

    try {
      const bytes = decodeBase64ToUint8Array(draft.ydocUpdate)
      const check = new Y.Doc()
      try { Y.applyUpdate(check, bytes) } finally { check.destroy() }
      if (!saveDocumentDraft(ownerId, documentDraftId, { ...draft, savedAt: Date.now() })) {
        draftCacheFailed.value = true
        return 'invalid'
      }
      Y.applyUpdate(ydoc.value, bytes)
      const counters = ydoc.value.getMap<number>('session-counters')
      const counter = (counters.get(current.sessionId) ?? 0) + 1
      counters.set(current.sessionId, counter)
      localCounter.value = counter
      pendingCounters.value = new Map(pendingCounters.value).set(current.sessionId, counter)
      draftRestored.value = true
      isLocallyCached.value = true
      recordSource()
      return 'restored'
    } catch {
      return 'invalid'
    }
  }

  async function restartForEpoch() {
    if (disposed) return
    flushLocalDraft()
    if (hasUnsavedBody.value && !isLocallyCached.value) {
      draftCacheFailed.value = true
      return
    }
    if (hasOwner() && ownerId != null) {
      const draft = readDocumentDraft(ownerId, documentId, documentDraftId)
      if (draft?.ydocUpdate) {
        // 留存旧版本草稿，避免新版本的编辑覆盖尚未同步的正文。
        let retained = false
        try { retained = saveDocumentDraft(ownerId, createDocumentDraftId(), draft) } catch { /* unavailable storage identity */ }
        if (!retained) {
          draftCacheFailed.value = true
          return
        }
        clearDocumentDraft(ownerId, documentId, documentDraftId)
      }
    }
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
    draftRestored.value = false
    isLocallyCached.value = false
    hasSynced.value = false
    authFailed.value = false
    epochChanged.value = false
    connectionState.value = 'connecting'
    if (renewTimer != null) clearTimeout(renewTimer)
    renewTimer = null
    await start()
  }

  /** 主动重建协作会话与底层 WebSocket 传输，保留本地未同步修改并在重连后自动合并推送。 */
  async function reconnect(reconnectOptions: { resetTransport?: boolean } = {}) {
    if (disposed || !hasOwner()) return
    if (hasUnsavedBody.value) flushLocalDraft(true)
    const previousSession = session.value
    generation += 1
    if (renewTimer != null) clearTimeout(renewTimer)
    renewTimer = null
    provider.value?.destroy()
    provider.value = null
    releaseSocket?.()
    releaseSocket = null
    if (reconnectOptions.resetTransport !== false && previousSession != null) {
      resetSharedCollaborationSocket(previousSession.websocketUrl, previousSession.user.id)
    }
    retirePreview()
    ydoc.value.destroy()
    ydoc.value = new Y.Doc()
    trackLocalEdits(ydoc.value)
    session.value = null
    localCounter.value = 0
    savedCounter.value = 0
    pendingCounters.value = new Map()
    draftRestored.value = false
    hasSynced.value = false
    authFailed.value = false
    epochChanged.value = false
    connectionState.value = 'connecting'
    await start()
  }

  function handleResumeCheck() {
    if (disposed || !hasOwner()) return
    const current = session.value
    if (connectionState.value === 'error' || authFailed.value || (current == null && connectionState.value === 'offline')) {
      void reconnect({ resetTransport: true })
      return
    }
    if (current != null && provider.value != null) {
      const remainingMs = Date.parse(current.expiresAt) - Date.now()
      if (remainingMs < 120_000 || connectionState.value === 'offline') {
        void refreshSession(current.sessionId).then(async (renewed) => {
          if (disposed || session.value?.sessionId !== current.sessionId) return
          if (renewed.epoch !== current.epoch) {
            epochChanged.value = true
            connectionState.value = 'offline'
            return
          }
          session.value = { ...current, roomToken: renewed.roomToken, expiresAt: renewed.expiresAt }
          await provider.value?.sendToken()
        }).catch(() => {
          // 交由既有指数退避续期定时器或手动重连兜底。
        })
      }
    }
  }

  function handleVisibilityChange() {
    if (typeof document !== 'undefined' && document.visibilityState === 'visible') {
      handleResumeCheck()
    }
  }

  function handleBeforeUnload() {
    flushLocalDraft()
  }

  function discardDraft() {
    if (draftSaveTimer != null) clearTimeout(draftSaveTimer)
    draftSaveTimer = null
    if (hasOwner() && ownerId != null) clearDocumentDraft(ownerId, documentId, documentDraftId)
    restoredSourceDraftIds.clear()
    draftRestored.value = false
    isLocallyCached.value = false
    draftCacheFailed.value = false
    void restartForEpoch()
  }

  function dispose() {
    flushLocalDraft()
    disposed = true
    generation += 1
    if (renewTimer != null) clearTimeout(renewTimer)
    renewTimer = null
    if (draftSaveTimer != null) clearTimeout(draftSaveTimer)
    draftSaveTimer = null
    provider.value?.destroy()
    provider.value = null
    releaseSocket?.()
    releaseSocket = null
    retirePreview()
    ydoc.value.destroy()
  }

  window.addEventListener('beforeunload', handleBeforeUnload)
  window.addEventListener('online', handleResumeCheck)
  if (typeof document !== 'undefined') document.addEventListener('visibilitychange', handleVisibilityChange)

  watch(() => auth.currentUser?.id, () => {
    if (!hasOwner()) {
      dispose()
      hasSynced.value = false
      authFailed.value = true
      connectionState.value = 'error'
    }
  }, { flush: 'sync' })

  onBeforeUnmount(() => {
    window.removeEventListener('beforeunload', handleBeforeUnload)
    window.removeEventListener('online', handleResumeCheck)
    if (typeof document !== 'undefined') document.removeEventListener('visibilitychange', handleVisibilityChange)
    dispose()
  })
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
    draftRestored: readonly(draftRestored),
    isLocallyCached: readonly(isLocallyCached),
    draftCacheFailed: readonly(draftCacheFailed),
    hasUnsavedBody,
    restoreDraft,
    discardDraft,
    start,
    reconnect,
    retirePreview,
    restartForEpoch,
    dispose
  }
}
