import { describe, expect, it } from 'vitest'
import source from './DocumentEditor.vue?raw'

describe('DocumentEditor long-form layout', () => {
  it('keeps the document page as the bounded flex scroll owner', () => {
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*width: min\(100%, 860px\)/s)
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*min-height: 0/s)
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*flex: 1/s)
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*overflow-y: auto/s)
  })

  it('forwards project document mentions into the structured editor', () => {
    expect(source).toContain('mentionDocuments: Array<{ id: number; title: string; projectId: number }>')
    expect(source).toContain(':mention-documents="mentionDocuments"')
  })
})
