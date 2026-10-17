import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as Y from 'yjs'
import { i18n } from '../i18n'
import { documentApi } from '../services/api/documents'
import { documentCollaborationApi } from '../services/api/documentCollaboration'
import type { ProjectDocument } from '../types/document'
import HtmlDocumentView from './HtmlDocumentView.vue'

vi.mock('../components/documents/HtmlDocumentPreview.vue', () => ({ default: { props: ['content', 'projectId'], template: '<div class="preview-stub">{{ content }}</div>' } }))
vi.mock('../components/documents/DocumentHistoryPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../components/documents/HtmlDocumentCollaboration.vue', () => ({ default: {
  props: ['documentId', 'visible', 'ariaLabel', 'placeholder', 'initialSession'], emits: ['content', 'status'],
  template: '<textarea class="collab-source" :placeholder="placeholder" @input="$emit(\'content\', $event.target.value)" />',
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
  app.use(createPinia()).use(router).use(i18n).mount(host)
  await flush()
  return { host, router }
}
const button = (host: HTMLElement, text: string) => [...host.querySelectorAll('button')].find((item) => item.textContent === text)!
function input(node: HTMLInputElement | HTMLTextAreaElement, value: string) { node.value = value; node.dispatchEvent(new Event('input', { bubbles: true })) }

describe('HTML document source and publishing', () => {
  beforeEach(() => {
    document.body.innerHTML = ''; localStorage.clear(); vi.clearAllMocks(); i18n.global.locale.value = 'en'
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
  afterEach(() => { apps.splice(0).forEach((app) => app.unmount()) })

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
