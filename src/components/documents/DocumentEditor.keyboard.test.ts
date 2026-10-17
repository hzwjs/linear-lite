import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DocumentEditor from './DocumentEditor.vue'

vi.mock('../../composables/useDocumentCollaboration', async () => {
  const vue = await vi.importActual<typeof import('vue')>('vue')
  const Y = await vi.importActual<typeof import('yjs')>('yjs')
  return { useDocumentCollaboration: () => ({
    ydoc: vue.shallowRef(new Y.Doc()), previewDoc: vue.shallowRef(null),
    session: vue.shallowRef({ user: { name: 'Ada', color: '#123456' }, sessionId: 'session-1', epoch: 'epoch-1' }),
    provider: vue.shallowRef({ awareness: undefined }), hasSynced: vue.ref(true), authFailed: vue.ref(false),
    saveState: vue.ref('saved'), connectionState: vue.ref('connected'), collaborators: vue.ref([]),
    serverVersion: vue.ref(1), commitSeq: vue.ref(0), epochChanged: vue.ref(false),
    start: vi.fn(), restartForEpoch: vi.fn(), retirePreview: vi.fn(),
  }) }
})

const focus = vi.fn()

beforeEach(() => { vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} }) })
afterEach(() => { vi.unstubAllGlobals() })

vi.mock('../StructuredDocumentEditor.vue', () => ({
  default: defineComponent({
    name: 'StructuredDocumentEditorStub',
    setup(_props, { expose }) {
      expose({ focus })
      return () => h('div', { 'data-testid': 'document-body' })
    }
  })
}))

const documentFixture = {
  id: 1,
  projectId: 7,
  parentDocumentId: null,
  title: 'Untitled',
  content: '[]',
  creatorId: 1,
  lastEditorId: 1,
  archivedAt: null,
  createdAt: '2026-07-29T08:00:00',
  sortOrder: 0,
  version: 1,
  updatedAt: '2026-07-29T08:00:00'
}

const treeNodes = [{
  id: 1,
  projectId: 7,
  parentDocumentId: null,
  title: 'Untitled',
  sortOrder: 0,
  version: 1,
  updatedAt: '2026-07-29T08:00:00'
}]

function renderEditor() {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(DocumentEditor, {
    document: documentFixture,
    treeNodes,
    saveState: 'idle',
    conflictVersion: null,
    mentionMembers: [],
    mentionDocuments: []
  })
  app.use(createI18n({
    legacy: false,
    locale: 'en',
    messages: { en: {} },
    missingWarn: false,
    fallbackWarn: false
  }))
  app.mount(host)
  return { app, host }
}

afterEach(() => {
  focus.mockClear()
  document.body.replaceChildren()
})

describe('DocumentEditor title keyboard behavior', () => {
  it('moves focus into the body when Enter is pressed in the title', async () => {
    const view = renderEditor()
    await nextTick()
    const title = view.host.querySelector<HTMLInputElement>('.document-editor__title')
    expect(title).not.toBeNull()

    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    title?.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
    expect(focus).toHaveBeenCalledOnce()
    view.app.unmount()
  })
})
