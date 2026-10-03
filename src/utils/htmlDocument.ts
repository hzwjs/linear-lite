export const HTML_DOCUMENT_MAX_BYTES = 5 * 1024 * 1024

export function htmlDocumentPath(projectId: number, documentId: number): string {
  return `/projects/${projectId}/documents/${documentId}/html`
}

export async function readHtmlDocumentFile(file: File): Promise<string> {
  if (!/\.html?$/i.test(file.name)) throw new Error('documents.html.invalidFile')
  if (file.size > HTML_DOCUMENT_MAX_BYTES) throw new Error('documents.html.tooLarge')
  try {
    const content = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(await file.arrayBuffer())
    if (content.trim().length === 0 || content.includes('\0')) throw new Error('invalid')
    return content
  } catch {
    throw new Error('documents.html.invalidEncoding')
  }
}

export function downloadHtmlDocument(title: string, content: string): void {
  const url = URL.createObjectURL(new Blob([content], { type: 'text/html;charset=utf-8' }))
  const link = document.createElement('a')
  link.href = url
  link.download = `${title.replace(/[\\/:*?"<>|]/g, '_')}.html`
  link.click()
  // 保留事件循环窗口，浏览器接收下载后释放资源。
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
