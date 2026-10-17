import { describe, expect, it, vi } from 'vitest'
import {
  createDocumentImageUploadResult,
  formatUploadFeedback,
  hasDocumentImageAssetPaste,
  hasExternalImagePaste,
  hasMarkdownImagePaste,
  isPastedImageFile,
} from './documentImageEditorUtils'

const asset = {
  assetId: 41,
  contentHash: 'sha256:asset',
  width: 640,
  height: 480,
  thumbnailUrl: '/thumbnail/41',
  originalUrl: '/attachment/41',
}

describe('document image resource flow', () => {
  it('turns image upload DTOs into documentImage blocks carrying only the resource ID', async () => {
    const file = new File(['image'], 'diagram.png', { type: 'image/png' })
    const upload = vi.fn().mockResolvedValue(asset)

    const result = await createDocumentImageUploadResult(file, upload)

    expect(upload).toHaveBeenCalledWith(file)
    expect(result.asset).toBe(asset)
    expect(result.block).toEqual({
      type: 'documentImage',
      props: { imageAssetId: 41, altText: 'diagram.png', caption: '' },
    })
    expect(JSON.stringify(result.block)).not.toContain('originalUrl')
  })

  it('distinguishes resource HTML from external images in mixed paste content', () => {
    const internalHtml = '<div data-content-type="documentImage" data-image-asset-id="41"><img data-image-asset-id="41" src="/rendered/41"></div>'
    const mixedHtml = `${internalHtml}<img src="https://outside.example/image.png">`

    expect(hasDocumentImageAssetPaste(internalHtml)).toBe(true)
    expect(hasExternalImagePaste(internalHtml)).toBe(false)
    expect(hasExternalImagePaste('<img data-image-asset-id="41" src="/rendered/41">')).toBe(false)
    expect(hasExternalImagePaste('<img src="https://outside.example/image.png">')).toBe(true)
    expect(hasExternalImagePaste(mixedHtml)).toBe(true)
  })

  it('uses Markdown tokens to catch inline and reference-style image syntax', () => {
    expect(hasMarkdownImagePaste('![diagram](https://outside.example/image.png)')).toBe(true)
    expect(hasMarkdownImagePaste('![diagram][diagram-ref]\n\n[diagram-ref]: https://outside.example/image.png')).toBe(true)
    expect(hasMarkdownImagePaste('| image |\n| --- |\n| ![diagram](https://outside.example/image.png) |')).toBe(true)
    expect(hasMarkdownImagePaste('![diagram]')).toBe(false)
    expect(hasMarkdownImagePaste('![diagram](https://outside.example/image.png) inside a code fence')).toBe(true)
    expect(hasMarkdownImagePaste('```md\n![diagram](https://outside.example/image.png)\n```')).toBe(false)
  })

  it('keeps existing upload status formatting and MIME classification', () => {
    expect(formatUploadFeedback('Uploading {name}…', 'guide.pdf', 'fallback')).toBe('Uploading guide.pdf…')
    expect(formatUploadFeedback(undefined, 'guide.pdf', 'Uploading guide.pdf…')).toBe('Uploading guide.pdf…')
    expect(isPastedImageFile(new File(['png'], 'diagram.png', { type: 'image/png' }))).toBe(true)
    expect(isPastedImageFile(new File(['pdf'], 'guide.pdf', { type: 'application/pdf' }))).toBe(false)
  })
})
