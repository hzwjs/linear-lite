import Image from '@tiptap/extension-image'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'
import type { DOMOutputSpec } from '@tiptap/pm/model'

type UploadState = 'uploading' | 'failed' | 'uploaded' | 'loading-remote'

function parseUploadState(value: string | null | undefined): UploadState {
  if (value === 'uploading' || value === 'failed' || value === 'loading-remote') return value
  return 'uploaded'
}

/** 用静态 HTML 渲染图片节点，首帧即有占位，与文字同时出现；load/error 与上传态由 TiptapEditor 事件委托处理 */
export const TaskImage = Image.extend({
  name: 'taskImage',

  inline: true,
  group: 'inline',

  addOptions() {
    return {
      ...this.parent?.(),
      inline: true,
      onRetry: (_localId: string) => {},
      onRemove: (_localId: string) => {},
      getUploadingLabel: () => 'Uploading',
      getFailedLabel: () => 'Failed',
      getRetryLabel: () => 'Retry',
      getRemoveLabel: () => 'Remove',
    }
  },

  addAttributes() {
    return {
      ...this.parent?.(),
      localId: {
        default: null,
        parseHTML: (element: HTMLElement) => element.getAttribute('data-local-id'),
        renderHTML: (attrs: { localId?: string | null }) =>
          attrs.localId ? { 'data-local-id': attrs.localId } : {},
      },
      uploadState: {
        default: 'uploaded',
        parseHTML: (element: HTMLElement) => parseUploadState(element.getAttribute('data-upload-state')),
        renderHTML: (attrs: { uploadState?: UploadState }) => ({
          'data-upload-state': parseUploadState(attrs.uploadState),
        }),
      },
      errorMessage: {
        default: null,
        parseHTML: (element: HTMLElement) => element.getAttribute('data-error-message'),
        renderHTML: (attrs: { errorMessage?: string | null }) =>
          attrs.errorMessage ? { 'data-error-message': attrs.errorMessage } : {},
      },
    }
  },

  parseHTML() {
    return [
      {
        tag: 'span.task-image-node',
        getAttrs: (dom) => {
          const wrap = typeof dom === 'object' && dom instanceof HTMLElement ? dom : null
          const img = wrap?.querySelector('img')
          if (!img || !wrap) return false
          return {
            src: img.getAttribute('src'),
            alt: img.getAttribute('alt'),
            localId: wrap.getAttribute('data-local-id') ?? null,
            uploadState: parseUploadState(wrap.getAttribute('data-upload-state')),
            errorMessage: wrap.getAttribute('data-error-message') ?? null,
          }
        },
      },
    ]
  },

  renderHTML({
    node,
    HTMLAttributes,
  }: {
    HTMLAttributes: Record<string, unknown>
    node: ProseMirrorNode
  }): DOMOutputSpec {
    const src = (HTMLAttributes.src as string) ?? ''
    const alt = (HTMLAttributes.alt as string) ?? ''
    const uploadState = parseUploadState((node.attrs.uploadState as string) ?? 'uploaded')
    const isUploading = uploadState === 'uploading'
    const isFailed = uploadState === 'failed'
    const isRemoteLoading = uploadState === 'loading-remote'
    const spanAttrs: Record<string, string> = {
      class: `task-image-node${isUploading || isRemoteLoading ? ' task-image-node--loading' : ''}`,
      'data-src': src,
      'data-upload-state': uploadState,
    }
    if (node.attrs.localId) spanAttrs['data-local-id'] = String(node.attrs.localId)
    if (node.attrs.errorMessage) spanAttrs['data-error-message'] = String(node.attrs.errorMessage)

    const children: DOMOutputSpec[] = []
    if (isRemoteLoading) {
      children.push([
        'span',
        {
          class: 'task-image-node__placeholder',
          'aria-hidden': 'true',
          style: 'display:flex;align-items:center;justify-content:center;min-width:96px;min-height:64px;padding:8px 10px;border-radius:12px;background:linear-gradient(135deg, rgba(148, 163, 184, 0.16), rgba(148, 163, 184, 0.28));border:1px dashed rgba(100, 116, 139, 0.45);color:rgba(71, 85, 105, 0.9);font-size:12px;line-height:1.2;box-sizing:border-box;',
        },
        ['span', {}, alt || '图片加载中']
      ])
    }
    children.push([
      'img',
      isRemoteLoading
        ? {
            src,
            alt,
            class: 'task-image-node__image',
            'data-remote-image': 'true',
            style: 'display:none;',
          }
        : { src, alt, class: 'task-image-node__image' }
    ])
    const opts = this.options as unknown as Record<string, (() => string) | undefined>
    if (isUploading || isFailed) {
      const label = isUploading
        ? opts.getUploadingLabel?.() ?? 'Uploading'
        : opts.getFailedLabel?.() ?? 'Failed'
      const statusContent: DOMOutputSpec[] = [['span', {}, label]]
      if (isFailed) {
        const retryLabel = opts.getRetryLabel?.() ?? 'Retry'
        const removeLabel = opts.getRemoveLabel?.() ?? 'Remove'
        const localId = String(node.attrs.localId ?? '')
        statusContent.push(
          ['button', { type: 'button', class: 'task-image-node__action', 'data-action': 'retry', 'data-local-id': localId }, retryLabel],
          ['button', { type: 'button', class: 'task-image-node__action', 'data-action': 'remove', 'data-local-id': localId }, removeLabel]
        )
      }
      children.push([
        'div',
        { class: `task-image-node__status${isFailed ? ' failed' : ''}` },
        ['div', { class: 'task-image-node__status-main' }, ...statusContent],
      ])
    }
    return ['span', spanAttrs, ['span', { class: 'task-image-node__frame' }, ...children]]
  },
}).configure({
  inline: true,
})
