import { createApp, defineComponent, h, nextTick, onMounted, ref } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DocumentEditor from './DocumentEditor.vue'
import { documentApi } from '../../services/api/documents'

vi.mock('../../services/api/documents', () => ({
  documentApi: { deleteAttachment: vi.fn(), downloadAttachment: vi.fn(), getAttachmentBlob: vi.fn(), uploadAttachment: vi.fn() }
}))

const removeAttachmentLink = vi.fn()

vi.mock('../StructuredDocumentEditor.vue', () => ({
  default: defineComponent({
    name: 'StructuredDocumentEditorStub',
    props: { documentId: { type: Number, required: true }, uploadFile: { type: Function, required: false } },
    setup(props, { expose }) {
      expose({ removeAttachmentLink })
      const showImage = ref(false)
      onMounted(async () => {
        await nextTick()
        showImage.value = true
      })
      return () => h('div', { 'data-document-id': props.documentId }, [
        h('button', {
          id: 'upload-file',
          onClick: () => props.uploadFile?.(new File(['document'], 'guide.pdf', { type: 'application/pdf' }))
        }, 'upload'),
        h('a', { id: 'attachment', href: '/api/project-documents/12/attachments/34/download', target: '_blank' }, 'attachment'),
        h('a', { id: 'other-document', href: '/api/project-documents/99/attachments/35/download' }, 'other document'),
        h('a', { id: 'ordinary', href: '/projects/7', target: '_blank' }, 'ordinary'),
        h('a', { id: 'attachment-with-query', href: '/api/project-documents/12/attachments/34/download?preview=1', target: '_blank' }, 'preview'),
        h('a', { id: 'invalid', href: 'http://[', target: '_blank' }, 'invalid'),
        showImage.value
          ? h('img', { id: 'attachment-image', src: '/api/project-documents/12/attachments/36/download', alt: 'diagram' })
          : null
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
      attachments: { deleteFailed: 'Delete failed', downloadFailed: 'Download failed' },
      documents: { attachmentDocumentMismatch: 'Wrong document' }
    } },
    missingWarn: false,
    fallbackWarn: false
  }))
  app.mount(host)
  return { app, host }
}

beforeEach(() => {
  vi.mocked(documentApi.deleteAttachment).mockResolvedValue()
  vi.mocked(documentApi.getAttachmentBlob).mockResolvedValue(new Blob(['image'], { type: 'image/png' }))
  vi.mocked(documentApi.uploadAttachment).mockResolvedValue({ url: '/api/project-documents/12/attachments/37/download' })
  removeAttachmentLink.mockReturnValue(true)
  Object.defineProperty(URL, 'createObjectURL', {
    configurable: true,
    value: vi.fn(() => 'blob:authenticated-image')
  })
  Object.defineProperty(URL, 'revokeObjectURL', {
    configurable: true,
    value: vi.fn()
  })
})

afterEach(() => {
  vi.mocked(documentApi.deleteAttachment).mockReset()
  vi.mocked(documentApi.downloadAttachment).mockReset()
  vi.mocked(documentApi.getAttachmentBlob).mockReset()
  removeAttachmentLink.mockReset()
  vi.restoreAllMocks()
  document.body.replaceChildren()
})

describe('DocumentEditor attachment links', () => {
  it('deletes an attachment from the card without requiring text-block editing', async () => {
    const view = renderEditor()
    await nextTick()
    await nextTick()

    const deleteButton = view.host.querySelector<HTMLButtonElement>('.document-attachment-delete')
    expect(deleteButton).not.toBeNull()
    deleteButton?.click()
    await nextTick()
    await nextTick()

    expect(documentApi.deleteAttachment).toHaveBeenCalledWith(12, 34)
    expect(removeAttachmentLink).toHaveBeenCalledWith('/api/project-documents/12/attachments/34/download')
    view.app.unmount()
  })

  it('keeps the attachment available and reports a delete failure', async () => {
    vi.mocked(documentApi.deleteAttachment).mockRejectedValueOnce(new Error('network'))
    const view = renderEditor()
    await nextTick()
    await nextTick()

    view.host.querySelector<HTMLButtonElement>('.document-attachment-delete')?.click()
    await nextTick()
    await nextTick()

    expect(view.host.textContent).toContain('Delete failed')
    expect(view.host.querySelector('.document-attachment-delete')).not.toBeNull()
    expect(removeAttachmentLink).not.toHaveBeenCalled()
    view.app.unmount()
  })

  it('does not mutate editor-owned attachment link attributes during hydration', async () => {
    const view = renderEditor()
    await nextTick()
    await nextTick()

    expect(view.host.querySelector('#attachment')?.classList.contains('document-attachment-link')).toBe(false)
    expect(view.host.querySelector('#other-document')?.classList.contains('document-attachment-link')).toBe(false)
    expect(view.host.querySelector('#ordinary')?.classList.contains('document-attachment-link')).toBe(false)
    expect(view.host.querySelector('#attachment-with-query')?.classList.contains('document-attachment-link')).toBe(false)
    view.app.unmount()
  })

  it('hydrates a protected attachment image through the authenticated api client', async () => {
    const view = renderEditor()
    await nextTick()
    await nextTick()
    await nextTick()

    expect(documentApi.getAttachmentBlob).toHaveBeenCalledWith(12, 36)
    expect(view.host.querySelector<HTMLImageElement>('#attachment-image')?.src)
      .toBe('blob:authenticated-image')

    view.app.unmount()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:authenticated-image')
  })

  it('shows a friendly loading state while a protected attachment image is fetched', async () => {
    let resolveBlob!: (blob: Blob) => void
    vi.mocked(documentApi.getAttachmentBlob).mockReturnValueOnce(new Promise((resolve) => {
      resolveBlob = resolve
    }))
    const view = renderEditor()
    await nextTick()
    await nextTick()
    await nextTick()

    const image = view.host.querySelector<HTMLImageElement>('#attachment-image')
    const host = image?.closest<HTMLElement>('.document-attachment-image-host--loading')
    expect(host).not.toBeNull()
    expect(host?.querySelector('.document-attachment-image-status')).not.toBeNull()
    expect(host?.querySelector('.document-attachment-image-status__text')?.textContent).toBe('documents.imageLoading')
    expect(image?.dataset.documentAttachmentState).toBe('loading')
    expect(image?.getAttribute('aria-busy')).toBe('true')

    resolveBlob(new Blob(['image'], { type: 'image/png' }))
    await nextTick()
    await nextTick()
    expect(image?.dataset.documentAttachmentState).toBe('loading')

    view.app.unmount()
  })

  it('passes the current document id to the editor upload boundary', async () => {
    const view = renderEditor()
    const editor = view.host.querySelector('[data-document-id]')
    expect(editor).toBeTruthy()
    expect(editor?.getAttribute('data-document-id')).toBe('12')
    view.app.unmount()
  })

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
