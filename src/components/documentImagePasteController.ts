import { Plugin, PluginKey } from '@tiptap/pm/state'
import { Decoration, DecorationSet } from '@tiptap/pm/view'
import type { DocumentImageAsset } from '../types/document'
import type { PastedImage, PastedImageSource } from './documentImagePaste'

export interface ImagePasteLabels {
  source: string
  importing: string
}

type Job = PastedImage & { state: 'queued' | 'importing'; controller: AbortController }

/** 装饰层呈现会话内预览；正文持有普通来源段落或完成后的 assetId。 */
export function createImagePasteController(editor: any, options: {
  labels: () => ImagePasteLabels
  importImage: (source: PastedImageSource, signal: AbortSignal) => Promise<DocumentImageAsset>
  registerAsset: (asset: DocumentImageAsset) => void
}) {
  const key = new PluginKey('document-image-paste')
  const jobs = new Map<string, Job>()
  let disposed = false
  let active = 0
  const valid = (job: Job) => {
    const block = editor.getBlock(job.blockId)
    return block?.type === 'paragraph' && JSON.stringify(block.content) === JSON.stringify(job.content)
  }
  function refresh() {
    if (!disposed && editor.prosemirrorView) editor.prosemirrorView.dispatch(editor.prosemirrorState.tr.setMeta(key, true))
  }
  function pump() {
    if (disposed) return
    for (const job of jobs.values()) {
      if (active >= 3) break
      if (job.state === 'queued' && valid(job)) void run(job)
    }
  }
  async function run(job: Job) {
    job.state = 'importing'
    active++
    refresh()
    try {
      const asset = await options.importImage(job.source, job.controller.signal)
      if (!Number.isInteger(asset.assetId) || asset.assetId <= 0) throw new Error('Imported document image has an invalid asset ID')
      if (disposed || jobs.get(job.blockId) !== job || !valid(job)) return
      jobs.delete(job.blockId)
      options.registerAsset(asset)
      editor.updateBlock(job.blockId, { type: 'documentImage', props: { imageAssetId: asset.assetId, caption: job.caption } })
    } catch {
      if (disposed || jobs.get(job.blockId) !== job || !valid(job)) return
      jobs.delete(job.blockId)
      // 失败结果是可编辑的原路径文本，取消会话预览与操作控件。
      editor.updateBlock(job.blockId, { type: 'paragraph', content: [{ type: 'text', text: job.pathText, styles: {} }] })
    } finally {
      active--
      refresh()
      pump()
    }
  }
  function render(job: Job) {
    const labels = options.labels()
    const root = document.createElement('div')
    root.className = 'bn-pasted-image'
    root.contentEditable = 'false'
    root.setAttribute('data-pasted-image-id', job.blockId)
    if (job.previewUrl) {
      const image = document.createElement('img')
      image.className = 'bn-pasted-image__preview'
      image.alt = job.caption
      image.referrerPolicy = 'no-referrer'
      image.src = job.previewUrl
      image.addEventListener('error', () => image.remove(), { once: true })
      root.append(image)
    }
    const status = document.createElement('div')
    status.className = 'bn-pasted-image__status'
    status.setAttribute('role', 'status')
    status.textContent = labels.importing
    root.append(status)
    return root
  }
  const plugin = new Plugin({
    key,
    props: {
      decorations(state) {
        const decorations: Decoration[] = []
        state.doc.descendants((node, position) => {
          if (node.type.name !== 'blockContainer') return
          const job = jobs.get(node.attrs.id)
          if (job) decorations.push(Decoration.widget(position + 1, () => render(job), {
            key: `${job.blockId}:${job.state}`,
            side: -1, ignoreSelection: true, stopEvent: () => true,
          }))
        })
        return DecorationSet.create(state.doc, decorations)
      },
    },
    view: () => ({
      update() {
        for (const [id, job] of jobs) if (!valid(job)) {
          jobs.delete(id)
          job.controller.abort()
        }
      },
    }),
  })
  editor._tiptapEditor.registerPlugin(plugin)
  return {
    add(images: PastedImage[]) {
      for (const image of images) {
        if (!valid({ ...image } as Job)) continue
        jobs.set(image.blockId, { ...image, state: 'queued', controller: new AbortController() })
      }
      refresh()
      pump()
    },
    dispose() {
      disposed = true
      for (const job of jobs.values()) job.controller.abort()
      jobs.clear()
      editor._tiptapEditor.unregisterPlugin(key)
    },
  }
}
