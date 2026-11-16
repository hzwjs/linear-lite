export interface DocumentLocalDraft {
  documentId: number
  format: 'BLOCKNOTE' | 'HTML'
  titleDirty: boolean
  title?: string
  ydocUpdate?: string
  ydocEpoch?: string
  serverVersion: number
  savedAt: number
}

export interface DocumentDraftEntry {
  draftId: string
  draft: DocumentLocalDraft
}

const STORAGE_PREFIX = 'linear-lite.doc-draft.v3:'

function isValidUserId(userId: number): boolean {
  return Number.isSafeInteger(userId) && userId > 0
}

function isValidDraftId(draftId: string): boolean {
  return typeof draftId === 'string' && /^[a-zA-Z0-9_-]{1,128}$/.test(draftId)
}

function getStoragePrefix(userId: number, documentId: number): string {
  return `${STORAGE_PREFIX}${userId}:${documentId}:`
}

function getStorageKey(userId: number, documentId: number, draftId: string): string {
  return `${getStoragePrefix(userId, documentId)}${draftId}`
}

export function createDocumentDraftId(): string {
  const bytes = new Uint8Array(16)
  crypto.getRandomValues(bytes)
  return Array.from(bytes, (byte) => byte.toString(16).padStart(2, '0')).join('')
}

// 每个页面实例使用独立标识；刷新或复制标签页不会覆写原草稿。
export const documentDraftId = createDocumentDraftId()

function isValidDraft(value: unknown, documentId: number): value is DocumentLocalDraft {
  if (typeof value !== 'object' || value == null) return false
  const draft = value as DocumentLocalDraft
  if (draft.documentId !== documentId || !Number.isSafeInteger(documentId) || documentId <= 0 ||
      (draft.format !== 'BLOCKNOTE' && draft.format !== 'HTML') ||
      typeof draft.titleDirty !== 'boolean' ||
      (draft.titleDirty && typeof draft.title !== 'string') ||
      (draft.title !== undefined && typeof draft.title !== 'string') ||
      typeof draft.serverVersion !== 'number' || !Number.isFinite(draft.serverVersion) ||
      typeof draft.savedAt !== 'number' || !Number.isFinite(draft.savedAt)) return false
  const hasUpdate = draft.ydocUpdate !== undefined
  const hasEpoch = draft.ydocEpoch !== undefined
  if (hasUpdate !== hasEpoch) return false
  if (hasUpdate && (typeof draft.ydocUpdate !== 'string' || draft.ydocUpdate.length === 0 ||
      typeof draft.ydocEpoch !== 'string' || draft.ydocEpoch.length === 0)) return false
  return true
}

export function encodeUint8ArrayToBase64(bytes: Uint8Array): string {
  let binary = ''
  for (let i = 0; i < bytes.byteLength; i++) binary += String.fromCharCode(bytes[i]!)
  return btoa(binary)
}

export function decodeBase64ToUint8Array(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i)
  return bytes
}

export function saveDocumentDraft(userId: number, draftId: string, draft: DocumentLocalDraft): boolean {
  if (!isValidUserId(userId) || !isValidDraftId(draftId) || !isValidDraft(draft, draft?.documentId) || typeof window === 'undefined') return false
  try {
    // Never evict unsynced drafts to make room for another document.
    window.localStorage.setItem(getStorageKey(userId, draft.documentId, draftId), JSON.stringify(draft))
    return true
  } catch {
    return false
  }
}

export function readDocumentDraft(userId: number, documentId: number, draftId: string): DocumentLocalDraft | null {
  if (!isValidUserId(userId) || !isValidDraftId(draftId) || typeof window === 'undefined') return null
  try {
    const raw = window.localStorage.getItem(getStorageKey(userId, documentId, draftId))
    if (!raw) return null
    const draft: unknown = JSON.parse(raw)
    if (!isValidDraft(draft, documentId)) return null
    return draft
  } catch {
    return null
  }
}

export function listDocumentDrafts(userId: number, documentId: number): DocumentDraftEntry[] {
  if (!isValidUserId(userId) || typeof window === 'undefined') return []
  try {
    const prefix = getStoragePrefix(userId, documentId)
    const drafts: DocumentDraftEntry[] = []
    for (let i = 0; i < window.localStorage.length; i++) {
      const key = window.localStorage.key(i)
      if (key == null || !key.startsWith(prefix)) continue
      const draftId = key.slice(prefix.length)
      const draft = readDocumentDraft(userId, documentId, draftId)
      if (draft) drafts.push({ draftId, draft })
    }
    return drafts.sort((a, b) => b.draft.savedAt - a.draft.savedAt || a.draftId.localeCompare(b.draftId))
  } catch {
    return []
  }
}

export function clearDocumentDraft(userId: number, documentId: number, draftId: string): void {
  if (!isValidUserId(userId) || !isValidDraftId(draftId) || typeof window === 'undefined') return
  try {
    window.localStorage.removeItem(getStorageKey(userId, documentId, draftId))
  } catch {
    // Storage may be unavailable in private browsing.
  }
}

export function hasUnsavedDraft(userId: number, documentId: number, draftId: string): boolean {
  const draft = readDocumentDraft(userId, documentId, draftId)
  return draft != null && (draft.titleDirty || Boolean(draft.ydocUpdate))
}
