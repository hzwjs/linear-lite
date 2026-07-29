import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { documentApi, parseDocumentAttachmentFileName } from './documents'

vi.mock('./index', () => ({
  api: { get: vi.fn() },
  unwrap: (res: { data: { data: unknown } }) => res.data.data
}))

describe('document attachment download', () => {
  beforeEach(() => {
    vi.mocked(api.get).mockReset()
  })

  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('decodes the UTF-8 filename field emitted by the document attachment endpoint', () => {
    expect(parseDocumentAttachmentFileName(
      "attachment; filename=ignored.txt; filename*=UTF-8''%E8%BF%81%E7%A7%BB%E6%B8%85%E5%8D%95.pdf"
    )).toBe('迁移清单.pdf')
  })

  it('requests a blob with the authenticated api client and downloads it with the response filename', async () => {
    const blob = new Blob(['document'])
    vi.mocked(api.get).mockResolvedValue({
      data: blob,
      headers: { 'content-disposition': "attachment; filename*=UTF-8''guide.pdf" }
    } as any)
    const createObjectURL = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:document')
    const revokeObjectURL = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => undefined)
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)

    await documentApi.downloadAttachment(12, 34)

    expect(api.get).toHaveBeenCalledWith('/project-documents/12/attachments/34/download', {
      responseType: 'blob'
    })
    expect(createObjectURL).toHaveBeenCalledWith(blob)
    expect(click).toHaveBeenCalledOnce()
    expect(revokeObjectURL).toHaveBeenCalledWith('blob:document')
  })

  it('returns an attachment blob for authenticated inline rendering', async () => {
    const blob = new Blob(['image'], { type: 'image/png' })
    vi.mocked(api.get).mockResolvedValue({ data: blob, headers: {} } as any)

    await expect(documentApi.getAttachmentBlob(12, 36)).resolves.toBe(blob)
    expect(api.get).toHaveBeenCalledWith('/project-documents/12/attachments/36/download', {
      responseType: 'blob'
    })
  })

  it('fails before creating a download when Content-Disposition has no UTF-8 filename', async () => {
    vi.mocked(api.get).mockResolvedValue({
      data: new Blob(['document']),
      headers: { 'content-disposition': 'attachment; filename="guide.pdf"' }
    } as any)
    const createObjectURL = vi.spyOn(URL, 'createObjectURL')

    await expect(documentApi.downloadAttachment(12, 34)).rejects.toThrow('missing a UTF-8 filename')
    expect(createObjectURL).not.toHaveBeenCalled()
  })
})
