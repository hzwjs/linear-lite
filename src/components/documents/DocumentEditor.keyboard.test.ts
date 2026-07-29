import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DocumentEditor from './DocumentEditor.vue'

const focus = vi.fn()

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
