export const MAX_EDITOR_IMAGE_SIZE_BYTES = 10 * 1024 * 1024
export const SUPPORTED_EDITOR_IMAGE_MIME_TYPES = [
  'image/png',
  'image/jpeg',
  'image/webp',
  'image/gif'
] as const
export const SUPPORTED_EDITOR_IMAGE_TYPES = ['png', 'jpg', 'jpeg', 'webp', 'gif'] as const
export const UPLOADED_IMAGE_STATE = 'uploaded'
export const UPLOADING_IMAGE_STATE = 'uploading'
export const FAILED_IMAGE_STATE = 'failed'

export interface EditorUploadStateSummary {
  hasPending: boolean
  hasFailed: boolean
}

export type EditorImageValidationResult =
  | { ok: true }
  | { ok: false; message: string }

export function validateEditorImageFile(file: File): EditorImageValidationResult {
  if (!SUPPORTED_EDITOR_IMAGE_MIME_TYPES.includes(file.type as (typeof SUPPORTED_EDITOR_IMAGE_MIME_TYPES)[number])) {
    return {
      ok: false,
      message: `仅支持 ${SUPPORTED_EDITOR_IMAGE_TYPES.join(' / ')} 图片`
    }
  }
  if (file.size > MAX_EDITOR_IMAGE_SIZE_BYTES) {
    return {
      ok: false,
      message: '图片大小不能超过 10MB'
    }
  }
  return { ok: true }
}

export function buildImageMarkdown(url: string): string {
  return `![image](${url})`
}

export function serializeEditorHtmlForSave(html: string): string {
  if (typeof document === 'undefined') return html
  const doc = new DOMParser().parseFromString(html, 'text/html')
  doc.querySelectorAll('img[data-upload-state]').forEach((img) => {
    const state = img.getAttribute('data-upload-state')
    if (state !== UPLOADED_IMAGE_STATE) {
      img.remove()
    } else {
      img.removeAttribute('data-local-id')
      img.removeAttribute('data-upload-state')
      img.removeAttribute('data-error-message')
    }
  })
  return doc.body.innerHTML
}

export function getEditorUploadStateSummaryFromHtml(html: string): EditorUploadStateSummary {
  if (typeof document === 'undefined') return { hasPending: false, hasFailed: false }
  const doc = new DOMParser().parseFromString(html, 'text/html')
  const images = Array.from(doc.querySelectorAll('img[data-upload-state]'))
  return {
    hasPending: images.some((img) => img.getAttribute('data-upload-state') === UPLOADING_IMAGE_STATE),
    hasFailed: images.some((img) => img.getAttribute('data-upload-state') === FAILED_IMAGE_STATE),
  }
}
