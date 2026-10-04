/** @jsxImportSource react */
import { act, createElement } from 'react'
import { createRoot } from 'react-dom/client'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import BlockNoteEditorReact, { type EditorApi } from './BlockNoteEditorReact'
import { prepareImagePaste, readLocalPastedImage, type PastedImageSource } from './documentImagePaste'

const asset = { assetId: 92, contentHash: 'hash', originalUrl: '/asset/92', thumbnailUrl: '/thumb/92', width: 640, height: 480 }
const labels = { source: '图片来源', importing: '图片转存中' }
let host: HTMLDivElement
let root: ReturnType<typeof createRoot>
let editorApi: EditorApi
let json: any[]

beforeEach(() => {
  Object.defineProperty(Range.prototype, 'getClientRects', { configurable: true, value: () => [] })
  Object.defineProperty(Range.prototype, 'getBoundingClientRect', { configurable: true, value: () => new DOMRect() })
  vi.stubGlobal('IS_REACT_ACT_ENVIRONMENT', true)
  vi.stubGlobal('matchMedia', () => ({ matches: false, addListener() {}, removeListener() {}, addEventListener() {}, removeEventListener() {} }))
  host = document.createElement('div')
  document.body.append(host)
  root = createRoot(host)
  json = []
})
afterEach(async () => {
  await act(async () => root.unmount())
  host.remove()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
  delete (Range.prototype as any).getClientRects
  delete (Range.prototype as any).getBoundingClientRect
})
async function mount(importPastedImage: (source: PastedImageSource, signal: AbortSignal) => Promise<typeof asset>) {
  await act(async () => root.render(createElement(BlockNoteEditorReact, {
    documentId: 11, pasteFileAsLink: true, imagePasteLabels: labels, importPastedImage,
    initialContent: JSON.stringify([{ id: 'start', type: 'paragraph', content: [], children: [] }]),
    onInit: api => { editorApi = api }, onChange: value => { json = JSON.parse(value) },
  })))
}
async function paste(html: string, text = '', files: File[] = []) {
  await act(async () => {
    const target = host.querySelector<HTMLElement>('.bn-editor[contenteditable]')!
    target.focus()
    const event = new Event('paste', { bubbles: true, cancelable: true })
    Object.defineProperty(event, 'clipboardData', { value: {
      types: [...(html ? ['text/html'] : []), 'text/plain', ...(files.length ? ['Files'] : [])],
      items: files.map(file => ({ getAsFile: () => file })), getData: (type: string) => type === 'text/html' ? html : text,
    } })
    target.dispatchEvent(event)
  })
}

describe('document image paste', () => {
  it('inserts mixed rich content while an image import is pending and replaces only its placeholder', async () => {
    let finish!: (value: typeof asset) => void
    const importImage = vi.fn(() => new Promise<typeof asset>(resolve => { finish = resolve }))
    await mount(importImage)
    await paste('<h2>标题</h2><p>图片前</p><img src="https://outside.example/diagram.png" alt="架构图"><p>图片后</p><ul><li>列表项</li></ul>')
    expect(host.textContent).toContain('图片前')
    expect(host.textContent).toContain('图片后')
    expect(host.textContent).toContain('列表项')
    expect(host.querySelector('.bn-pasted-image__preview')?.getAttribute('src')).toBe('https://outside.example/diagram.png')
    expect(JSON.stringify(json)).not.toContain('linear-lite.invalid')
    expect(JSON.stringify(json)).not.toContain('图片转存中')
    expect(json.some(block => block.type === 'documentImage')).toBe(false)
    await act(async () => finish(asset))
    const image = json.find(block => block.type === 'documentImage')
    expect(image.props).toMatchObject({ imageAssetId: 92, caption: '架构图' })
    expect(image.props).not.toHaveProperty('url')
    expect(host.querySelector('.bn-pasted-image')).toBeNull()
    expect(json.findIndex(block => block.type === 'documentImage')).toBeGreaterThan(json.findIndex(block => JSON.stringify(block).includes('图片前')))
  })

  it('writes the original URL as plain text after one failed import and removes all paste feedback', async () => {
    const importImage = vi.fn().mockRejectedValue(new Error('HTTP 403'))
    await mount(importImage)
    await paste('<p>正文</p><img src="https://outside.example/blocked.png" alt="图">')
    expect(host.textContent).toContain('正文')
    expect(host.textContent).toContain('https://outside.example/blocked.png')
    expect(host.textContent).not.toContain('HTTP 403')
    expect(host.querySelector('.bn-pasted-image')).toBeNull()
    expect(host.querySelector('a[href="https://outside.example/blocked.png"]')).toBeNull()
    expect(importImage).toHaveBeenCalledOnce()
    expect(json.filter(block => JSON.stringify(block).includes('正文'))).toHaveLength(1)
    expect(json.filter(block => block.type === 'documentImage')).toHaveLength(0)
    expect(json.find(block => JSON.stringify(block).includes('blocked.png')).content)
      .toEqual([{ type: 'text', text: 'https://outside.example/blocked.png', styles: {} }])
  })

  it('attempts to read a local file URL and preserves its exact path after browser rejection', async () => {
    const fetchImage = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))
    vi.stubGlobal('fetch', fetchImage)
    const importImage = vi.fn(async (source: PastedImageSource, signal: AbortSignal) => {
      await readLocalPastedImage(source, signal)
      return asset
    })
    await mount(importImage)
    await paste('<p>正文</p><img src="file:///Users/me/chart.png" alt="本地图"><p>尾段</p>')
    expect(importImage).toHaveBeenCalledOnce()
    expect(fetchImage).toHaveBeenCalledWith('file:///Users/me/chart.png', { signal: expect.any(AbortSignal) })
    expect(host.textContent).toContain('尾段')
    expect(host.textContent).toContain('file:///Users/me/chart.png')
    expect(host.textContent).not.toContain('Failed to fetch')
    expect(host.querySelector('.bn-pasted-image')).toBeNull()
    expect(Array.from(host.querySelectorAll('button')).some(button => ['上传替换', '重试转存'].includes(button.textContent ?? ''))).toBe(false)
    expect(json.find(block => JSON.stringify(block).includes('file://')).content)
      .toEqual([{ type: 'text', text: 'file:///Users/me/chart.png', styles: {} }])
  })

  it('uses supplied clipboard file data for a local image while retaining its accompanying text', async () => {
    const file = new File(['image'], 'chart.png', { type: 'image/png' })
    const importImage = vi.fn().mockResolvedValue(asset)
    await mount(importImage)
    await paste('<p>正文</p><img src="file:///tmp/chart.png"><p>尾段</p>', '正文 尾段', [file])
    expect(importImage.mock.calls[0]![0]).toEqual({ kind: 'file', file })
    expect(host.textContent).toContain('尾段')
    expect(json.some(block => block.type === 'documentImage')).toBe(true)
  })

  it('retains the original path when an associated clipboard file fails to upload', async () => {
    const file = new File(['image'], 'chart.png', { type: 'image/png' })
    const importImage = vi.fn().mockRejectedValue(new Error('上传失败'))
    await mount(importImage)
    await paste('<p>正文</p><img src="file:///Users/me/图表/chart.png" alt="图"><p>尾段</p>', '', [file])
    expect(importImage.mock.calls[0]![0]).toEqual({ kind: 'file', file })
    expect(host.textContent).toContain('file:///Users/me/图表/chart.png')
    expect(host.textContent).toContain('尾段')
    expect(host.querySelector('.bn-pasted-image')).toBeNull()
  })

  it.each(['file:///Users/me/chart.png', 'C:/Users/me/chart.png'])('imports file-only clipboard images and writes %s on failure', async path => {
    const file = new File(['image'], 'chart.png', { type: 'image/png' })
    const importImage = vi.fn().mockRejectedValue(new Error('上传失败'))
    await mount(importImage)
    await paste('', path, [file])
    expect(importImage.mock.calls[0]![0]).toEqual({ kind: 'file', file })
    expect(host.textContent).toContain(path)
    expect(host.querySelector('.bn-pasted-image')).toBeNull()
  })

  it('imports internal resources by ID and isolates clone failure from external image success', async () => {
    const importImage = vi.fn(async (source: PastedImageSource) => {
      if (source.kind === 'asset') throw new Error('源文档无权限')
      return asset
    })
    await mount(importImage)
    await paste('<p>正文</p><div data-content-type="documentImage" data-image-asset-id="41"><img src="https://wrong.example/999"></div><img src="https://outside.example/good.png">')
    expect(importImage.mock.calls.map(call => call[0])).toEqual([{ kind: 'asset', assetId: 41 }, { kind: 'url', url: 'https://outside.example/good.png' }])
    expect(host.textContent).toContain('https://wrong.example/999')
    expect(host.textContent).not.toContain('源文档无权限')
    expect(json.filter(block => block.type === 'documentImage')).toHaveLength(1)
  })

  it('preserves Markdown tables and fenced image syntax while importing actual image tokens', async () => {
    const importImage = vi.fn().mockResolvedValue(asset)
    await mount(importImage)
    await paste('', '# 标题\n\n图片前 ![图][ref] 图片后\n\n[ref]: https://outside.example/a.png\n\n| 名称 | 图 |\n| --- | --- |\n| A | ![表格图](https://outside.example/b.png) |\n\n```md\n![代码](https://outside.example/code.png)\n```')
    expect(importImage).toHaveBeenCalledTimes(2)
    expect(json.some(block => block.type === 'table')).toBe(true)
    expect(json.some(block => block.type === 'codeBlock' && JSON.stringify(block).includes('![代码]'))).toBe(true)
    expect(host.textContent).toContain('图片后')
    expect(JSON.stringify(json)).not.toContain('linear-lite.invalid')
  })

  it('aborts a deleted placeholder and ignores its late result', async () => {
    let finish!: (value: typeof asset) => void
    let signal!: AbortSignal
    await mount((_source, value) => { signal = value; return new Promise(resolve => { finish = resolve }) })
    await paste('<p>保留正文</p><img src="https://outside.example/late.png" alt="待删除图">')
    await act(async () => { expect(editorApi.removeAttachmentLink('https://outside.example/late.png')).toBe(true) })
    expect(signal.aborted).toBe(true)
    await act(async () => finish(asset))
    expect(json.some(block => block.type === 'documentImage')).toBe(false)
    expect(host.textContent).toContain('保留正文')
  })

  it('undoes a pending paste and prevents a late image from reappearing', async () => {
    let finish!: (value: typeof asset) => void
    await mount(() => new Promise(resolve => { finish = resolve }))
    await paste('<p>正文</p><img src="https://outside.example/undo.png">')
    await act(async () => host.querySelector('.bn-editor')!.dispatchEvent(new KeyboardEvent('keydown', { key: 'z', code: 'KeyZ', ctrlKey: true, bubbles: true })))
    expect(host.querySelector('.bn-pasted-image')).toBeNull()
    await act(async () => finish(asset))
    expect(json.some(block => block.type === 'documentImage')).toBe(false)
  })
})

it('reads accessible blob, data, relative, file and Windows image paths and rejects invalid responses', async () => {
  const signal = new AbortController().signal
  const fetchImage = vi.fn().mockResolvedValue({ ok: true, blob: async () => new Blob(['png'], { type: 'image/png' }) })
  vi.stubGlobal('fetch', fetchImage)
  expect((await readLocalPastedImage({ kind: 'url', url: 'blob:http://localhost/a' }, signal)).type).toBe('image/png')
  expect((await readLocalPastedImage({ kind: 'url', url: 'data:image/png;base64,cG5n' }, signal)).size).toBe(3)
  expect((await readLocalPastedImage({ kind: 'url', url: 'file:///tmp/a.png' }, signal)).type).toBe('image/png')
  expect((await readLocalPastedImage({ kind: 'url', url: './images/a.png' }, signal)).type).toBe('image/png')
  expect((await readLocalPastedImage({ kind: 'url', url: 'C:\\images\\a.png' }, signal)).type).toBe('image/png')
  expect(fetchImage).toHaveBeenCalledWith('file:///C:/images/a.png', { signal })
  await expect(readLocalPastedImage({ kind: 'url', url: 'javascript:alert(1)' }, signal)).rejects.toThrow('协议')
  fetchImage.mockResolvedValueOnce({ ok: false })
  await expect(readLocalPastedImage({ kind: 'url', url: './missing.png' }, signal)).rejects.toThrow('读取失败')
  fetchImage.mockResolvedValueOnce({ ok: true, blob: async () => new Blob(['html'], { type: 'text/html' }) })
  await expect(readLocalPastedImage({ kind: 'url', url: './page.html' }, signal)).rejects.toThrow('图片内容无效')
  const prepared = prepareImagePaste({ markdown: '```md\n![code](https://outside.example/a.png)\n```' }, [])
  expect(prepared.sources.size).toBe(0)
})
