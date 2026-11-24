import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import { i18n } from '../i18n'
import { documentApi } from '../services/api/documents'
import { documentCollaborationApi } from '../services/api/documentCollaboration'
import { useAuthStore } from '../store/authStore'
import { documentDraftId, readDocumentDraft, saveDocumentDraft, type DocumentLocalDraft } from '../utils/documentDraftStorage'
import type { ProjectDocument } from '../types/document'
import HtmlDocumentView from './HtmlDocumentView.vue'

const restoreDraft = vi.hoisted(() => vi.fn())
vi.mock('../components/documents/HtmlDocumentPreview.vue', () => ({ default: { props: ['content', 'projectId'], template: '<div class="preview-stub">{{ content }}</div>' } }))
vi.mock('../components/documents/DocumentHistoryPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/documents/HtmlDocumentCollaboration.vue', () => ({ default: {
  props: ['documentId', 'visible', 'ariaLabel', 'placeholder', 'initialSession'], emits: ['content', 'status'],
  template: '<textarea class="collab-source" :placeholder="placeholder" @input="$emit(\'content\', $event.target.value)" />',
  methods: { restoreDraft(draft: DocumentLocalDraft, options?: unknown) { return restoreDraft(draft, options) } },
  async mounted() {
    const session = await (this.initialSession ?? documentCollaborationApi.createSession(this.documentId))
    const doc = new Y.Doc()
    Y.applyUpdate(doc, Uint8Array.from(atob(session.stateBase64), character => character.charCodeAt(0)))
    this.$emit('content', doc.getText('html').toString())
    doc.destroy()
    this.$emit('status', { saveState: 'saved', connectionState: 'connected', hasSynced: true, collaborators: [], version: 1, commitSeq: 0 })
  }
} }))
vi.mock('../services/api/documents', async () => {
  const actual = await vi.importActual<typeof import('../services/api/documents')>('../services/api/documents')
  return { ...actual, documentApi: { ...actual.documentApi, get: vi.fn(), create: vi.fn(), update: vi.fn() } }
})
vi.mock('../services/api/documentCollaboration', () => ({
  documentCollaborationApi: { createSession: vi.fn(), updateTitle: vi.fn() }
}))

const htmlDocument = (patch: Partial<ProjectDocument> = {}): ProjectDocument => ({
  id: 81, projectId: 3, parentDocumentId: null, title: '交互文档', contentFormat: 'HTML',
  content: '\n<!doctype html><html><body><script>window.x=1</script></body></html>\n',
  imageAssets: [], sortOrder: 0, version: 1, favorited: false, creatorId: 7, lastEditorId: 7,
  archivedAt: null, createdAt: '2026-10-03T10:00:00', updatedAt: '2026-10-03T10:00:00', ...patch
})
const apps: App[] = []
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick(); await nextTick() }
async function mount(path = '/projects/3/documents/81/html') {
  const router = createRouter({ history: createMemoryHistory(), routes: [
    { path: '/projects/:projectId/documents/html/new', name: 'html-document-create', component: HtmlDocumentView },
    { path: '/projects/:projectId/documents/:documentId/html', name: 'html-document-read', component: HtmlDocumentView }
  ] })
  await router.push(path)
  const host = document.createElement('div'); document.body.append(host)
  const app = createApp({ template: '<router-view />' }); apps.push(app)
  const pinia = createPinia()
  app.use(pinia).use(router).use(i18n).mount(host)
  await flush()
  return { host, router, auth: useAuthStore(pinia) }
}
const button = (host: HTMLElement, text: string) => [...host.querySelectorAll('button')].find((item) => item.textContent === text)!
async function selectDraft(host: HTMLElement, draftId: string) {
  const trigger = [...host.querySelectorAll('button')].find((item) => item.textContent?.includes('Local drafts ('))!
  trigger.click(); await flush()
  const select = host.querySelector('select')!
  select.value = draftId
  select.dispatchEvent(new Event('change', { bubbles: true }))
  await flush()
}
function input(node: HTMLInputElement | HTMLTextAreaElement, value: string) { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })) }
function testToken(userId: number) { return `header.${btoa(JSON.stringify({ sub: String(userId) }))}.signature` }
function replaceStoredAccount(userId: number) {
  localStorage.setItem('linear_lite_jwt', testToken(userId))
  localStorage.setItem('linear_lite_user', JSON.stringify({ id: userId, username: 'Other' }))
}

describe('HTML document source and publishing', () => {
  beforeEach(() => {
    document.body.innerHTML = ''; localStorage.clear(); vi.clearAllMocks(); i18n.global.locale.value = 'en'
    restoreDraft.mockReturnValue('restored')
    localStorage.setItem('linear_lite_jwt', testToken(7))
    localStorage.setItem('linear_lite_user', JSON.stringify({ id: 7, username: 'Ada' }))
    vi.mocked(documentApi.get).mockResolvedValue(htmlDocument())
    const checkpoint = new Y.Doc()
    checkpoint.getText('html').insert(0, htmlDocument().content)
    const stateBase64 = btoa(String.fromCharCode(...Y.encodeStateAsUpdate(checkpoint)))
    checkpoint.destroy()
    vi.mocked(documentCollaborationApi.createSession).mockResolvedValue({
      documentId: 81, epoch: 'epoch-1', roomName: 'doc:81:epoch:epoch-1', websocketUrl: 'ws://test',
      roomToken: 'token', user: { id: 7, name: 'Ada', color: '#123456' }, sessionId: 'session-1',
      expiresAt: new Date(Date.now() + 3_600_000).toISOString(), version: 1, commitSeq: 0,
      contentFormat: 'HTML', stateBase64,
    })
    vi.mocked(documentCollaborationApi.updateTitle).mockReset()
  })
  afterEach(() => { apps.splice(0).forEach((app) => app.unmount()); vi.useRealTimers(); vi.restoreAllMocks() })

  it('does not restore another account’s title draft', async () => {
    saveDocumentDraft(8, 'other', { documentId: 81, format: 'HTML', title: 'Other account', titleDirty: true, serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe('交互文档')
    expect(host.textContent).not.toContain('Other account')
  })

  it('restores only the explicitly selected owner draft and preserves the source', async () => {
    saveDocumentDraft(7, 'first', { documentId: 81, format: 'HTML', title: 'First title', titleDirty: true, serverVersion: 1, savedAt: Date.now() - 1 })
    saveDocumentDraft(7, 'second', { documentId: 81, format: 'HTML', title: 'Restored title', titleDirty: true, serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe('交互文档')
    await selectDraft(host, 'second')
    button(host, 'Restore draft').click(); await flush()
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe('Restored title')
    expect(host.querySelector('.preview-stub')?.textContent).toBe(htmlDocument().content)
    expect(restoreDraft).toHaveBeenCalledWith(expect.objectContaining({ title: 'Restored title' }), { sourceDraftId: 'second', force: false })
    expect(readDocumentDraft(7, 81, 'first')?.title).toBe('First title')
    expect(readDocumentDraft(7, 81, 'second')?.title).toBe('Restored title')
  })

  it.each(['', '   '])('preserves an invalid title %j for recovery without allowing submission', async (draftTitle) => {
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: draftTitle, titleDirty: true, serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    await selectDraft(host, 'source')
    button(host, 'Restore draft').click(); await flush()
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe(draftTitle)
    expect(button(host, 'Save').disabled).toBe(true)
    button(host, 'Save').click(); await flush()
    expect(documentCollaborationApi.updateTitle).not.toHaveBeenCalled()
    window.dispatchEvent(new Event('beforeunload', { cancelable: true }))
    expect(readDocumentDraft(7, 81, documentDraftId)).toMatchObject({ titleDirty: true, title: draftTitle })
    expect(readDocumentDraft(7, 81, 'source')).toMatchObject({ titleDirty: true, title: draftTitle })
  })

  it('preserves an old-epoch draft and allows viewing its source without merging', async () => {
    const doc = new Y.Doc()
    doc.getText('html').insert(0, '<p>Prior draft</p>')
    const update = btoa(String.fromCharCode(...Y.encodeStateAsUpdate(doc)))
    doc.destroy()
    saveDocumentDraft(7, 'old', { documentId: 81, format: 'HTML', title: 'Old title', titleDirty: true, ydocUpdate: update, ydocEpoch: 'old-epoch', serverVersion: 1, savedAt: Date.now() })
    restoreDraft.mockReturnValueOnce('epoch-mismatch')
    const { host } = await mount()
    await selectDraft(host, 'old')
    button(host, 'Restore draft').click(); await flush()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('different document version')
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe('交互文档')
    expect(readDocumentDraft(7, 81, 'old')?.ydocUpdate).toBe(update)
    button(host, 'View draft').click(); await flush()
    const preview = host.querySelector('textarea[aria-label="Local draft source"]') as HTMLTextAreaElement
    expect(preview.readOnly).toBe(true)
    expect(preview.value).toBe('<p>Prior draft</p>')
  })

  it('requires choosing again if a source draft changed after selection', async () => {
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: 'First title', titleDirty: true, serverVersion: 1, savedAt: 1 })
    const { host } = await mount()
    await selectDraft(host, 'source')
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: 'New title', titleDirty: true, serverVersion: 1, savedAt: 2 })
    button(host, 'Restore draft').click(); await flush()
    expect(restoreDraft).not.toHaveBeenCalled()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('Select it again')
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe('交互文档')
  })

  it('rejects recovery when another tab changes the stored account after selection', async () => {
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: 'Private draft', titleDirty: true, serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    await selectDraft(host, 'source')
    replaceStoredAccount(8)
    button(host, 'Restore draft').click(); await flush()
    expect(restoreDraft).not.toHaveBeenCalled()
    expect(readDocumentDraft(7, 81, 'source')?.title).toBe('Private draft')
    expect(readDocumentDraft(8, 81, documentDraftId)).toBeNull()
  })

  it('does not replace unsaved edits in the current tab', async () => {
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: 'Draft title', titleDirty: true, serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    await selectDraft(host, 'source')
    input(host.querySelector('input:not([type=file])')!, 'Current edit')
    button(host, 'Restore draft').click(); await flush()
    expect(restoreDraft).not.toHaveBeenCalled()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('Save the changes')
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe('Current edit')
  })

  it('keeps the Yjs draft when saving a title', async () => {
    const update = 'dGVzdA=='
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: 'Changed title', titleDirty: true, ydocUpdate: update, ydocEpoch: 'epoch-1', serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    await selectDraft(host, 'source')
    button(host, 'Restore draft').click(); await flush()
    saveDocumentDraft(7, documentDraftId, { documentId: 81, format: 'HTML', titleDirty: true, title: 'Changed title', ydocUpdate: update, ydocEpoch: 'epoch-1', serverVersion: 1, savedAt: Date.now() })
    vi.mocked(documentCollaborationApi.updateTitle).mockResolvedValue({
      title: 'Changed title', version: 2, updatedAt: '2026-10-17T08:00:00', lastEditorId: 7
    })
    button(host, 'Save').click(); await flush()
    expect(readDocumentDraft(7, 81, documentDraftId)).toMatchObject({ titleDirty: false, ydocUpdate: update, ydocEpoch: 'epoch-1', serverVersion: 2 })
    expect(readDocumentDraft(7, 81, documentDraftId)?.title).toBeUndefined()
    // 源草稿包含未确认正文，标题落盘后清除其 titleDirty 但保留正文更新，直到正文保存确认后再清理。
    expect(readDocumentDraft(7, 81, 'source')).toMatchObject({ titleDirty: false, ydocUpdate: update, ydocEpoch: 'epoch-1' })
  })

  it('clears a reverted title while retaining the Yjs draft', async () => {
    const update = 'dGVzdA=='
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: 'Changed title', titleDirty: true, ydocUpdate: update, ydocEpoch: 'epoch-1', serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    await selectDraft(host, 'source')
    button(host, 'Restore draft').click(); await flush()
    saveDocumentDraft(7, documentDraftId, { documentId: 81, format: 'HTML', title: 'Changed title', titleDirty: true, ydocUpdate: update, ydocEpoch: 'epoch-1', serverVersion: 1, savedAt: Date.now() })
    input(host.querySelector('input:not([type=file])')!, '交互文档')
    await flush()
    expect(readDocumentDraft(7, 81, documentDraftId)).toMatchObject({ titleDirty: false, ydocUpdate: update })
    expect(readDocumentDraft(7, 81, documentDraftId)?.title).toBeUndefined()
  })

  it('retains the server title for a body-only Yjs draft', async () => {
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', titleDirty: false, ydocUpdate: 'dGVzdA==', ydocEpoch: 'epoch-1', serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe('交互文档')
    expect(readDocumentDraft(7, 81, 'source')).toMatchObject({ titleDirty: false, ydocUpdate: 'dGVzdA==' })
  })

  it('does not alter an unselected source draft matching the server title', async () => {
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: '交互文档', titleDirty: true, ydocUpdate: 'dGVzdA==', ydocEpoch: 'epoch-1', serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    expect((host.querySelector('input:not([type=file])') as HTMLInputElement).value).toBe('交互文档')
    expect(readDocumentDraft(7, 81, 'source')).toMatchObject({ titleDirty: true, title: '交互文档', ydocUpdate: 'dGVzdA==' })
  })

  it('does not render a draft when the document request is forbidden', async () => {
    saveDocumentDraft(7, 'source', { documentId: 81, format: 'HTML', title: 'Private draft', titleDirty: true, serverVersion: 1, savedAt: Date.now() })
    vi.mocked(documentApi.get).mockRejectedValue({ response: { status: 403 } })
    const { host } = await mount()
    expect(host.querySelector('.preview-stub')).toBeNull()
    expect(host.textContent).not.toContain('Private draft')
    expect(host.querySelector('[role=alert]')).not.toBeNull()
  })

  it('rejects a previous account’s delayed document response', async () => {
    let resolvePrevious!: (value: ProjectDocument) => void
    vi.mocked(documentApi.get)
      .mockReturnValueOnce(new Promise((resolve) => { resolvePrevious = resolve }))
      .mockRejectedValue({ response: { status: 403 } })
    const { host, auth } = await mount()
    auth.currentUser = { id: 8, username: 'Other' }
    await flush()
    resolvePrevious(htmlDocument({ title: 'Previous account' }))
    await flush()
    expect(host.textContent).not.toContain('Previous account')
    expect(host.querySelector('.preview-stub')).toBeNull()
  })

  it('cancels pending title writes when the account changes', async () => {
    const { host, auth } = await mount()
    vi.useFakeTimers()
    input(host.querySelector('input:not([type=file])')!, 'Private title')
    vi.mocked(documentApi.get).mockRejectedValue({ response: { status: 403 } })
    auth.currentUser = { id: 8, username: 'Other' }
    await flush()
    await vi.advanceTimersByTimeAsync(500)
    expect(readDocumentDraft(8, 81, documentDraftId)).toBeNull()
  })

  it('flushes a pending title when the page unmounts before the debounce', async () => {
    const { host } = await mount()
    vi.useFakeTimers()
    input(host.querySelector('input:not([type=file])')!, 'Pending title')
    expect(readDocumentDraft(7, 81, documentDraftId)).toBeNull()
    apps.splice(0).forEach((app) => app.unmount())
    expect(readDocumentDraft(7, 81, documentDraftId)).toMatchObject({ titleDirty: true, title: 'Pending title' })
  })

  it('flushes a pending title during beforeunload', async () => {
    const { host } = await mount()
    vi.useFakeTimers()
    input(host.querySelector('input:not([type=file])')!, 'Pending title')
    window.dispatchEvent(new Event('beforeunload', { cancelable: true }))
    expect(readDocumentDraft(7, 81, documentDraftId)).toMatchObject({ titleDirty: true, title: 'Pending title' })
  })

  it('does not claim a failed local title write succeeded', async () => {
    const { host } = await mount()
    vi.useFakeTimers()
    const setItem = vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('Quota exceeded', 'QuotaExceededError') })
    input(host.querySelector('input:not([type=file])')!, 'Pending title')
    await vi.advanceTimersByTimeAsync(400)
    expect(readDocumentDraft(7, 81, documentDraftId)).toBeNull()
    expect(host.querySelector('.html-document__status')?.textContent).toContain('Unsaved')
    setItem.mockRestore()
  })

  it('does not write a pending draft after another tab changes the stored session', async () => {
    const { host } = await mount()
    vi.useFakeTimers()
    input(host.querySelector('input:not([type=file])')!, 'Private title')
    replaceStoredAccount(8)
    await vi.advanceTimersByTimeAsync(500)
    expect(readDocumentDraft(7, 81, documentDraftId)).toBeNull()
    expect(readDocumentDraft(8, 81, documentDraftId)).toBeNull()
  })

  it('does not clear a title draft after another tab changes the stored session', async () => {
    saveDocumentDraft(7, documentDraftId, { documentId: 81, format: 'HTML', title: 'Private title', titleDirty: true, ydocUpdate: 'dGVzdA==', ydocEpoch: 'epoch-1', serverVersion: 1, savedAt: Date.now() })
    const { host } = await mount()
    input(host.querySelector('input:not([type=file])')!, 'New title')
    replaceStoredAccount(8)
    input(host.querySelector('input:not([type=file])')!, '交互文档')
    await flush()
    expect(readDocumentDraft(7, 81, documentDraftId)).toMatchObject({ title: 'Private title', titleDirty: true, ydocUpdate: 'dGVzdA==' })
  })

  it('rejects a document response after another tab changes the stored session', async () => {
    let resolvePrevious!: (value: ProjectDocument) => void
    vi.mocked(documentApi.get).mockReturnValueOnce(new Promise((resolve) => { resolvePrevious = resolve }))
    const { host } = await mount()
    replaceStoredAccount(8)
    resolvePrevious(htmlDocument({ title: 'Previous account' }))
    await flush()
    expect(host.textContent).not.toContain('Previous account')
    expect(host.querySelector('.preview-stub')).toBeNull()
  })

  it('rejects a title-save response after another tab changes the stored session', async () => {
    let resolveSave!: (value: Awaited<ReturnType<typeof documentCollaborationApi.updateTitle>>) => void
    vi.mocked(documentCollaborationApi.updateTitle).mockReturnValueOnce(new Promise((resolve) => { resolveSave = resolve }))
    const { host } = await mount()
    input(host.querySelector('input:not([type=file])')!, 'Private title')
    await flush()
    button(host, 'Save').click()
    expect(documentCollaborationApi.updateTitle).toHaveBeenCalledTimes(1)
    replaceStoredAccount(8)
    resolveSave({ title: 'Private title', version: 2, updatedAt: '2026-10-17T08:00:00', lastEditorId: 7 })
    await flush()
    expect(host.textContent).not.toContain('Private title')
  })

  it('rejects collaborative content after another tab changes the stored session', async () => {
    const { host } = await mount()
    button(host, 'Source').click(); await flush()
    const source = host.querySelector('.collab-source') as HTMLTextAreaElement
    replaceStoredAccount(8)
    input(source, '<p>Previous account body</p>')
    await flush()
    expect(host.textContent).not.toContain('Previous account body')
    expect(host.querySelector('.preview-stub')).toBeNull()
  })

  it('rejects a delayed file import after another tab changes the stored session', async () => {
    const { host } = await mount('/projects/3/documents/html/new')
    let finishRead!: (value: ArrayBuffer) => void
    const file = { name: 'replacement.html', size: 21, arrayBuffer: () => new Promise<ArrayBuffer>(resolve => { finishRead = resolve }) }
    const fileInput = host.querySelector('input[type=file]') as HTMLInputElement
    Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] })
    fileInput.dispatchEvent(new Event('change', { bubbles: true })); await flush()
    replaceStoredAccount(8)
    finishRead(new TextEncoder().encode('<p>Previous file</p>').buffer)
    await flush()
    expect(host.textContent).not.toContain('Previous file')
    expect(host.querySelector('textarea')?.value).not.toBe('<p>Previous file</p>')
  })

  it('previews a loaded HTML document without a project sidebar', async () => {
    const { host } = await mount()
    expect(host.querySelector('.preview-stub')?.textContent).toBe(htmlDocument().content)
    expect(host.querySelector('.sidebar')).toBeNull()
    expect(document.title).toContain('交互文档')
  })

  it('runs a new draft before creating a row and preserves raw source on creation', async () => {
    const { host, router } = await mount('/projects/3/documents/html/new')
    const source = '\n<!doctype html><html><body>新内容</body></html>\n'
    input(host.querySelector('input:not([type=file])')!, '新文档')
    input(host.querySelector('textarea')!, source)
    await flush(); button(host, 'Run preview').click(); await flush()
    expect(host.querySelector('.preview-stub')?.textContent).toBe(source)
    expect(documentApi.create).not.toHaveBeenCalled()
    vi.mocked(documentApi.create).mockResolvedValue(htmlDocument({ title: '新文档', content: source }))
    vi.mocked(documentApi.get).mockResolvedValue(htmlDocument({ title: '新文档', content: source }))
    button(host, 'Save').click(); await flush(); await flush()
    expect(documentApi.create).toHaveBeenCalledWith(3, { parentDocumentId: null, title: '新文档', content: source, contentFormat: 'HTML' })
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/projects/3/documents/81/html'))
  })

  it('keeps existing HTML in the collaboration editor and does not send whole-body PUTs', async () => {
    const { host } = await mount(); button(host, 'Source').click(); await flush()
    const source = '\n<html><body>版本二<script>window.x=2</script></body></html>\n'
    input(host.querySelector('.collab-source')!, source); await flush()
    expect(documentApi.update).not.toHaveBeenCalled()
    button(host, 'Run preview').click(); await flush()
    expect(documentApi.update).not.toHaveBeenCalled()
    expect(host.querySelector('.preview-stub')?.textContent).toBe(source)
  })

  it('saves an existing document title through the title CAS endpoint', async () => {
    const { host } = await mount(); button(host, 'Source').click(); await flush()
    input(host.querySelector('input:not([type=file])')!, '新标题'); await flush()
    vi.mocked(documentCollaborationApi.updateTitle).mockResolvedValue({
      title: '新标题', version: 2, updatedAt: '2026-10-17T08:00:00', lastEditorId: 7
    })
    button(host, 'Save').click(); await flush()
    expect(documentCollaborationApi.updateTitle).toHaveBeenCalledWith(81, '交互文档', '新标题')
    expect(documentApi.update).not.toHaveBeenCalled()
    expect(host.querySelector('h1')?.textContent).toBe('新标题')
  })

  it('keeps edits made while an imported file is still being read', async () => {
    const { host } = await mount('/projects/3/documents/html/new')
    input(host.querySelector('input:not([type=file])')!, 'Draft')
    input(host.querySelector('textarea')!, '<p>Original draft</p>'); await flush()
    let finishRead!: (value: ArrayBuffer) => void
    const file = { name: 'replacement.html', size: 21, arrayBuffer: () => new Promise<ArrayBuffer>(resolve => { finishRead = resolve }) }
    const fileInput = host.querySelector('input[type=file]') as HTMLInputElement
    Object.defineProperty(fileInput, 'files', { configurable: true, value: [file] })
    fileInput.dispatchEvent(new Event('change', { bubbles: true })); await flush()
    expect(button(host, 'Save').disabled).toBe(true)
    input(host.querySelector('textarea')!, '<p>Newer user draft</p>'); await flush()
    finishRead(new TextEncoder().encode('<p>Imported source</p>').buffer); await flush()
    expect((host.querySelector('textarea') as HTMLTextAreaElement).value).toBe('<p>Newer user draft</p>')
    expect(button(host, 'Save').disabled).toBe(false)
  })

  it('refuses a document from another project before rendering its source', async () => {
    vi.mocked(documentApi.get).mockResolvedValue(htmlDocument({ projectId: 4 }))
    const { host } = await mount()
    expect(host.querySelector('.preview-stub')).toBeNull()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('unavailable')
  })
})
