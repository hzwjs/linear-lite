import { beforeEach, describe, expect, it, vi } from 'vitest'
import { AxiosError } from 'axios'
import { api } from './index'
import { documentApi } from './documents'

vi.mock('./index', async (importOriginal) => ({
  ...await importOriginal<typeof import('./index')>(),
  api: { post: vi.fn() }
}))

describe('Markdown document import API', () => {
  beforeEach(() => { vi.mocked(api.post).mockReset() })

  it('uploads the file and parent through multipart with the browser boundary', async () => {
    const file = new File(['# Guide'], '说明.md', { type: 'text/markdown' })
    vi.mocked(api.post).mockResolvedValue({ data: { code: 200, data: { id: 21, contentFormat: 'BLOCKNOTE' } } })
    await expect(documentApi.importMarkdown(7, 12, file, [])).resolves.toMatchObject({ id: 21 })
    const [url, body, options] = vi.mocked(api.post).mock.calls[0]!
    expect(url).toBe('/projects/7/documents/import-markdown')
    expect((body as FormData).get('file')).toBe(file)
    expect((body as FormData).get('parentDocumentId')).toBe('12')
    expect(options).toEqual({ timeout: 120000, headers: { 'Content-Type': undefined } })
  })

  it('inspects image references and pairs uploaded images with JSON paths', async () => {
    const file = new File(['![图](图.png)'], 'guide.md')
    const image = new File(['png'], '图.png', { type: 'image/png' })
    vi.mocked(api.post).mockResolvedValue({ data: { code: 200, data: { imagePaths: ['图.png'] } } })
    await expect(documentApi.inspectMarkdown(7, file)).resolves.toEqual({ imagePaths: ['图.png'] })
    expect(vi.mocked(api.post).mock.calls[0]![0]).toBe('/projects/7/documents/import-markdown/inspect')
    await documentApi.importMarkdown(7, null, file, [{ path: '图.png', file: image }])
    const form = vi.mocked(api.post).mock.calls[1]![1] as FormData
    expect(form.getAll('images')).toEqual([image])
    expect((form.get('imagePaths') as Blob).type).toBe('application/json')
    const paths = await new Promise<string>((resolve) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.readAsText(form.get('imagePaths') as Blob) })
    expect(JSON.parse(paths)).toEqual(['图.png'])
  })

  it('omits a root parent and exposes the server validation message', async () => {
    const response = { data: { message: 'Markdown 文件须使用 UTF-8 编码' } } as any
    vi.mocked(api.post).mockRejectedValue(new AxiosError('Bad request', 'ERR_BAD_REQUEST', undefined, undefined, response))
    let error: unknown
    try { await documentApi.importMarkdown(7, null, new File(['x'], 'guide.md'), []) } catch (cause) { error = cause }
    expect(error).toBeInstanceOf(Error)
    expect((error as Error).message).toContain('UTF-8')
    expect((vi.mocked(api.post).mock.calls[0]![1] as FormData).has('parentDocumentId')).toBe(false)
  })
})
