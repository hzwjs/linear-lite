import { createHash, timingSafeEqual } from 'node:crypto'
import type { IncomingMessage, ServerResponse } from 'node:http'
import { Server, type Document } from '@hocuspocus/server'
import * as Y from 'yjs'
import { encodeDocument, materializeAndInspect } from './document-codec.js'
import { JavaCollaborationClient } from './java-client.js'
import { KeyedSerialQueue } from './serial-queue.js'
import { isDocumentUpdateSyncType } from './sync-message.js'
import { parseRoomName, roomName, type CollaborationConfig, type ContentFormat, type DocumentRoomContext, type RoomState } from './types.js'

type RoomRuntime = RoomState & {
  stateBase64: string
  lastEditorId: number
  validationState: Y.Doc
  persistenceBlocked?: boolean
}

type TransitionFence = {
  transitionId: string
  documentId: number
  epoch: string
  nextEpoch: string
  operation: 'archive' | 'unarchive' | 'delete'
  deadlineEpochMs: number
  inFlight: number
}

type CommandRequest = {
  operationId: string
  actorId: number
  epoch: string
  expectedVersion: number
  type: 'replace' | 'restore'
  contentFormat: ContentFormat
  content: string
  title?: string
}

type EncodeInput = { contentFormat: ContentFormat; content: string; key?: string }
type CloseRoomInput = { epoch: string; nextEpoch: string; operation: 'archive' | 'unarchive' | 'delete'; transitionId: string; deadlineEpochMs: number }

export class CollaborationNode {
  readonly httpServer: Server<DocumentRoomContext>
  readonly java: JavaCollaborationClient
  private readonly runtime = new Map<string, RoomRuntime>()
  private readonly roomLocks = new KeyedSerialQueue()
  private readonly validationQueues = new KeyedSerialQueue()
  private readonly persistenceQueues = new KeyedSerialQueue()
  private readonly transitionFences = new Map<number, TransitionFence>()
  private readonly inFlightByDocument = new Map<number, number>()
  private readonly inFlightWaiters = new Map<number, Array<() => void>>()

  constructor(readonly config: CollaborationConfig) {
    this.java = new JavaCollaborationClient(config)
    this.httpServer = new Server<DocumentRoomContext>({
      address: config.host,
      port: config.port,
      quiet: true,
      stopOnSignals: false,
      debounce: config.persistenceDebounceMs,
      maxDebounce: Math.max(1000, config.persistenceDebounceMs * 4),
      onAuthenticate: async ({ documentName, token }) => {
        await this.reconcileRoomFence(documentName)
        this.assertRoomNotFenced(documentName)
        if (this.runtime.get(documentName)?.persistenceBlocked) {
          throw new Error('Collaboration room is refreshing after a state conflict')
        }
        const context = await this.java.authorize(token, documentName)
        await this.reconcileRoomFence(documentName)
        if (context.archived || roomName(context.documentId, context.epoch) !== documentName) {
          throw new Error('Collaboration session is no longer valid')
        }
        return context
      },
      onTokenSync: async ({ documentName, token, context }) => {
        await this.reconcileRoomFence(documentName)
        this.assertRoomNotFenced(documentName)
        const refreshed = await this.java.authorize(token, documentName)
        await this.reconcileRoomFence(documentName)
        if (refreshed.sessionId !== context.sessionId || refreshed.epoch !== context.epoch || refreshed.archived) {
          throw new Error('Collaboration session expired or changed')
        }
        return refreshed
      },
      onLoadDocument: async ({ documentName }) => {
        await this.reconcileRoomFence(documentName)
        this.assertRoomNotFenced(documentName)
        const { documentId, epoch } = parseRoomName(documentName)
        const stored = await this.java.loadState(documentId, epoch)
        await this.reconcileRoomFence(documentName)
        this.assertRoomNotFenced(documentName)
        const state: RoomState = {
          documentId,
          epoch,
          roomName: documentName,
          contentFormat: stored.contentFormat,
          commitSeq: stored.commitSeq,
          version: stored.version,
        }
        const runtime = this.getOrCreateRuntime(state, stored.stateBase64)
        const binary = Buffer.from(stored.stateBase64, 'base64')
        runtime.stateBase64 = stored.stateBase64
        return new Uint8Array(binary)
      },
      beforeHandleMessage: async ({ documentName, context }) => {
        this.assertRoomNotFenced(documentName)
        const documentId = parseRoomName(documentName).documentId
        this.enterInFlight(documentId)
        try {
          const runtime = this.requireRuntime(documentName)
          if (runtime.persistenceBlocked) throw this.httpError(409, 'ROOM_REFRESH_REQUIRED')
          runtime.lastEditorId = context.userId
        } catch (error) {
          this.leaveInFlight(documentId)
          throw error
        }
      },
      afterHandleMessage: async ({ documentName }) => {
        this.leaveInFlight(parseRoomName(documentName).documentId)
      },
      beforeSync: async ({ documentName, type, payload, context }) => {
        if (!isDocumentUpdateSyncType(type)) return
        const runtime = this.requireRuntime(documentName)
        await this.validationQueues.run(documentName, async () => {
          const candidate = new Y.Doc()
          try {
            Y.applyUpdate(candidate, Y.encodeStateAsUpdate(runtime.validationState))
            Y.applyUpdate(candidate, payload)
            const candidateState = Y.encodeStateAsUpdate(candidate)
            const projection = materializeAndInspect(runtime.contentFormat, candidateState)
            this.assertContentSize(runtime.contentFormat, projection.contentBytes)
            await this.java.validateUpdate({
              documentId: runtime.documentId,
              epoch: runtime.epoch,
              userId: context.userId,
              imageAssetIds: projection.imageAssetIds,
              contentFormat: runtime.contentFormat,
              contentBytes: projection.contentBytes,
            })
            runtime.validationState.destroy()
            runtime.validationState = candidate
          } catch (error) {
            candidate.destroy()
            throw error
          }
        })
      },
      onStoreDocument: async ({ document, documentName, lastContext }) => {
        const runtime = this.requireRuntime(documentName)
        if (runtime.persistenceBlocked) throw this.httpError(409, 'ROOM_REFRESH_REQUIRED')
        if (lastContext?.userId) runtime.lastEditorId = lastContext.userId
        await this.persistDocument(document, runtime)
      },
      afterUnloadDocument: async ({ documentName }) => {
        const runtime = this.runtime.get(documentName)
        if (runtime?.persistenceBlocked) {
          runtime.validationState.destroy()
          this.runtime.delete(documentName)
        }
      },
    })
    this.installHttpRequestHandler()
  }

  async listen(): Promise<void> {
    await this.httpServer.listen(this.config.port)
  }

  async destroy(): Promise<void> {
    await this.httpServer.destroy()
    for (const runtime of this.runtime.values()) runtime.validationState.destroy()
    this.runtime.clear()
  }

  private async handleInternalRequest(request: IncomingMessage, response: ServerResponse, url: URL, instance: Server<DocumentRoomContext>['hocuspocus']): Promise<void> {
    if (request.method === 'POST' && url.pathname === '/internal/collaboration/encode') {
      const input = await this.readJson<EncodeInput>(request)
      this.respond(response, 200, this.encodeResponse(input))
      return
    }
    if (request.method === 'POST' && url.pathname === '/internal/collaboration/encode/batch') {
      const body = await this.readJson<{ documents: EncodeInput[] }>(request, 64 * 1024 * 1024)
      if (!Array.isArray(body.documents) || body.documents.length > 100) throw this.httpError(400, 'BATCH_SIZE_INVALID')
      const documents = body.documents.map((input) => this.encodeResponse(input))
      const responseBytes = documents.reduce((total, item) => total + Buffer.byteLength(item.stateBase64, 'utf8'), 0)
      if (responseBytes > 64 * 1024 * 1024) throw this.httpError(413, 'BATCH_RESPONSE_TOO_LARGE')
      this.respond(response, 200, { documents })
      return
    }
    if (request.method === 'POST' && url.pathname === '/internal/collaboration/transitions/release') {
      const input = await this.readJson<{ transitionId: string; status: 'FINALIZED' | 'ABORTED'; documents: Array<{ documentId: number; epoch: string }> }>(request)
      await this.releaseTransition(input)
      this.respond(response, 204)
      return
    }
    const match = /^\/internal\/collaboration\/documents\/(\d+)\/(snapshot|commands|metadata|close-room)$/.exec(url.pathname)
    if (!match) {
      this.respond(response, 404, { error: 'NOT_FOUND' })
      return
    }
    const documentId = Number(match[1])
    const route = match[2]
    if (request.method === 'POST' && route === 'close-room') {
      const input = await this.readJson<CloseRoomInput>(request)
      const result = await this.closeRoom(documentId, input, instance)
      this.respond(response, 200, result)
      return
    }
    if (request.method === 'GET' && route === 'snapshot') {
      const epoch = url.searchParams.get('epoch')
      if (!epoch) throw this.httpError(400, 'EPOCH_REQUIRED')
      const snapshot = await this.snapshot(documentId, epoch, url.searchParams.get('flush') === 'true', instance)
      this.respond(response, 200, snapshot)
      return
    }
    if (request.method === 'POST' && route === 'commands') {
      const command = await this.readJson<CommandRequest>(request)
      const result = await this.command(documentId, command, instance)
      this.respond(response, 200, result)
      return
    }
    if (request.method === 'POST' && route === 'metadata') {
      const metadata = await this.readJson<{ epoch: string; version: number; title: string; editorId: number }>(request)
      const name = roomName(documentId, metadata.epoch)
      instance.documents.get(name)?.broadcastStateless(JSON.stringify({ event: 'metadata', ...metadata }))
      this.respond(response, 204)
      return
    }
    this.respond(response, 404, { error: 'NOT_FOUND' })
  }

  private installHttpRequestHandler(): void {
    const server = this.httpServer.httpServer
    server.removeListener('request', this.httpServer.requestHandler)
    server.on('request', (request, response) => {
      void this.handleHttpRequest(request, response).catch((error: unknown) => {
        if (response.headersSent) {
          response.destroy(error instanceof Error ? error : undefined)
          return
        }
        const status = this.statusFor(error)
        this.respond(response, status, { error: this.publicError(error, status) })
      })
    })
  }

  private async handleHttpRequest(request: IncomingMessage, response: ServerResponse): Promise<void> {
    const url = new URL(request.url ?? '/', `http://${request.headers.host ?? 'localhost'}`)
    if (!url.pathname.startsWith('/internal/collaboration/')) {
      response.statusCode = 200
      response.setHeader('Content-Type', 'text/plain')
      response.end('Welcome to Hocuspocus!')
      return
    }
    if (!this.hasInternalKey(request)) {
      this.respond(response, 401, { error: 'UNAUTHORIZED' })
      return
    }
    await this.handleInternalRequest(request, response, url, this.httpServer.hocuspocus)
  }

  private encodeResponse(input: EncodeInput) {
    if (input.contentFormat !== 'BLOCKNOTE' && input.contentFormat !== 'HTML') throw this.httpError(400, 'CONTENT_FORMAT_INVALID')
    const state = encodeDocument(input.contentFormat, input.content)
    const projection = materializeAndInspect(input.contentFormat, state)
    this.assertContentSize(input.contentFormat, projection.contentBytes)
    return {
      ...(input.key === undefined ? {} : { key: input.key }),
      contentFormat: input.contentFormat,
      stateBase64: Buffer.from(state).toString('base64'),
      stateSha256: createHash('sha256').update(state).digest('hex'),
      content: projection.content,
      contentBytes: projection.contentBytes,
      imageAssetIds: projection.imageAssetIds,
    }
  }

  private async snapshot(documentId: number, epoch: string, flush: boolean, instance: Server<DocumentRoomContext>['hocuspocus']) {
    const name = roomName(documentId, epoch)
    return this.roomLocks.run(String(documentId), async () => {
      this.assertRoomNotFenced(name)
      const connection = await instance.openDirectConnection(name, {} as DocumentRoomContext)
      try {
        const document = instance.documents.get(name)
        if (!document) throw new Error('Collaboration room failed to load')
        const runtime = this.requireRuntime(name)
        if (flush) {
          await this.flushScheduledStore(name, instance)
          await this.persistDocument(document, runtime)
        }
        const projection = materializeAndInspect(runtime.contentFormat, Y.encodeStateAsUpdate(document))
        return {
          documentId,
          epoch,
          version: runtime.version,
          commitSeq: runtime.commitSeq,
          contentFormat: runtime.contentFormat,
          content: projection.content,
        }
      } finally {
        await connection.disconnect({ unloadImmediately: false })
      }
    })
  }

  private async closeRoom(documentId: number, input: CloseRoomInput, instance: Server<DocumentRoomContext>['hocuspocus']) {
    this.validateCloseRoomInput(input)
    const name = roomName(documentId, input.epoch)
    const existingFence = this.transitionFences.get(documentId)
    if (existingFence && existingFence.transitionId !== input.transitionId) {
      await this.reconcileRoomFence(name)
    }
    return this.roomLocks.run(String(documentId), async () => {
      let fence = this.transitionFences.get(documentId)
      if (fence && fence.transitionId !== input.transitionId) throw this.httpError(409, 'ROOM_TRANSITION_ACTIVE')
      if (!fence) {
        fence = {
          transitionId: input.transitionId,
          documentId,
          epoch: input.epoch,
          nextEpoch: input.nextEpoch,
          operation: input.operation,
          deadlineEpochMs: input.deadlineEpochMs,
          inFlight: this.inFlightByDocument.get(documentId) ?? 0,
        }
        // Java's persistent fence blocks new sessions before this process fence is installed.
        this.transitionFences.set(documentId, fence)
      }
      if (fence.epoch !== input.epoch || fence.nextEpoch !== input.nextEpoch || fence.operation !== input.operation
        || fence.deadlineEpochMs !== input.deadlineEpochMs) {
        throw this.httpError(409, 'ROOM_TRANSITION_FIELDS_CONFLICT')
      }
      await this.waitForInFlight(documentId, fence)
      const document = instance.documents.get(name)
      let result: { epoch: string; commitSeq: number; version: number; transitionId: string }
      if (document) {
        const runtime = this.requireRuntime(name)
        await this.flushScheduledStore(name, instance)
        await this.persistDocument(document, runtime, input.transitionId)
        result = { epoch: runtime.epoch, commitSeq: runtime.commitSeq, version: runtime.version, transitionId: input.transitionId }
      } else {
        const stored = await this.java.loadState(documentId, input.epoch, input.transitionId)
        result = { epoch: input.epoch, commitSeq: stored.commitSeq, version: stored.version, transitionId: input.transitionId }
      }
      instance.closeConnections(name)
      if (document) {
        if (!await this.waitForConnectionsClosed(document)) throw this.httpError(503, 'ROOM_CONNECTIONS_STILL_OPEN')
        await instance.unloadDocument(document)
      }
      return result
    })
  }

  private validateCloseRoomInput(input: CloseRoomInput): void {
    if (!/^[a-zA-Z0-9_-]+$/.test(input.epoch) || !/^[a-zA-Z0-9_-]+$/.test(input.nextEpoch)
      || input.epoch === input.nextEpoch || !['archive', 'unarchive', 'delete'].includes(input.operation)
      || !input.transitionId || !Number.isSafeInteger(input.deadlineEpochMs) || input.deadlineEpochMs <= 0) {
      throw this.httpError(400, 'CLOSE_ROOM_FIELDS_INVALID')
    }
  }

  private async releaseTransition(input: {
    transitionId: string
    status: 'FINALIZED' | 'ABORTED'
    documents: Array<{ documentId: number; epoch: string }>
  }): Promise<void> {
    if (!input.transitionId || !['FINALIZED', 'ABORTED'].includes(input.status) || !Array.isArray(input.documents)) {
      throw this.httpError(400, 'TRANSITION_RELEASE_FIELDS_INVALID')
    }
    for (const item of input.documents) {
      if (!Number.isSafeInteger(item.documentId) || item.documentId <= 0 || !/^[a-zA-Z0-9_-]+$/.test(item.epoch)) {
        throw this.httpError(400, 'TRANSITION_RELEASE_DOCUMENT_INVALID')
      }
      const fence = this.transitionFences.get(item.documentId)
      if (fence?.transitionId === input.transitionId) {
        const expectedEpoch = input.status === 'FINALIZED' ? fence.nextEpoch : fence.epoch
        if (item.epoch !== expectedEpoch) throw this.httpError(409, 'TRANSITION_RELEASE_EPOCH_MISMATCH')
      }
    }
    for (const item of input.documents) {
      await this.roomLocks.run(String(item.documentId), async () => {
        const fence = this.transitionFences.get(item.documentId)
        if (!fence || fence.transitionId !== input.transitionId) return
        if (input.status === 'FINALIZED') {
          const oldRoom = roomName(item.documentId, fence.epoch)
          this.runtime.get(oldRoom)?.validationState.destroy()
          this.runtime.delete(oldRoom)
        } else {
          const oldRoom = roomName(item.documentId, fence.epoch)
          const document = this.httpServer.hocuspocus.documents.get(oldRoom)
          const runtime = this.runtime.get(oldRoom)
          if (document && runtime && !runtime.persistenceBlocked) {
            await this.persistDocument(document, runtime, null)
          }
        }
        this.transitionFences.delete(item.documentId)
      })
    }
  }

  private async reconcileRoomFence(room: string): Promise<void> {
    const { documentId } = parseRoomName(room)
    const fence = this.transitionFences.get(documentId)
    if (!fence) return
    const transition = await this.java.getTransition(fence.transitionId)
    if (transition.status === 'ACTIVE') throw this.httpError(409, 'ROOM_TRANSITION_ACTIVE')
    await this.releaseTransition({
      transitionId: transition.transitionId,
      status: transition.status,
      documents: transition.documents.map(({ documentId: id, epoch }) => ({ documentId: id, epoch })),
    })
  }

  private async flushScheduledStore(name: string, instance: Server<DocumentRoomContext>['hocuspocus']): Promise<void> {
    await instance.debouncer.executeNow(`onStoreDocument-${name}`)
  }

  private async waitForConnectionsClosed(document: Document): Promise<boolean> {
    const deadline = Date.now() + this.config.requestTimeoutMs
    while (document.getConnectionsCount() > 0 && Date.now() < deadline) {
      await new Promise((resolve) => setTimeout(resolve, 10))
    }
    return document.getConnectionsCount() === 0
  }

  private async command(documentId: number, command: CommandRequest, instance: Server<DocumentRoomContext>['hocuspocus']) {
    if (!command.operationId || command.actorId <= 0 || !Number.isSafeInteger(command.expectedVersion)) {
      throw this.httpError(400, 'COMMAND_FIELDS_INVALID')
    }
    if (command.type !== 'replace' && command.type !== 'restore') throw this.httpError(400, 'COMMAND_TYPE_INVALID')
    if (command.contentFormat !== 'BLOCKNOTE' && command.contentFormat !== 'HTML') throw this.httpError(400, 'CONTENT_FORMAT_INVALID')
    return this.roomLocks.run(String(documentId), async () => {
      const name = roomName(documentId, command.epoch)
      this.assertRoomNotFenced(name)
      const connection = await instance.openDirectConnection(name, {} as DocumentRoomContext)
      try {
        const document = instance.documents.get(name)
        if (!document) throw new Error('Collaboration room failed to load')
        const runtime = this.requireRuntime(name)
        await this.flushScheduledStore(name, instance)
        await this.persistDocument(document, runtime)
        if (runtime.version !== command.expectedVersion) throw this.httpError(409, 'VERSION_CONFLICT')
        if (runtime.contentFormat !== command.contentFormat) throw this.httpError(409, 'CONTENT_FORMAT_CONFLICT')
        const binary = encodeDocument(command.contentFormat, command.content)
        const projection = materializeAndInspect(command.contentFormat, binary)
        this.assertContentSize(command.contentFormat, projection.contentBytes)
        await this.java.validateUpdate({
          documentId,
          epoch: runtime.epoch,
          userId: command.actorId,
          imageAssetIds: projection.imageAssetIds,
          contentFormat: runtime.contentFormat,
          contentBytes: projection.contentBytes,
        })
        runtime.lastEditorId = command.actorId
        if (command.type === 'restore') {
          const next = await this.java.restoreEpoch(documentId, {
            expectedEpoch: runtime.epoch,
            expectedCommitSeq: runtime.commitSeq,
            contentFormat: runtime.contentFormat,
            stateBase64: Buffer.from(binary).toString('base64'),
            content: projection.content,
            editorId: command.actorId,
            operationId: command.operationId,
            title: command.title,
          })
          document.broadcastStateless(JSON.stringify({ event: 'epochChanged', epoch: next.epoch }))
          runtime.persistenceBlocked = true
          instance.closeConnections(name)
          return { epoch: next.epoch, commitSeq: next.commitSeq, version: next.version, contentFormat: command.contentFormat, content: projection.content, title: command.title }
        }
        await connection.transact((target) => this.replaceDocumentContents(target, command.contentFormat, binary, command.content))
        const persisted = await this.persistDocument(document, runtime)
        return { epoch: runtime.epoch, commitSeq: persisted.commitSeq, version: persisted.version, contentFormat: runtime.contentFormat, content: projection.content }
      } finally {
        await connection.disconnect({ unloadImmediately: command.type === 'restore' })
      }
    })
  }

  private replaceDocumentContents(document: Y.Doc, contentFormat: ContentFormat, binary: Uint8Array, content: string): void {
    if (contentFormat === 'HTML') {
      const text = document.getText('html')
      text.delete(0, text.length)
      if (content.length) text.insert(0, content)
      return
    }
    const fragment = document.getXmlFragment('document-store')
    fragment.delete(0, fragment.length)
    Y.applyUpdate(document, binary)
  }

  private async persistDocument(document: Document, runtime: RoomRuntime, requestedTransitionId?: string | null): Promise<{ commitSeq: number; version: number }> {
    return this.persistenceQueues.run(runtime.roomName, async () => {
      if (runtime.persistenceBlocked) throw this.httpError(409, 'ROOM_REFRESH_REQUIRED')
      const transitionId = requestedTransitionId === undefined
        ? this.transitionFences.get(runtime.documentId)?.transitionId
        : requestedTransitionId ?? undefined
      const state = Y.encodeStateAsUpdate(document)
      const stateBase64 = Buffer.from(state).toString('base64')
      if (stateBase64 === runtime.stateBase64 && !transitionId) return { commitSeq: runtime.commitSeq, version: runtime.version }
      const projection = materializeAndInspect(runtime.contentFormat, state)
      this.assertContentSize(runtime.contentFormat, projection.contentBytes)
      try {
        const saved = await this.java.storeState({
          documentId: runtime.documentId,
          epoch: runtime.epoch,
          contentFormat: runtime.contentFormat,
          expectedCommitSeq: runtime.commitSeq,
          stateBase64,
          content: projection.content,
          editorId: runtime.lastEditorId,
          counters: this.counters(state),
          transitionId,
        })
        return this.recordPersisted(document, runtime, state, stateBase64, saved)
      } catch (error) {
        if (this.isTransitionConflict(error)) throw error
        let transitionCanonical: Awaited<ReturnType<JavaCollaborationClient['loadState']>> | undefined
        if (transitionId) {
          try {
            const canonical = await this.java.loadState(runtime.documentId, runtime.epoch, transitionId)
            if (canonical.stateBase64 === stateBase64) {
              return this.recordPersisted(document, runtime, state, stateBase64, {
                commitSeq: canonical.commitSeq,
                version: canonical.version,
              })
            }
            transitionCanonical = canonical
          } catch (readError) {
            if (this.isTransitionConflict(readError)) throw readError
          }
        }
        if ((error as { status?: number }).status !== 409) throw error
        const canonical = transitionCanonical ?? await this.java.loadState(runtime.documentId, runtime.epoch)
        runtime.commitSeq = canonical.commitSeq
        runtime.version = canonical.version
        runtime.contentFormat = canonical.contentFormat
        runtime.stateBase64 = canonical.stateBase64
        runtime.persistenceBlocked = true
        this.httpServer.hocuspocus.closeConnections(runtime.roomName)
        throw error
      }
    })
  }

  private recordPersisted(
    document: Document,
    runtime: RoomRuntime,
    state: Uint8Array,
    stateBase64: string,
    saved: { commitSeq: number; version: number },
  ): { commitSeq: number; version: number } {
    runtime.commitSeq = saved.commitSeq
    runtime.version = saved.version
    runtime.stateBase64 = stateBase64
    document.broadcastStateless(JSON.stringify({
      event: 'saved',
      epoch: runtime.epoch,
      counters: this.counters(state),
      version: saved.version,
      commitSeq: saved.commitSeq,
    }))
    return saved
  }

  private isTransitionConflict(error: unknown): boolean {
    const text = (error as { responseBody?: string })?.responseBody
    if (!text) return false
    try {
      const body = JSON.parse(text) as { errorType?: string }
      return body.errorType === 'TRANSITION_FENCE_ACTIVE' || body.errorType === 'TRANSITION_EXPIRED'
    } catch {
      return false
    }
  }

  private assertRoomNotFenced(room: string): void {
    const { documentId } = parseRoomName(room)
    if (this.transitionFences.has(documentId)) throw this.httpError(409, 'ROOM_TRANSITION_ACTIVE')
  }

  private enterInFlight(documentId: number): void {
    this.inFlightByDocument.set(documentId, (this.inFlightByDocument.get(documentId) ?? 0) + 1)
    const fence = this.transitionFences.get(documentId)
    if (fence) fence.inFlight += 1
  }

  private leaveInFlight(documentId: number): void {
    const count = this.inFlightByDocument.get(documentId) ?? 0
    if (count <= 1) this.inFlightByDocument.delete(documentId)
    else this.inFlightByDocument.set(documentId, count - 1)
    const fence = this.transitionFences.get(documentId)
    if (fence && fence.inFlight > 0) fence.inFlight -= 1
    if ((this.inFlightByDocument.get(documentId) ?? 0) === 0) {
      for (const resolve of this.inFlightWaiters.get(documentId) ?? []) resolve()
      this.inFlightWaiters.delete(documentId)
    }
  }

  private async waitForInFlight(documentId: number, fence: TransitionFence): Promise<void> {
    while (fence.inFlight > 0) {
      await new Promise<void>((resolve, reject) => {
        const waiters = this.inFlightWaiters.get(documentId) ?? []
        const done = () => {
          clearTimeout(timeout)
          resolve()
        }
        const timeout = setTimeout(() => {
          const current = this.inFlightWaiters.get(documentId) ?? []
          this.inFlightWaiters.set(documentId, current.filter((waiter) => waiter !== done))
          reject(this.httpError(503, 'ROOM_DRAIN_TIMEOUT'))
        }, this.config.requestTimeoutMs)
        waiters.push(done)
        this.inFlightWaiters.set(documentId, waiters)
      })
    }
    if (fence.inFlight !== 0) throw this.httpError(500, 'ROOM_DRAIN_COUNTER_MISMATCH')
  }

  private counters(state: Uint8Array): Record<string, number> {
    const vector = Y.decodeStateVector(Y.encodeStateVectorFromUpdate(state))
    return Object.fromEntries(Array.from(vector, ([clientId, clock]) => [String(clientId), clock]))
  }

  private getOrCreateRuntime(state: RoomState, stateBase64: string): RoomRuntime {
    const existing = this.runtime.get(state.roomName)
    if (existing) {
      existing.commitSeq = state.commitSeq
      existing.version = state.version
      existing.contentFormat = state.contentFormat
      return existing
    }
    const validationState = new Y.Doc()
    Y.applyUpdate(validationState, Buffer.from(stateBase64, 'base64'))
    const runtime: RoomRuntime = {
      ...state,
      stateBase64,
      lastEditorId: 0,
      validationState,
    }
    this.runtime.set(state.roomName, runtime)
    return runtime
  }

  private requireRuntime(room: string): RoomRuntime {
    const runtime = this.runtime.get(room)
    if (!runtime) throw new Error('Collaboration room state is not loaded')
    return runtime
  }

  private assertContentSize(format: ContentFormat, bytes: number): void {
    const max = format === 'HTML' ? this.config.htmlMaxBytes : this.config.blocknoteMaxBytes
    if (bytes > max) throw this.httpError(413, 'CONTENT_TOO_LARGE')
  }

  private hasInternalKey(request: IncomingMessage): boolean {
    const value = request.headers['x-collaboration-internal-key']
    if (typeof value !== 'string') return false
    const provided = Buffer.from(value)
    const expected = Buffer.from(this.config.internalKey)
    return provided.length === expected.length && timingSafeEqual(provided, expected)
  }

  private async readJson<T>(request: IncomingMessage, maxBytes = 12 * 1024 * 1024): Promise<T> {
    const chunks: Buffer[] = []
    let bytes = 0
    for await (const chunk of request) {
      const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk)
      bytes += buffer.length
      if (bytes > maxBytes) throw this.httpError(413, 'REQUEST_TOO_LARGE')
      chunks.push(buffer)
    }
    try {
      return JSON.parse(Buffer.concat(chunks).toString('utf8')) as T
    } catch {
      throw this.httpError(400, 'JSON_INVALID')
    }
  }

  private respond(response: ServerResponse, status: number, body?: unknown): void {
    response.statusCode = status
    response.setHeader('Cache-Control', 'no-store')
    if (body === undefined) {
      response.end()
      return
    }
    response.setHeader('Content-Type', 'application/json; charset=utf-8')
    response.end(JSON.stringify(body))
  }

  private httpError(status: number, code: string): Error {
    return Object.assign(new Error(code), { status, code })
  }

  private statusFor(error: unknown): number {
    const status = Number((error as { status?: number })?.status)
    return status >= 400 && status < 600 ? status : 502
  }

  private publicError(error: unknown, status: number): string {
    const code = (error as { code?: string })?.code
    if (code) return code
    if (status === 409) return 'STATE_CONFLICT'
    if (status === 404) return 'STATE_NOT_FOUND'
    if (status === 413) return 'CONTENT_TOO_LARGE'
    return 'COLLABORATION_BACKEND_ERROR'
  }
}
