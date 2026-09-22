import { createApp, defineComponent, h, onMounted, ref } from 'vue'
import StructuredDocumentEditor from '../../src/components/StructuredDocumentEditor.vue'
import { i18n } from '../../src/i18n'
import '../../src/style.css'
import type { DocumentImageAsset } from '../../src/types/document'

const documentId = 901111
const appRoot = document.querySelector<HTMLElement>('#app')
if (!appRoot) throw new Error('Harness root was not found')

const app = createApp(defineComponent({
  setup() {
    const body = ref('')
    const imageAssets = ref<DocumentImageAsset[]>([])
    const mountKey = ref(0)
    const stateText = ref('正在读取本地 mock 文档')
    let saveTail: Promise<void> = Promise.resolve()

    async function readDocument() {
      const response = await fetch(`/api/__harness/documents/${documentId}`)
      if (!response.ok) throw new Error(`Mock document read returned ${response.status}`)
      const document = await response.json() as { content: string; imageAssets: DocumentImageAsset[] }
      body.value = document.content
      imageAssets.value = document.imageAssets
      mountKey.value++
      stateText.value = '已用本地 mock 文档内容和图片资源清单挂载'
    }

    function handleModelValue(value: string) {
      body.value = value
      saveTail = saveTail.catch(() => {}).then(async () => {
        const response = await fetch(`/api/__harness/documents/${documentId}/content`, {
          method: 'PUT',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ content: value }),
        })
        if (!response.ok) throw new Error(`Mock document save returned ${response.status}`)
      }).catch((error: unknown) => {
        stateText.value = error instanceof Error ? error.message : String(error)
      })
    }

    onMounted(() => {
      void readDocument().catch((error: unknown) => {
        stateText.value = error instanceof Error ? error.message : String(error)
      })
    })

    Object.defineProperty(window, '__linearLite111', {
      configurable: true,
      value: {
        documentId,
        get modelValue() { return body.value },
        get imageAssets() { return imageAssets.value },
        waitForSave: () => saveTail,
        reloadDocument: readDocument,
      },
    })

    return () => h('main', { class: 'll111-harness' }, [
      h('header', { class: 'll111-header' }, [
        h('div', [
          h('p', { class: 'll111-eyebrow' }, '本地浏览器验收 / LINEAR-LITE-111'),
          h('h1', '文档图片资源链路'),
          h('p', { id: 'harness-state', role: 'status', 'aria-live': 'polite' }, stateText.value),
        ]),
        h('button', {
          id: 'reload-mock-document',
          type: 'button',
          onClick: () => void readDocument().catch((error: unknown) => {
            stateText.value = error instanceof Error ? error.message : String(error)
          }),
        }, '从 mock 文档重挂载'),
      ]),
      h('section', { class: 'll111-editor-panel', 'aria-label': '文档编辑器' }, [
        h(StructuredDocumentEditor, {
          key: mountKey.value,
          modelValue: body.value,
          documentId,
          imageAssets: imageAssets.value,
          pasteFileAsLink: true,
          'onUpdate:modelValue': handleModelValue,
        }),
      ]),
      h('details', { class: 'll111-json-panel', open: true }, [
        h('summary', '当前文档正文 JSON'),
        h('pre', { id: 'document-json', 'data-testid': 'document-json' }, body.value),
      ]),
    ])
  },
}))

app.use(i18n)
app.mount(appRoot)

declare global {
  interface Window {
    __linearLite111?: {
      documentId: number
      modelValue: string
      imageAssets: DocumentImageAsset[]
      waitForSave: () => Promise<void>
      reloadDocument: () => Promise<void>
    }
  }
}

