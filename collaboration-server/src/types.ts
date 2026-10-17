export type ContentFormat = 'BLOCKNOTE' | 'HTML'

export interface DocumentRoomContext {
  documentId: number
  projectId: number
  contentFormat: ContentFormat
  userId: number
  userName: string
  color: string
  sessionId: string
  epoch: string
  version: number
  archived: false
}

export interface RoomState {
  documentId: number
  epoch: string
  roomName: string
  contentFormat: ContentFormat
  commitSeq: number
  version: number
}

export interface StoredRoomState {
  stateBase64: string
  commitSeq: number
  version: number
  contentFormat: ContentFormat
}

export interface CollaborationConfig {
  host: string
  port: number
  javaBaseUrl: string
  internalKey: string
  blocknoteMaxBytes: number
  htmlMaxBytes: number
  requestTimeoutMs: number
  persistenceDebounceMs: number
}

export function roomName(documentId: number, epoch: string): string {
  return `doc:${documentId}:epoch:${epoch}`
}

export function parseRoomName(name: string): { documentId: number; epoch: string } {
  const match = /^doc:(\d+):epoch:([a-zA-Z0-9_-]+)$/.exec(name)
  if (!match) throw new Error('Invalid collaboration room name')
  return { documentId: Number(match[1]), epoch: match[2] }
}
