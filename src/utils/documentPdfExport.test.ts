import { afterEach, describe, expect, it, vi } from 'vitest'
import { prepareDocumentImagesForPrint, startDocumentPdfExport } from './documentPdfExport'

describe('documentPdfExport', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.classList.remove('document-pdf-export')
    document.body.replaceChildren()
    vi.restoreAllMocks()
  })

  it('restores thumbnails and rejects when an original image cannot be decoded', async () => {
    const image = document.createElement('img')
    image.src = '/thumbnail.jpg'
    image.dataset.originalUrl = '/original.jpg'
    image.decode = vi.fn().mockRejectedValue(new Error('decode failed'))
    document.body.appendChild(image)

    await expect(prepareDocumentImagesForPrint(document)).rejects.toThrow('decode failed')

    expect(image.getAttribute('src')).toContain('/thumbnail.jpg')
  })

  it('cancels printing and clears export state when original decoding fails', async () => {
    vi.useFakeTimers()
    const print = vi.spyOn(window, 'print').mockImplementation(() => {})
    const image = document.createElement('img')
    image.src = '/thumbnail.jpg'
    image.dataset.originalUrl = '/original.jpg'
    image.decode = vi.fn().mockRejectedValue(new Error('decode failed'))
    document.body.appendChild(image)

    startDocumentPdfExport('Document', () => {})
    await vi.runAllTimersAsync()

    expect(print).not.toHaveBeenCalled()
    expect(document.body.classList.contains('document-pdf-export')).toBe(false)
    expect(image.getAttribute('src')).toContain('/thumbnail.jpg')
  })
})
