export interface DocumentLocalDraft {
  documentId: number
  projectId?: number
  format: 'BLOCKNOTE' | 'HTML'
  title: string
  content?: string
  ydocUpdate?: string
  serverVersion: number
  savedAt: number
  hasUnsavedChanges: boolean
}

const STORAGE_PREFIX = 'linear-lite.doc-draft.v1:'
const INDEX_KEY = 'linear-lite.doc-draft-index.v1'
const MAX_DRAFTS = 30
const MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000 // 7 days

function getStorageKey(documentId: number): string {
  return `${STORAGE_PREFIX}${documentId}`
}

export function encodeUint8ArrayToBase64(bytes: Uint8Array): string {
  let binary = ''
  const len = bytes.byteLength
  for (let i = 0; i < len; i++) {
    binary += String.fromCharCode(bytes[i])
  }
  return btoa(binary)
}

export function decodeBase64ToUint8Array(base64: string): Uint8Array {
  const binary = atob(base64)
  const bytes = new Uint8Array(binary.length)
  for (let i = 0; i < binary.length; i++) {
    bytes[i] = binary.charCodeAt(i)
  }
  return bytes
}

interface DraftIndexEntry {
  documentId: number
  savedAt: number
}

function readIndex(): DraftIndexEntry[] {
  try {
    const raw = localStorage.getItem(INDEX_KEY)
    if (!raw) return []
    const parsed = JSON.parse(raw) as DraftIndexEntry[]
    if (!Array.isArray(parsed)) return []
    return parsed.filter((item) => typeof item.documentId === 'number' && typeof item.savedAt === 'number')
  } catch {
    return []
  }
}

function writeIndex(entries: DraftIndexEntry[]): void {
  try {
    localStorage.setItem(INDEX_KEY, JSON.stringify(entries))
  } catch {
    /* ignore */
  }
}

function pruneDrafts(currentId: number): void {
  const now = Date.now()
  const entries = readIndex()
  const validEntries: DraftIndexEntry[] = []

  for (const entry of entries) {
    if (entry.documentId !== currentId && now - entry.savedAt > MAX_AGE_MS) {
      try {
        localStorage.removeItem(getStorageKey(entry.documentId))
      } catch {
        /* ignore */
      }
    } else {
      validEntries.push(entry)
    }
  }

  if (validEntries.length > MAX_DRAFTS) {
    validEntries.sort((a, b) => a.savedAt - b.savedAt)
    const toRemoveCount = validEntries.length - MAX_DRAFTS
    const toRemove = validEntries.splice(0, toRemoveCount)
    for (const entry of toRemove) {
      if (entry.documentId !== currentId) {
        try {
          localStorage.removeItem(getStorageKey(entry.documentId))
        } catch {
          /* ignore */
        }
      }
    }
  }

  writeIndex(validEntries)
}

export function saveDocumentDraft(draft: DocumentLocalDraft): void {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') return
  try {
    pruneDrafts(draft.documentId)
    const key = getStorageKey(draft.documentId)
    localStorage.setItem(key, JSON.stringify(draft))

    const entries = readIndex().filter((e) => e.documentId !== draft.documentId)
    entries.push({ documentId: draft.documentId, savedAt: draft.savedAt })
    writeIndex(entries)
  } catch {
    try {
      // Quota exceeded: evict oldest 50% drafts and retry once
      const entries = readIndex().sort((a, b) => a.savedAt - b.savedAt)
      const evictCount = Math.max(1, Math.floor(entries.length / 2))
      const evicted = entries.splice(0, evictCount)
      for (const entry of evicted) {
        if (entry.documentId !== draft.documentId) {
          localStorage.removeItem(getStorageKey(entry.documentId))
        }
      }
      writeIndex(entries)
      localStorage.setItem(getStorageKey(draft.documentId), JSON.stringify(draft))
    } catch {
      /* storage completely unavailable or private browsing quota */
    }
  }
}

export function readDocumentDraft(documentId: number): DocumentLocalDraft | null {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') return null
  try {
    const raw = localStorage.getItem(getStorageKey(documentId))
    if (!raw) return null
    const parsed = JSON.parse(raw) as DocumentLocalDraft
    if (typeof parsed !== 'object' || parsed == null) return null
    if (typeof parsed.documentId !== 'number' || typeof parsed.title !== 'string' || typeof parsed.savedAt !== 'number') {
      return null
    }
    return parsed
  } catch {
    return null
  }
}

export function clearDocumentDraft(documentId: number): void {
  if (typeof window === 'undefined' || typeof window.localStorage === 'undefined') return
  try {
    localStorage.removeItem(getStorageKey(documentId))
    const entries = readIndex().filter((e) => e.documentId !== documentId)
    writeIndex(entries)
  } catch {
    /* ignore */
  }
}

export function hasUnsavedDraft(
  documentId: number,
  serverVersion?: number,
  serverTitle?: string,
  serverContent?: string
): boolean {
  const draft = readDocumentDraft(documentId)
  if (!draft || !draft.hasUnsavedChanges) return false
  if (serverTitle !== undefined && draft.title !== serverTitle) return true
  if (serverContent !== undefined && draft.content !== undefined && draft.content !== serverContent) return true
  if (serverVersion !== undefined && draft.serverVersion > serverVersion) return true
  return draft.hasUnsavedChanges
}
