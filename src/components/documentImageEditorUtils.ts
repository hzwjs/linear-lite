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

export async function clonePastedDocumentImageAssets(
  html: string,
  resolveAsset: (assetId: number) => DocumentImageAsset | undefined,
  cloneAsset?: (assetId: number) => Promise<DocumentImageAsset>,
): Promise<{ html: string; clonedAssets: DocumentImageAsset[] }> {
  const parsed = new DOMParser().parseFromString(html, 'text/html')
  const clonedAssets: DocumentImageAsset[] = []
  const elements = Array.from(parsed.querySelectorAll(
    '[data-content-type="documentImage"][data-image-asset-id], img[data-image-asset-id]',
  ))
  const replacements = new Map<number, number>()
  for (const element of elements) {
    const assetId = Number(element.getAttribute('data-image-asset-id'))
    if (!Number.isInteger(assetId) || assetId <= 0) {
      throw new Error('Pasted document image has an invalid asset ID')
    }
    if (replacements.has(assetId)) continue
    if (resolveAsset(assetId)) {
      replacements.set(assetId, assetId)
      continue
    }
    if (!cloneAsset) throw new Error('Pasted document image cannot be copied')
    const cloned = await cloneAsset(assetId)
    if (!Number.isInteger(cloned.assetId) || cloned.assetId <= 0) {
      throw new Error('Cloned document image has an invalid asset ID')
    }
    replacements.set(assetId, cloned.assetId)
    clonedAssets.push(cloned)
  }
  for (const element of elements) {
    const assetId = Number(element.getAttribute('data-image-asset-id'))
    element.setAttribute('data-image-asset-id', String(replacements.get(assetId)))
  }
  return { html: parsed.body.innerHTML, clonedAssets }
}

export interface DocumentImageUploadResult {
  asset: DocumentImageAsset
  block: { type: 'documentImage'; props: { imageAssetId: number; caption: string } }
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
      props: { imageAssetId: asset.assetId, caption: '' },
    },
  }
}
