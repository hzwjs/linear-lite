import { createApp, defineComponent, h, nextTick } from 'vue'
import { createI18n } from 'vue-i18n'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import DocumentEditor from './DocumentEditor.vue'

const discardDraftMock = vi.fn()
const restoreDraftMock = vi.fn(() => 'restored')
const mockOffline = vi.hoisted(() => ({ enabled: false }))

vi.mock('../../composables/useDocumentCollaboration', async () => {
  const vue = await vi.importActual<typeof import('vue')>('vue')
  const Y = await vi.importActual<typeof import('yjs')>('yjs')
  return {
    useDocumentCollaboration: () => ({
      ydoc: vue.shallowRef(new Y.Doc()),
      previewDoc: vue.shallowRef(mockOffline.enabled ? new Y.Doc() : null),
      session: vue.shallowRef(mockOffline.enabled ? null : { user: { name: 'Ada', color: '#123456' }, sessionId: 'session-1', epoch: 'epoch-1' }),
      provider: vue.shallowRef(mockOffline.enabled ? null : { awareness: undefined }),
      hasSynced: vue.ref(true),
      authFailed: vue.ref(false),
      saveState: vue.ref('saved'),
      connectionState: vue.ref('connected'),
      collaborators: vue.ref([]),
      serverVersion: vue.ref(1),
      commitSeq: vue.ref(0),
      epochChanged: vue.ref(false),
      draftRestored: vue.ref(mockOffline.enabled),
      isLocallyCached: vue.ref(true),
      hasUnsavedBody: vue.ref(true),
      draftCacheFailed: vue.ref(false),
      discardDraft: discardDraftMock,
      restoreDraft: restoreDraftMock,
      start: vi.fn(),
      restartForEpoch: vi.fn(),
      retirePreview: vi.fn(),
    }),
  }
})

vi.mock('../StructuredDocumentEditor.vue', () => ({
  default: defineComponent({
    name: 'StructuredDocumentEditorStub',
    setup(_props, { expose }) {
      expose({ focus: vi.fn() })
      return () => h('div', { 'data-testid': 'document-body' })
    },
  }),
}))

const documentFixture = {
  id: 1,
  projectId: 7,
  parentDocumentId: null,
  contentFormat: 'BLOCKNOTE' as const,
  title: 'Untitled Document',
  content: '[]',
  imageAssets: [],
  creatorId: 1,
  lastEditorId: 1,
  archivedAt: null,
  createdAt: '2026-07-29T08:00:00',
  sortOrder: 0,
  version: 1,
  updatedAt: '2026-07-29T08:00:00',
  favorited: false,
}

const treeNodes = [{
  id: 1,
  projectId: 7,
  parentDocumentId: null,
  contentFormat: 'BLOCKNOTE' as const,
  title: 'Untitled Document',
  sortOrder: 0,
  version: 1,
  updatedAt: '2026-07-29T08:00:00',
  favorited: false,
}]

describe('DocumentEditor local cache and draft restoration', () => {
  beforeEach(() => {
    mockOffline.enabled = false
    vi.stubGlobal('ResizeObserver', class { observe() {} unobserve() {} disconnect() {} })
    document.body.innerHTML = ''
    discardDraftMock.mockClear()
    restoreDraftMock.mockClear()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    document.body.innerHTML = ''
  })

  function renderEditor(extraProps = {}) {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const onDiscardDraft = vi.fn()
    const onDismissDraftBanner = vi.fn()
    const app = createApp(DocumentEditor, {
      document: documentFixture,
      treeNodes,
      saveState: 'idle',
      conflictVersion: null,
      mentionMembers: [],
      mentionDocuments: [],
      onDiscardDraft,
      onDismissDraftBanner,
      ...extraProps,
    })
    app.use(createI18n({
      legacy: false,
      locale: 'en',
      messages: {
        en: {
          documents: {
            saveState: { idle: 'Not edited', saved: 'Saved' },
            collaboration: { saved: 'Saved' },
            draftRestoredTitle: 'Unsaved local draft restored',
            draftRestoredDescription: 'Draft restored from {time}',
            discardDraft: 'Discard draft',
            keepDraft: 'Keep draft',
            locallyCached: 'Cached locally',
            locallyCachedTooltip: 'Edits are cached locally',
            updatedTime: { justNow: 'just now' },
          },
        },
      },
      missingWarn: false,
      fallbackWarn: false,
    }))
    app.mount(host)
    return { host, app, onDiscardDraft, onDismissDraftBanner }
  }

  it('renders the draft restored banner when hasRestoredDraft is true', async () => {
    const { host } = renderEditor({
      hasRestoredDraft: true,
      restoredDraftTime: Date.now() - 3000,
    })
    await nextTick()

    const banner = host.querySelector('.document-editor__draft-banner')
    expect(banner).not.toBeNull()
    expect(banner?.textContent).toContain('Unsaved local draft restored')
  })

  it('emits discardDraft and calls collaborationState.discardDraft when discard button is clicked', async () => {
    const { host, onDiscardDraft } = renderEditor({
      hasRestoredDraft: true,
      restoredDraftTime: Date.now(),
    })
    await nextTick()

    const discardBtn = host.querySelector<HTMLButtonElement>('.document-editor__draft-banner button:first-of-type')
    expect(discardBtn).not.toBeNull()
    discardBtn?.click()
    await nextTick()

    expect(onDiscardDraft).toHaveBeenCalledOnce()
    expect(discardDraftMock).toHaveBeenCalledOnce()
    expect(host.querySelector('.document-editor__draft-banner')).toBeNull()
  })

  it('emits dismissDraftBanner and closes banner when keep draft button is clicked', async () => {
    const { host, onDismissDraftBanner } = renderEditor({
      hasRestoredDraft: true,
      restoredDraftTime: Date.now(),
    })
    await nextTick()

    const keepBtn = host.querySelector<HTMLButtonElement>('.document-editor__draft-keep-btn')
    expect(keepBtn).not.toBeNull()
    keepBtn?.click()
    await nextTick()

    expect(onDismissDraftBanner).toHaveBeenCalledOnce()
    expect(host.querySelector('.document-editor__draft-banner')).toBeNull()
  })

  it('displays the cached locally badge in toolbar', async () => {
    const { host } = renderEditor()
    await nextTick()

    const badge = host.querySelector('.document-editor__save-state--cached')
    expect(badge).not.toBeNull()
    expect(badge?.textContent).toContain('Cached locally')
  })

  it('renders the recovered body as a read-only preview when the session is offline', async () => {
    mockOffline.enabled = true
    const { host } = renderEditor()
    await nextTick()
    expect(host.querySelector('[data-testid="document-body"]')).not.toBeNull()
  })

  it('restores the selected snapshot and sends the same snapshot and owner to the title store', async () => {
    const entry = { draftId: 'other', draft: { documentId: 1, format: 'BLOCKNOTE', titleDirty: true, title: 'Draft', serverVersion: 1, savedAt: 1 } }
    const onRestoreDraftTitle = vi.fn()
    const { host } = renderEditor({ draftCandidates: [entry], draftOwnerId: 7, onRestoreDraftTitle })
    await nextTick()
    host.querySelector<HTMLButtonElement>('details.document-editor__conflict button')?.click()
    expect(restoreDraftMock).toHaveBeenCalledWith(entry.draft)
    expect(onRestoreDraftTitle).toHaveBeenCalledWith(entry, 7)
  })
})
