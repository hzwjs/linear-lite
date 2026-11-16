import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  clearDocumentDraft,
  createDocumentDraftId,
  decodeBase64ToUint8Array,
  encodeUint8ArrayToBase64,
  hasUnsavedDraft,
  listDocumentDrafts,
  readDocumentDraft,
  saveDocumentDraft,
  type DocumentLocalDraft,
} from './documentDraftStorage'

function draft(overrides: Partial<DocumentLocalDraft> = {}): DocumentLocalDraft {
  return {
    documentId: 42,
    format: 'BLOCKNOTE',
    titleDirty: true,
    title: 'Offline Notes',
    ydocUpdate: encodeUint8ArrayToBase64(new Uint8Array([1, 2, 3])),
    ydocEpoch: 'epoch-1',
    serverVersion: 3,
    savedAt: Date.now(),
    ...overrides,
  }
}

describe('documentDraftStorage', () => {
  beforeEach(() => localStorage.clear())
  afterEach(() => vi.restoreAllMocks())

  it('encodes and decodes Uint8Array binary base64', () => {
    const original = new Uint8Array([0, 1, 2, 255, 128, 42, 99, 200])
    expect(decodeBase64ToUint8Array(encodeUint8ArrayToBase64(original))).toEqual(original)
  })

  it('persists title and Yjs draft under the account and document', () => {
    const saved = draft()
    expect(saveDocumentDraft(7, 'tab-a', saved)).toBe(true)
    expect(readDocumentDraft(7, 42, 'tab-a')).toEqual(saved)
    expect(hasUnsavedDraft(7, 42, 'tab-a')).toBe(true)
    clearDocumentDraft(7, 42, 'tab-a')
    expect(readDocumentDraft(7, 42, 'tab-a')).toBeNull()
  })

  it('detects title and body changes independently', () => {
    expect(hasUnsavedDraft(7, 42, 'tab-a')).toBe(false)
    saveDocumentDraft(7, 'tab-a', draft({ titleDirty: true, ydocUpdate: undefined, ydocEpoch: undefined }))
    expect(hasUnsavedDraft(7, 42, 'tab-a')).toBe(true)
    saveDocumentDraft(7, 'tab-a', draft({ titleDirty: false, title: undefined, ydocUpdate: 'AQ==' }))
    expect(hasUnsavedDraft(7, 42, 'tab-a')).toBe(true)
    saveDocumentDraft(7, 'tab-a', draft({ titleDirty: false, title: undefined, ydocUpdate: undefined, ydocEpoch: undefined }))
    expect(hasUnsavedDraft(7, 42, 'tab-a')).toBe(false)
  })

  it('isolates reads, writes and deletion by account', () => {
    const saved = draft({ title: 'Account A' })
    saveDocumentDraft(7, 'tab-a', saved)
    expect(readDocumentDraft(8, 42, 'tab-a')).toBeNull()
    saveDocumentDraft(8, 'tab-a', { ...saved, title: 'Account B' })
    saveDocumentDraft(7, 'tab-a', { ...saved, documentId: 420, title: 'Other document' })
    expect(readDocumentDraft(7, 42, 'tab-a')?.title).toBe('Account A')
    expect(readDocumentDraft(8, 42, 'tab-a')?.title).toBe('Account B')
    expect(listDocumentDrafts(7, 42)).toEqual([{ draftId: 'tab-a', draft: saved }])
    expect(listDocumentDrafts(8, 42)).toHaveLength(1)
    clearDocumentDraft(8, 42, 'tab-a')
    expect(readDocumentDraft(7, 42, 'tab-a')?.title).toBe('Account A')
  })

  it('does not use old unscoped entries or operate without an account', () => {
    localStorage.setItem('linear-lite.doc-draft.v1:42', JSON.stringify(draft()))
    expect(readDocumentDraft(7, 42, 'tab-a')).toBeNull()
    localStorage.setItem('linear-lite.doc-draft.v2:7:42', JSON.stringify(draft()))
    expect(listDocumentDrafts(7, 42)).toEqual([])
    for (const invalidUserId of [0, -1, Number.NaN, undefined as unknown as number]) {
      expect(saveDocumentDraft(invalidUserId, 'tab-a', draft())).toBe(false)
      expect(readDocumentDraft(invalidUserId, 42, 'tab-a')).toBeNull()
      expect(hasUnsavedDraft(invalidUserId, 42, 'tab-a')).toBe(false)
      expect(listDocumentDrafts(invalidUserId, 42)).toEqual([])
      clearDocumentDraft(invalidUserId, 42, 'tab-a')
    }
    expect(localStorage.getItem('linear-lite.doc-draft.v1:42')).not.toBeNull()
    expect(localStorage.length).toBe(2)
  })

  it('reports capacity failure without evicting unsynced drafts', () => {
    saveDocumentDraft(7, 'tab-a', draft({ documentId: 1, title: 'First' }))
    saveDocumentDraft(8, 'tab-b', draft({ documentId: 2, title: 'Second' }))
    const write = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new DOMException('Storage full', 'QuotaExceededError')
    })
    const remove = vi.spyOn(Storage.prototype, 'removeItem')
    expect(saveDocumentDraft(7, 'tab-a', draft({ documentId: 3 }))).toBe(false)
    expect(saveDocumentDraft(7, 'tab-a', draft({ documentId: 1, title: 'Replacement' }))).toBe(false)
    expect(remove).not.toHaveBeenCalled()
    expect(readDocumentDraft(7, 1, 'tab-a')?.title).toBe('First')
    expect(readDocumentDraft(8, 2, 'tab-b')?.title).toBe('Second')
    write.mockRestore()
  })

  it('keeps older unsynced drafts when the number or age grows', () => {
    for (let i = 1; i <= 35; i++) {
      expect(saveDocumentDraft(7, 'tab-a', draft({ documentId: i, savedAt: i }))).toBe(true)
    }
    expect(readDocumentDraft(7, 1, 'tab-a')).not.toBeNull()
    expect(readDocumentDraft(7, 35, 'tab-a')).not.toBeNull()
  })

  it('rejects malformed or mismatched records', () => {
    localStorage.setItem('linear-lite.doc-draft.v3:7:55:tab-a', 'invalid json {{{')
    expect(readDocumentDraft(7, 55, 'tab-a')).toBeNull()
    localStorage.setItem('linear-lite.doc-draft.v3:7:55:tab-a', JSON.stringify(draft()))
    expect(readDocumentDraft(7, 55, 'tab-a')).toBeNull()
  })

  it('keeps independent drafts for multiple tabs and copies without removing the source', () => {
    const a = draft({ title: 'Tab A', ydocUpdate: 'AQ==', ydocEpoch: 'epoch-a', savedAt: 10 })
    const b = draft({ title: 'Tab B', ydocUpdate: 'Ag==', ydocEpoch: 'epoch-b', savedAt: 20 })
    expect(saveDocumentDraft(7, 'tab-a', a)).toBe(true)
    expect(saveDocumentDraft(7, 'tab-b', b)).toBe(true)
    expect(listDocumentDrafts(7, 42)).toEqual([{ draftId: 'tab-b', draft: b }, { draftId: 'tab-a', draft: a }])
    expect(saveDocumentDraft(7, 'tab-c', readDocumentDraft(7, 42, 'tab-a')!)).toBe(true)
    expect(readDocumentDraft(7, 42, 'tab-a')).toEqual(a)
    expect(readDocumentDraft(7, 42, 'tab-c')).toEqual(a)
    clearDocumentDraft(7, 42, 'tab-c')
    expect(readDocumentDraft(7, 42, 'tab-a')).toEqual(a)
    expect(readDocumentDraft(7, 42, 'tab-b')).toEqual(b)
  })

  it('requires a collaboration epoch for every Yjs update', () => {
    expect(saveDocumentDraft(7, 'tab-a', draft({ ydocEpoch: undefined }))).toBe(false)
    expect(saveDocumentDraft(7, 'tab-a', draft({ ydocEpoch: '' }))).toBe(false)
    localStorage.setItem('linear-lite.doc-draft.v3:7:42:tab-a', JSON.stringify(draft({ ydocEpoch: undefined })))
    expect(readDocumentDraft(7, 42, 'tab-a')).toBeNull()
    expect(saveDocumentDraft(7, 'tab-a', draft({ ydocUpdate: undefined, ydocEpoch: 'epoch-1' }))).toBe(false)
  })

  it('generates a distinct id for each page instance', () => {
    expect(createDocumentDraftId()).not.toBe(createDocumentDraftId())
  })
})
