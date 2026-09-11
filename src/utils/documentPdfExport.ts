/**
 * 打印前把正文图片从缩略图切到原图并等待解码，避免 PDF 导出低清或空图。
 * 返回恢复函数；图片元素由编辑器管理，打印态结束后必须还原缩略图地址。
 */
export async function prepareDocumentImagesForPrint(
  root: ParentNode = window.document
): Promise<() => void> {
  const images = Array.from(root.querySelectorAll<HTMLImageElement>('img[data-original-url]'))
  const restorers: Array<() => void> = []
  for (const image of images) {
    const original = image.dataset.originalUrl
    if (!original) continue
    const previous = image.getAttribute('src')
    if (previous === original) continue
    image.setAttribute('src', original)
    restorers.push(() => {
      if (previous != null) image.setAttribute('src', previous)
      else image.removeAttribute('src')
    })
  }
  try {
    await Promise.all(images.map((image) =>
      typeof image.decode === 'function' ? image.decode() : Promise.resolve()
    ))
  } catch (error) {
    for (const restore of restorers) restore()
    throw error
  }
  return () => {
    for (const restore of restorers) restore()
  }
}

export function startDocumentPdfExport(title: string, onCleanup: () => void): () => void {
  const originalTitle = window.document.title
  let cleaned = false
  let restoreImages: (() => void) | null = null

  const cleanup = () => {
    if (cleaned) return
    cleaned = true
    window.removeEventListener('afterprint', cleanup)
    window.document.body.classList.remove('document-pdf-export')
    window.document.title = originalTitle
    restoreImages?.()
    restoreImages = null
    onCleanup()
  }

  window.document.title = title
  window.document.body.classList.add('document-pdf-export')
  window.addEventListener('afterprint', cleanup, { once: true })
  void prepareDocumentImagesForPrint()
    .then((restore) => {
      if (cleaned) {
        restore()
        return
      }
      restoreImages = restore
      window.setTimeout(() => {
        if (cleaned) return
        try {
          window.print()
        } catch {
          cleanup()
        }
      }, 0)
    })
    .catch(() => {
      if (!cleaned) {
        cleanup()
      }
    })

  return cleanup
}
