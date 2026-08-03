import { describe, expect, it } from 'vitest'
import source from './DocumentEditor.vue?raw'
import minimapSource from './DocumentMinimap.vue?raw'

describe('DocumentEditor long-form layout', () => {
  it('keeps the document page as the bounded flex scroll owner', () => {
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*width: min\(100%, var\(--document-page-max-width\)\)/s)
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*min-height: 0/s)
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*flex: 1/s)
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*overflow-y: auto/s)
  })

  it('forwards project document mentions into the structured editor', () => {
    expect(source).toContain('mentionDocuments: Array<{ id: number; title: string; projectId: number }>')
    expect(source).toContain(':mention-documents="mentionDocuments"')
  })

  it('keeps the favorite action immediately after the toolbar document name', () => {
    expect(source).toMatch(/class="document-editor__toolbar-meta"[\s\S]*<nav class="document-editor__breadcrumbs"[\s\S]*<\/nav>[\s\S]*class="document-editor__favorite"/)
    expect(source).not.toMatch(/<nav class="document-editor__breadcrumbs"[\s\S]*class="document-editor__favorite"[\s\S]*<\/nav>/)
    expect(source).not.toContain('document-editor__actions .document-editor__favorite')
  })

  it('mounts the minimap against the single document scroll owner', () => {
    expect(source).toContain('ref="documentPageRef" class="document-editor__page"')
    expect(source).toContain('<DocumentMinimap :scroll-element="documentPageRef" :controls="`document-scroll-${document.id}`" />')
    expect(source).toMatch(/\.document-editor\s*\{[^}]*container: document-editor \/ inline-size/s)
  })

  it('keeps the compact minimap inside the existing document gutter', () => {
    expect(source).toContain('--document-page-max-width: 860px')
    expect(source).toMatch(/\.document-editor__page\s*\{[^}]*margin: 0 auto/s)
    expect(minimapSource).toContain('--document-minimap-width: 24px')
    expect(minimapSource).toContain('--document-minimap-height: 140px')
    expect(minimapSource).toContain('left: calc((100% - var(--document-page-max-width)) / 4 - var(--document-minimap-width) / 2);')
    expect(minimapSource).toContain('@container document-editor (min-width: 920px)')
    expect(minimapSource).toContain('highlighted ? palette.active : palette.inactive')
    expect(minimapSource).toContain('documentMinimapTickWidth(Math.abs(y - hoverPointerY), 8, width)')
  })
})
