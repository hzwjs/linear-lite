import { createApp, nextTick, type App } from 'vue'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import { documentApi } from '../../services/api/documents'
import type { ProjectDocument, ProjectDocumentRevision } from '../../types/document'
import DocumentHistoryPanel from './DocumentHistoryPanel.vue'

const restoreRevision = vi.hoisted(() => vi.fn())
vi.mock('../../store/documentStore', () => ({ useDocumentStore: () => ({ restoreRevision }) }))
vi.mock('../../services/api/documents', async () => {
  const actual = await vi.importActual<typeof import('../../services/api/documents')>('../../services/api/documents')
  return { ...actual, documentApi: { listRevisions: vi.fn(), getRevision: vi.fn() } }
})
vi.mock('./HtmlDocumentPreview.vue', () => ({ default: { props: ['content'], template: '<div class="preview-stub">{{ content }}</div>' } }))
vi.mock('../StructuredDocumentEditor.vue', () => ({ default: { template: '<div />' } }))
const documentValue: ProjectDocument = {
  id: 81, projectId: 3, parentDocumentId: null, title: 'Current', contentFormat: 'HTML', content: '<p>Current</p>',
  imageAssets: [], sortOrder: 0, version: 3, favorited: false, creatorId: 7, lastEditorId: 7,
  archivedAt: null, createdAt: '2026-10-03T10:00:00', updatedAt: '2026-10-03T10:00:00'
}
const revision = (revisionId: number): ProjectDocumentRevision => ({
  revisionId, documentId: 81, sourceVersion: revisionId, title: `Version ${revisionId}`, content: `<p>Version ${revisionId}</p>`,
  contentFormat: 'HTML', imageAssets: [], editorId: 7, editorName: 'Editor', createdAt: '2026-10-03T10:00:00'
})
let app: App
async function flush() { await Promise.resolve(); await Promise.resolve(); await nextTick() }
async function mount() {
  const host = window.document.createElement('div'); window.document.body.append(host)
  app = createApp(DocumentHistoryPanel, { open: true, document: documentValue }).use(i18n)
  app.mount(host); await flush()
  return host
}
const restoreButton = (host: HTMLElement) => host.querySelector('footer button') as HTMLButtonElement

describe('document history requests and restore feedback', () => {
  beforeEach(() => {
    window.document.body.innerHTML = ''; vi.clearAllMocks(); i18n.global.locale.value = 'en'
    vi.spyOn(window, 'confirm').mockReturnValue(true)
    vi.mocked(documentApi.listRevisions).mockResolvedValue([revision(1), revision(2)])
  })
  afterEach(() => { app?.unmount(); vi.restoreAllMocks() })

  it('does not allow pending details to be restored or replace the newest selection', async () => {
    let finishFirst!: (value: ProjectDocumentRevision) => void
    vi.mocked(documentApi.getRevision).mockImplementation((_id, revisionId) => revisionId === 1
      ? new Promise(resolve => { finishFirst = resolve }) : Promise.resolve(revision(2)))
    const host = await mount()
    const rows = host.querySelectorAll<HTMLButtonElement>('.document-history__item')
    rows[1]!.click(); await flush()
    expect(restoreButton(host).disabled).toBe(true)
    rows[2]!.click(); await flush()
    finishFirst(revision(1)); await flush()
    expect(host.querySelector('.preview-stub')?.textContent).toBe('<p>Version 2</p>')
    restoreRevision.mockResolvedValue(undefined)
    restoreButton(host).click(); await flush()
    expect(restoreRevision).toHaveBeenCalledWith(2)
  })

  it('shows a conflict without treating an unsuccessful restore as completed', async () => {
    vi.mocked(documentApi.getRevision).mockResolvedValue(revision(1))
    restoreRevision.mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { data: { currentVersion: 4 } } } })
    const host = await mount()
    host.querySelectorAll<HTMLButtonElement>('.document-history__item')[1]!.click(); await flush()
    restoreButton(host).click(); await flush()
    expect(host.querySelector('[role=alert]')?.textContent).toContain('Refresh the page')
    expect(restoreButton(host).disabled).toBe(false)
    expect(host.querySelector('.preview-stub')?.textContent).toBe('<p>Version 1</p>')
  })
})
