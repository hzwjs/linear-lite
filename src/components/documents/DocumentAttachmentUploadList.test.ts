import { createApp, defineComponent, h, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { documentApi } from '../../services/api/documents'
import { abortDocumentAttachmentUpload } from '../../services/api/documentAttachmentUpload'
import { useDocumentAttachmentUploads } from './useDocumentAttachmentUploads'
import DocumentAttachmentUploadList from './DocumentAttachmentUploadList.vue'

vi.mock('vue-i18n', () => ({ useI18n: () => ({ t: (key: string) => key }) }))
vi.mock('../../services/api/documents', () => ({ documentApi: { uploadAttachment: vi.fn(), deleteAttachment: vi.fn() } }))
vi.mock('../../services/api/documentAttachmentUpload', async importOriginal => ({
  ...await importOriginal<typeof import('../../services/api/documentAttachmentUpload')>(),
  abortDocumentAttachmentUpload: vi.fn().mockResolvedValue(undefined),
}))

let app: App
let host: HTMLDivElement
let uploads: ReturnType<typeof useDocumentAttachmentUploads>
const flush = async () => { await Promise.resolve(); await nextTick(); await Promise.resolve(); await nextTick() }
const attachment = { id: 31, documentId: 11, url: '/download' } as any

beforeEach(() => {
  vi.clearAllMocks()
  host = document.createElement('div')
  document.body.appendChild(host)
  app = createApp(defineComponent({ setup() {
    uploads = useDocumentAttachmentUploads()
    return () => h(DocumentAttachmentUploadList, { jobs: uploads.jobs.value, onRetry: uploads.retry, onCancel: uploads.cancel })
  } }))
  app.mount(host)
})
afterEach(() => { app.unmount(); host.remove() })

describe('document attachment upload feedback', () => {
  it('starts document uploads on HTTP where crypto.randomUUID is unavailable', async () => {
    vi.stubGlobal('crypto', { getRandomValues: globalThis.crypto.getRandomValues.bind(globalThis.crypto) })
    try {
      vi.mocked(documentApi.uploadAttachment).mockResolvedValue(attachment)
      await expect(uploads.upload(11, new File(['HTTP upload'], 'http.txt'))).resolves.toBe(attachment)
      expect(documentApi.uploadAttachment).toHaveBeenCalledOnce()
    } finally { vi.unstubAllGlobals() }
  })

  it('renders real byte progress and aborts an in-flight upload on cancel', async () => {
    let signal: AbortSignal | undefined
    vi.mocked(documentApi.uploadAttachment).mockImplementation((_documentId, file, options) => {
      signal = options?.signal
      options?.onSession?.('bound-session')
      options?.onProgress?.({ stage: 'uploading', loaded: file.size / 2, total: file.size })
      return new Promise(() => undefined)
    })
    const promise = uploads.upload(11, new File([new Uint8Array(1024)], 'report.pdf'))
    const canceled = expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    await flush()
    expect(host.textContent).toContain('report.pdf')
    expect(host.querySelector('progress')?.value).toBe(50)
    host.querySelector<HTMLButtonElement>('[aria-label="documents.uploadCancel"]')?.click()
    await canceled
    expect(signal?.aborted).toBe(true)
    expect(abortDocumentAttachmentUpload).toHaveBeenCalledWith(11, 'bound-session')
    await flush()
    expect(host.querySelector('.document-upload')).toBeNull()
  })

  it('keeps a failed upload retryable and resolves its original editor callback after resume', async () => {
    vi.mocked(documentApi.uploadAttachment).mockImplementationOnce(async (_documentId, _file, options) => {
      options?.onSession?.('bound-session')
      throw new Error('Network interrupted')
    }).mockResolvedValueOnce(attachment)
    const promise = uploads.upload(11, new File(['content'], 'report.pdf'))
    await flush()
    expect(host.textContent).toContain('Network interrupted')
    expect(host.querySelector('.document-upload--failed')).not.toBeNull()
    host.querySelector<HTMLButtonElement>('[aria-label="documents.uploadRetry"]')?.click()
    await expect(promise).resolves.toBe(attachment)
    expect(vi.mocked(documentApi.uploadAttachment).mock.calls[1]?.[2]?.sessionToken).toBe('bound-session')
  })

  it('uses an indeterminate confirmation state and cleans up an upload completed after navigation', async () => {
    let complete: (value: any) => void = () => undefined
    vi.mocked(documentApi.uploadAttachment).mockImplementation((_documentId, file, options) => {
      options?.onProgress?.({ stage: 'processing', loaded: file.size, total: file.size })
      return new Promise(resolve => { complete = resolve })
    })
    const promise = uploads.upload(11, new File(['content'], 'report.pdf'))
    const canceled = expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    await flush()
    expect(host.textContent).toContain('documents.uploadProcessing')
    expect(host.querySelector('progress')?.hasAttribute('value')).toBe(false)
    expect(host.querySelector('[aria-label="documents.uploadCancel"]')).toBeNull()
    app.unmount()
    complete(attachment)
    await canceled
    expect(documentApi.deleteAttachment).toHaveBeenCalledWith(11, 31)
  })
})
