import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DocumentEditor from './DocumentEditor.vue'
import { documentApi } from '../../services/api/documents'

vi.mock('../../services/api/documents', () => ({
  documentApi: { downloadAttachment: vi.fn() }
}))

vi.mock('../StructuredDocumentEditor.vue', () => ({
  default: defineComponent({
    name: 'StructuredDocumentEditorStub',
    setup() {
      return () => h('div', [
        h('a', { id: 'attachment', href: '/api/project-documents/12/attachments/34/download', target: '_blank' }, 'attachment'),
        h('a', { id: 'other-document', href: '/api/project-documents/99/attachments/35/download' }, 'other document'),
        h('a', { id: 'ordinary', href: '/projects/7', target: '_blank' }, 'ordinary'),
        h('a', { id: 'attachment-with-query', href: '/api/project-documents/12/attachments/34/download?preview=1', target: '_blank' }, 'preview'),
        h('a', { id: 'invalid', href: 'http://[', target: '_blank' }, 'invalid')
      ])
    }
  })
}))

const documentFixture = {
  id: 12,
  projectId: 7,
  parentDocumentId: null,
  title: 'Migration guide',
  content: '[]',
  creatorId: 1,
  lastEditorId: 1,
  archivedAt: null,
  createdAt: '2026-07-29T08:00:00',
  sortOrder: 0,
  version: 1,
  updatedAt: '2026-07-29T08:00:00'
}

function renderEditor() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(DocumentEditor, {
    document: documentFixture,
    treeNodes: [{
      id: 12,
      projectId: 7,
      parentDocumentId: null,
      title: 'Migration guide',
      sortOrder: 0,
      version: 1,
      updatedAt: '2026-07-29T08:00:00'
    }],
    saveState: 'idle',
    conflictVersion: null,
    mentionMembers: [],
    mentionDocuments: []
  })
  app.use(createI18n({
    legacy: false,
    locale: 'en',
    messages: { en: {
      attachments: { downloadFailed: 'Download failed' },
      documents: { attachmentDocumentMismatch: 'Wrong document' }
    } },
    missingWarn: false,
    fallbackWarn: false
  }))
  app.mount(host)
  return { app, host }
}

afterEach(() => {
  vi.mocked(documentApi.downloadAttachment).mockReset()
  vi.restoreAllMocks()
  document.body.replaceChildren()
})

describe('DocumentEditor attachment links', () => {
  it('intercepts the current document attachment and downloads it through the api client', async () => {
    vi.mocked(documentApi.downloadAttachment).mockResolvedValue()
    const view = renderEditor()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })

    view.host.querySelector('#attachment')?.dispatchEvent(event)
    await nextTick()

    expect(event.defaultPrevented).toBe(true)
    expect(documentApi.downloadAttachment).toHaveBeenCalledWith(12, 34)
    view.app.unmount()
  })

  it('intercepts an attachment before the editor stops click propagation', async () => {
    vi.mocked(documentApi.downloadAttachment).mockResolvedValue()
    const view = renderEditor()
    const anchor = view.host.querySelector('#attachment')!
    anchor.addEventListener('click', (event) => event.stopPropagation())
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })

    anchor.dispatchEvent(event)
    await nextTick()

    expect(event.defaultPrevented).toBe(true)
    expect(documentApi.downloadAttachment).toHaveBeenCalledWith(12, 34)
    view.app.unmount()
  })

  it('blocks the editor mousedown chain from opening an intercepted attachment', async () => {
    vi.mocked(documentApi.downloadAttachment).mockResolvedValue()
    const openSpy = vi.spyOn(window, 'open').mockImplementation(() => null)
    const view = renderEditor()
    const anchor = view.host.querySelector<HTMLAnchorElement>('#attachment')!
    // ProseMirror converts mousedown into a document-level mouseup handler before click is emitted.
    anchor.parentElement!.addEventListener('mousedown', () => {
      document.addEventListener('mouseup', () => window.open(anchor.href, anchor.target), { once: true })
    })

    anchor.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, cancelable: true }))
    anchor.dispatchEvent(new MouseEvent('mouseup', { bubbles: true, cancelable: true }))
    anchor.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    await nextTick()

    expect(openSpy).not.toHaveBeenCalled()
    expect(documentApi.downloadAttachment).toHaveBeenCalledWith(12, 34)
    view.app.unmount()
  })

  it('blocks an attachment URL belonging to another document and exposes an error', async () => {
    const view = renderEditor()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })

    view.host.querySelector('#other-document')?.dispatchEvent(event)
    await nextTick()

    expect(event.defaultPrevented).toBe(true)
    expect(documentApi.downloadAttachment).not.toHaveBeenCalled()
    expect(view.host.querySelector('[role="alert"]')?.textContent).toContain('Wrong document')
    view.app.unmount()
  })

  it.each(['#ordinary', '#attachment-with-query', '#invalid'])('does not intercept unrelated link %s', (selector) => {
    const view = renderEditor()
    const event = new MouseEvent('click', { bubbles: true, cancelable: true })

    view.host.querySelector(selector)?.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(false)
    expect(documentApi.downloadAttachment).not.toHaveBeenCalled()
    view.app.unmount()
  })

  it('shows a download error when the authenticated request fails', async () => {
    vi.mocked(documentApi.downloadAttachment).mockRejectedValue(new Error('Unauthorized'))
    const view = renderEditor()

    view.host.querySelector('#attachment')?.dispatchEvent(new MouseEvent('click', { bubbles: true, cancelable: true }))
    await nextTick()
    await nextTick()

    expect(view.host.querySelector('[role="alert"]')?.textContent).toContain('Download failed')
    view.app.unmount()
  })
})
