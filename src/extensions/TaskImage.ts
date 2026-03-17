import Image from '@tiptap/extension-image'
import { VueNodeViewRenderer } from '@tiptap/vue-3'
import type { Node as ProseMirrorNode } from '@tiptap/pm/model'
import TaskImageNodeView from '../components/TaskImageNodeView.vue'

type UploadState = 'uploading' | 'failed' | 'uploaded'

function parseUploadState(value: string | null | undefined): UploadState {
  if (value === 'uploading' || value === 'failed') return value
  return 'uploaded'
}

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

  renderHTML({ HTMLAttributes }: { HTMLAttributes: Record<string, unknown>; node: ProseMirrorNode }) {
    return ['img', HTMLAttributes]
  },

  addNodeView() {
    return VueNodeViewRenderer(TaskImageNodeView)
  },
}).configure({
  inline: true,
})
