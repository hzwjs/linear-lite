import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DocumentEditor from './DocumentEditor.vue'

vi.mock('../StructuredDocumentEditor.vue', () => ({
  default: defineComponent({
    name: 'StructuredDocumentEditorStub',
    setup: () => () => h('div')
  })
}))

describe('DocumentEditor PDF export', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.classList.remove('document-pdf-export')
    document.body.replaceChildren()
  })

  it('prints the active document and restores the page after printing', async () => {
    vi.useFakeTimers()
    const print = vi.spyOn(window, 'print').mockImplementation(() => {})
    document.title = 'Linear Lite'
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentEditor, {
      document: {
        id: 12,
        projectId: 7,
        parentDocumentId: null,
        title: 'Operations handbook',
        content: '[]',
        creatorId: 1,
        lastEditorId: 1,
        archivedAt: null,
        createdAt: '2026-07-20T08:00:00',
        sortOrder: 0,
        version: 3,
        favorited: false,
        updatedAt: '2026-07-28T08:00:00'
      },
      treeNodes: [],
      saveState: 'saved',
      conflictVersion: null,
      mentionMembers: [],
      mentionDocuments: []
    })
    app.use(createI18n({
      legacy: false,
      locale: 'en',
      messages: { en: { documents: { exportPdf: 'Export PDF', exportingPdf: 'Preparing PDF…', untitled: 'Untitled' } } },
      missingWarn: false,
      fallbackWarn: false
    }))
    app.mount(host)
    await nextTick()

    ;(host.querySelector('button[title="Export PDF"]') as HTMLButtonElement).click()
    await nextTick()
    vi.advanceTimersByTime(0)

    expect(print).toHaveBeenCalledOnce()
    expect(document.body.classList.contains('document-pdf-export')).toBe(true)
    expect(document.title).toBe('Operations handbook')

    window.dispatchEvent(new Event('afterprint'))
    expect(document.body.classList.contains('document-pdf-export')).toBe(false)
    expect(document.title).toBe('Linear Lite')
    app.unmount()
    print.mockRestore()
  })
})
