import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, describe, expect, it, vi } from 'vitest'
import DocumentEditor from './DocumentEditor.vue'

vi.mock('../StructuredDocumentEditor.vue', () => ({
  default: defineComponent({
    name: 'StructuredDocumentEditorStub',
    setup: () => () => h('div')
  })
}))

describe('DocumentEditor updated metadata', () => {
  afterEach(() => {
    vi.useRealTimers()
    document.body.replaceChildren()
  })

  it('shows the last editor and relative update time below the title', async () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-30T08:00:00'))
    const host = document.createElement('div')
    document.body.appendChild(host)
    const documentFixture = {
      id: 12,
      projectId: 7,
      parentDocumentId: null,
      title: 'Operations handbook',
      content: '[]',
      creatorId: 1,
      lastEditorId: 9,
      archivedAt: null,
      createdAt: '2026-07-20T08:00:00',
      sortOrder: 0,
      version: 3,
      updatedAt: '2026-07-28T08:00:00'
    }
    const app = createApp(DocumentEditor, {
      document: documentFixture,
      treeNodes: [documentFixture],
      saveState: 'idle',
      conflictVersion: null,
      mentionMembers: [{ id: 9, label: 'dubaoxiang' }],
      mentionDocuments: []
    })
    app.use(createI18n({
      legacy: false,
      locale: 'en',
      messages: {
        en: {
          documents: {
            updatedBy: '{name} updated {time}',
            updatedTime: { daysAgo: '{count} days ago' }
          }
        }
      },
      missingWarn: false,
      fallbackWarn: false
    }))
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.document-editor__updated')?.textContent)
      .toBe('dubaoxiang updated 2 days ago')
    app.unmount()
  })
})
