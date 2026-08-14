export function startDocumentPdfExport(title: string, onCleanup: () => void): () => void {
  const originalTitle = window.document.title
  let cleaned = false

  const cleanup = () => {
    if (cleaned) return
    cleaned = true
    window.removeEventListener('afterprint', cleanup)
    window.document.body.classList.remove('document-pdf-export')
    window.document.title = originalTitle
    onCleanup()
  }

  window.document.title = title
  window.document.body.classList.add('document-pdf-export')
  window.addEventListener('afterprint', cleanup, { once: true })
  window.setTimeout(() => {
    try {
      window.print()
    } catch {
      cleanup()
    }
  }, 0)

  return cleanup
}
