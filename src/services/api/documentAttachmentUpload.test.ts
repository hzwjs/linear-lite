import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError, CanceledError } from 'axios'
import { api } from './index'
import { uploadDocumentAttachment } from './documentAttachmentUpload'

vi.mock('./index', () => ({
  api: { post: vi.fn(), put: vi.fn(), delete: vi.fn() },
  unwrap: (response: { data: { data: unknown } }) => response.data.data,
}))

const partSize = 8 * 1024 * 1024
const attachment = { id: 31, documentId: 11, fileSize: partSize + 7, sha256: 'verified', url: '/download' }
const session = (status: string, parts: unknown[] = []) => ({
  data: { data: { sessionToken: 'session', partSize, status, parts, attachment: status === 'completed' ? attachment : null } },
})

describe('document attachment multipart upload', () => {
  beforeEach(() => { vi.resetAllMocks(); vi.useFakeTimers() })
  afterEach(() => vi.useRealTimers())

  it('uploads bounded parts, reports bytes and waits for metadata confirmation', async () => {
    const file = new File([new Uint8Array(partSize + 7)], 'report.bin')
    vi.mocked(api.post).mockResolvedValueOnce(session('uploading') as any)
      .mockResolvedValueOnce(session('processing') as any).mockResolvedValueOnce(session('completed') as any)
    vi.mocked(api.put).mockImplementation(async (_url, _body, options) => {
      options?.onUploadProgress?.({ loaded: 1, total: 2 } as any)
      return {} as any
    })
    const progress = vi.fn()
    const promise = uploadDocumentAttachment(11, file, { onProgress: progress })
    await vi.runAllTimersAsync()
    expect(await promise).toBe(attachment)
    expect(api.put).toHaveBeenCalledTimes(2)
    const first = vi.mocked(api.put).mock.calls[0]
    const form = first[1] as FormData
    expect((form.get('file') as File).size).toBe(partSize)
    expect(form.get('sessionToken')).toBe('session')
    expect(progress).toHaveBeenCalledWith({ stage: 'uploading', loaded: partSize / 2, total: file.size })
    expect(progress).toHaveBeenCalledWith({ stage: 'processing', loaded: file.size, total: file.size })
    expect(vi.mocked(api.post).mock.calls.map(call => call[0])).toEqual([
      '/project-documents/11/attachment-uploads', '/project-documents/11/attachment-uploads/complete',
      '/project-documents/11/attachment-uploads/status',
    ])
  })

  it('resumes missing parts after a failed attempt', async () => {
    const file = new File([new Uint8Array(partSize + 7)], 'report.bin')
    vi.mocked(api.post).mockResolvedValueOnce(session('uploading', [{ partNumber: 1, size: partSize }]) as any)
      .mockResolvedValueOnce(session('completed') as any)
    vi.mocked(api.put).mockResolvedValue({} as any)
    const progress = vi.fn()
    await expect(uploadDocumentAttachment(11, file, { sessionToken: 'session', onProgress: progress })).resolves.toBe(attachment)
    expect(api.put).toHaveBeenCalledOnce()
    expect(vi.mocked(api.put).mock.calls[0][0]).toBe('/project-documents/11/attachment-uploads/parts/2')
    expect(progress).toHaveBeenCalledWith({ stage: 'uploading', loaded: partSize, total: file.size })
  })

  it('restarts confirmation if the service loses its in-memory processing task', async () => {
    vi.mocked(api.post).mockResolvedValueOnce(session('uploaded') as any)
      .mockResolvedValueOnce(session('processing') as any).mockResolvedValueOnce(session('uploaded') as any)
      .mockResolvedValueOnce(session('completed') as any)
    const promise = uploadDocumentAttachment(11, new File(['content'], 'report.pdf'), { sessionToken: 'session' })
    await vi.runAllTimersAsync()
    await expect(promise).resolves.toBe(attachment)
    expect(api.put).not.toHaveBeenCalled()
    expect(vi.mocked(api.post).mock.calls.map(call => call[0])).toEqual([
      '/project-documents/11/attachment-uploads/status', '/project-documents/11/attachment-uploads/complete',
      '/project-documents/11/attachment-uploads/status', '/project-documents/11/attachment-uploads/complete',
    ])
  })

  it('retries a failed part without starting another upload session', async () => {
    const file = new File(['content'], 'report.pdf')
    vi.mocked(api.post).mockResolvedValueOnce(session('uploading') as any).mockResolvedValueOnce(session('completed') as any)
    vi.mocked(api.put).mockRejectedValueOnce(new AxiosError('connection reset', 'ERR_NETWORK')).mockResolvedValue({} as any)
    const progress = vi.fn()
    const promise = uploadDocumentAttachment(11, file, { onProgress: progress })
    await vi.runAllTimersAsync()
    await expect(promise).resolves.toBe(attachment)
    expect(api.put).toHaveBeenCalledTimes(2)
    expect(progress).toHaveBeenCalledWith({ stage: 'retrying', loaded: 0, total: file.size })
    expect(api.post).toHaveBeenCalledTimes(2)
  })

  it('cancels in-flight parts and does not confirm an incomplete file', async () => {
    const file = new File(['content'], 'report.pdf')
    vi.mocked(api.post).mockResolvedValue(session('uploading') as any)
    const controller = new AbortController()
    let putStarted: () => void = () => undefined
    const started = new Promise<void>(resolve => { putStarted = resolve })
    vi.mocked(api.put).mockImplementation((_url, _body, options) => new Promise((_resolve, reject) => {
      putStarted()
      options?.signal?.addEventListener?.('abort', () => reject(new CanceledError()))
    }))
    const promise = uploadDocumentAttachment(11, file, { signal: controller.signal })
    const rejected = expect(promise).rejects.toMatchObject({ name: 'AbortError' })
    await started
    controller.abort()
    await rejected
    expect(api.post).toHaveBeenCalledOnce()
  })

  it('rejects a mismatched stored part before transferring any bytes', async () => {
    vi.mocked(api.post).mockResolvedValue(session('uploading', [{ partNumber: 1, size: 99 }]) as any)
    await expect(uploadDocumentAttachment(11, new File(['content'], 'report.pdf'), { sessionToken: 'session' })).rejects.toThrow('does not match')
    expect(api.put).not.toHaveBeenCalled()
  })
})
