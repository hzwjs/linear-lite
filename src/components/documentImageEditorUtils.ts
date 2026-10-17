import { marked } from 'marked'
import type { DocumentImageAsset } from '../types/document'

/** Replaces the attachment name token; upload status text is shown in editor blocks. */
export function formatUploadFeedback(template: string | undefined, fileName: string, fallback: string): string {
  return template ? template.replace(/\{name\}/g, fileName) : fallback
}

export function isPastedImageFile(file: Pick<File, 'type'>): boolean {
  return file.type.startsWith('image/')
}

export function hasExternalImagePaste(html: string): boolean {
  if (!html) return false
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  return Array.from(parsed.querySelectorAll('img')).some((image) => {
    if (!image.getAttribute('src')) return false
    if (image.hasAttribute('data-image-asset-id')) return false
    return image.closest('[data-content-type="documentImage"][data-image-asset-id]') == null
  })
}

export function hasMarkdownImagePaste(markdown: string): boolean {
  let containsImage = false
  marked.walkTokens(marked.lexer(markdown), (token) => {
    if (token.type === 'image') containsImage = true
  })
  return containsImage
}

export function hasDocumentImageAssetPaste(html: string): boolean {
  if (!html) return false
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  return parsed.querySelector(
    '[data-content-type="documentImage"][data-image-asset-id], img[data-image-asset-id]',
  ) != null
}

export interface DocumentImageUploadResult {
  asset: DocumentImageAsset
  block: { type: 'documentImage'; props: { imageAssetId: number; altText: string; caption: string } }
}

export async function createDocumentImageUploadResult(
  file: File,
  uploadImageAsset: (file: File) => Promise<DocumentImageAsset>,
): Promise<DocumentImageUploadResult> {
  const asset = await uploadImageAsset(file)
  return {
    asset,
    block: {
      type: 'documentImage',
      props: { imageAssetId: asset.assetId, altText: file.name, caption: '' },
    },
  }
}
