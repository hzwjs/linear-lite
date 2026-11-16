import { beforeEach, describe, expect, it } from 'vitest'
import {
  clearDocumentDraft,
  decodeBase64ToUint8Array,
  encodeUint8ArrayToBase64,
  hasUnsavedDraft,
  readDocumentDraft,
  saveDocumentDraft,
  type DocumentLocalDraft,
} from './documentDraftStorage'

describe('documentDraftStorage', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('encodes and decodes Uint8Array binary base64 correctly', () => {
    const original = new Uint8Array([0, 1, 2, 255, 128, 42, 99, 200])
    const base64 = encodeUint8ArrayToBase64(original)
    const decoded = decodeBase64ToUint8Array(base64)
    expect(decoded).toEqual(original)
  })

  it('saves and reads a document draft from localStorage', () => {
    const draft: DocumentLocalDraft = {
      documentId: 42,
      projectId: 1,
      format: 'BLOCKNOTE',
      title: 'Offline Notes',
      content: '[{"type":"paragraph","content":"hello"}]',
      ydocUpdate: encodeUint8ArrayToBase64(new Uint8Array([1, 2, 3])),
      serverVersion: 3,
      savedAt: Date.now(),
      hasUnsavedChanges: true,
    }

    saveDocumentDraft(draft)
    const read = readDocumentDraft(42)

    expect(read).not.toBeNull()
    expect(read?.documentId).toBe(42)
    expect(read?.title).toBe('Offline Notes')
    expect(read?.format).toBe('BLOCKNOTE')
    expect(read?.hasUnsavedChanges).toBe(true)
    expect(read?.ydocUpdate).toBe(draft.ydocUpdate)
  })

  it('clears draft from localStorage', () => {
    saveDocumentDraft({
      documentId: 99,
      format: 'HTML',
      title: 'HTML Doc',
      serverVersion: 1,
      savedAt: Date.now(),
      hasUnsavedChanges: true,
    })

    expect(readDocumentDraft(99)).not.toBeNull()
    clearDocumentDraft(99)
    expect(readDocumentDraft(99)).toBeNull()
  })

  it('detects unsaved draft with hasUnsavedDraft', () => {
    expect(hasUnsavedDraft(123)).toBe(false)

    saveDocumentDraft({
      documentId: 123,
      format: 'BLOCKNOTE',
      title: 'Draft Title',
      content: 'new content',
      serverVersion: 2,
      savedAt: Date.now(),
      hasUnsavedChanges: true,
    })

    expect(hasUnsavedDraft(123, 2, 'Old Title', 'new content')).toBe(true)
    expect(hasUnsavedDraft(123, 2, 'Draft Title', 'old content')).toBe(true)
    expect(hasUnsavedDraft(123, 1, 'Draft Title', 'new content')).toBe(true)
  })

  it('handles corrupted localStorage data gracefully', () => {
    localStorage.setItem('linear-lite.doc-draft.v1:55', 'invalid json {{{')
    expect(readDocumentDraft(55)).toBeNull()
  })

  it('prunes old drafts beyond MAX_DRAFTS', () => {
    for (let i = 1; i <= 35; i++) {
      saveDocumentDraft({
        documentId: i,
        format: 'BLOCKNOTE',
        title: `Doc ${i}`,
        serverVersion: 1,
        savedAt: 1000 + i,
        hasUnsavedChanges: true,
      })
    }

    // Oldest drafts (1..5) should have been pruned to stay within MAX_DRAFTS (30)
    expect(readDocumentDraft(1)).toBeNull()
    expect(readDocumentDraft(2)).toBeNull()
    // Recent draft should remain
    expect(readDocumentDraft(35)).not.toBeNull()
  })
})
