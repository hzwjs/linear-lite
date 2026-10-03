import { createApp, nextTick, type App } from 'vue'
import { createPinia } from 'pinia'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { documentApi } from '../services/api/documents'
import type { ProjectDocument } from '../types/document'
import HtmlDocumentView from './HtmlDocumentView.vue'

vi.mock('../components/documents/HtmlDocumentPreview.vue', () => ({ default: { props: ['content', 'projectId'], template: '<div class="preview-stub">{{ content }}</div>' } }))
vi.mock('../components/documents/DocumentHistoryPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../services/api/documents', async () => {
  const actual = await vi.importActual<typeof import('../services/api/documents')>('../services/api/documents')
  return { ...actual, documentApi: { ...actual.documentApi, get: vi.fn(), create: vi.fn(), update: vi.fn() } }
})

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

  it('publishes only on Save and carries the acknowledged version and format', async () => {
    const { host } = await mount(); button(host, 'Source').click(); await flush()
    const source = '\n<html><body>版本二<script>window.x=2</script></body></html>\n'
    input(host.querySelector('textarea')!, source); await flush()
    expect(documentApi.update).not.toHaveBeenCalled()
    vi.mocked(documentApi.update).mockResolvedValue(htmlDocument({ content: source, version: 2 }))
    button(host, 'Save').click(); await flush()
    expect(documentApi.update).toHaveBeenCalledWith(81, { expectedVersion: 1, title: '交互文档', content: source, contentFormat: 'HTML' })
    expect(host.querySelector('.preview-stub')?.textContent).toBe(source)
    expect(host.textContent).toContain('v2')
  })

  it('keeps local source on version conflict and blocks further publishing', async () => {
    const { host } = await mount(); button(host, 'Source').click(); await flush()
    input(host.querySelector('textarea')!, '<p>local draft</p>'); await flush()
    vi.mocked(documentApi.update).mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { data: { currentVersion: 4 } } } })
    button(host, 'Save').click(); await flush()
    expect((host.querySelector('textarea') as HTMLTextAreaElement).value).toBe('<p>local draft</p>')
    expect(host.querySelector('[role=alert]')?.textContent).toContain('Someone else changed')
    expect(button(host, 'Save').disabled).toBe(true)
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
