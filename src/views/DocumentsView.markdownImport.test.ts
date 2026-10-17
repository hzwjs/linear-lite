import { createPinia } from 'pinia'
import { createApp, nextTick } from 'vue'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import { documentApi } from '../services/api/documents'
import { documentCollaborationApi, type DocumentCollaborationSession } from '../services/api/documentCollaboration'
import { projectApi } from '../services/api/project'
import type { ProjectDocument, ProjectDocumentTreeNode } from '../types/document'
import DocumentsView from './DocumentsView.vue'

vi.mock('../components/documents/DocumentEditor.vue', () => ({ default: { template: '<div data-editor />' } }))
vi.mock('../components/documents/DocumentHistoryPanel.vue', () => ({ default: { template: '<div />' } }))
vi.mock('../services/api/documentCollaboration', () => ({ documentCollaborationApi: { createSession: vi.fn() } }))
vi.mock('../services/api/documents', async (importOriginal) => ({
  ...await importOriginal<typeof import('../services/api/documents')>(),
  documentApi: { listTree: vi.fn(), get: vi.fn(), importMarkdown: vi.fn(), inspectMarkdown: vi.fn(), search: vi.fn() }
}))
vi.mock('../services/api/project', () => ({ projectApi: { listMembers: vi.fn() } }))

const root: ProjectDocumentTreeNode = { id: 1, projectId: 7, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Root', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-10-04T10:00:00+08:00' }
function imported(id = 2, parentDocumentId: number | null = null): ProjectDocument {
  return { ...root, id, parentDocumentId, title: 'Imported', content: '[]', imageAssets: [], creatorId: 1, lastEditorId: 1, archivedAt: null, createdAt: root.updatedAt }
}
function fileDrag(type: string, files: File[], relatedTarget: EventTarget | null = null) {
  const event = new Event(type, { bubbles: true, cancelable: true }) as DragEvent
  const dataTransfer = { types: ['Files'], files, dropEffect: 'none' }
  Object.defineProperties(event, { dataTransfer: { value: dataTransfer }, relatedTarget: { value: relatedTarget } })
  return { event, dataTransfer }
}
async function settle() {
  await new Promise((resolve) => setTimeout(resolve, 0))
  await nextTick()
}

describe('DocumentsView Markdown import', () => {
  let unmount: (() => void) | undefined
  beforeEach(() => {
    document.body.innerHTML = ''
    localStorage.clear()
    i18n.global.locale.value = 'en'
    vi.mocked(documentApi.listTree).mockReset().mockResolvedValue([root])
    vi.mocked(documentApi.get).mockReset().mockImplementation(async (id) => imported(id))
    vi.mocked(documentApi.inspectMarkdown).mockReset().mockResolvedValue({ imagePaths: [] })
    vi.mocked(documentApi.importMarkdown).mockReset().mockResolvedValue(imported())
    vi.mocked(projectApi.listMembers).mockReset().mockResolvedValue([])
    vi.mocked(documentCollaborationApi.createSession).mockReset().mockResolvedValue({} as DocumentCollaborationSession)
  })
  afterEach(() => unmount?.())

  async function mount() {
    const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/projects/:projectId/documents/:documentId?', component: DocumentsView }] })
    await router.push('/projects/7/documents')
    await router.isReady()
    const host = document.createElement('div'); document.body.append(host)
    const app = createApp({ template: '<RouterView />' }).use(createPinia()).use(router).use(i18n)
    app.mount(host); unmount = () => app.unmount()
    await settle()
    return { host, router }
  }

  it('drops a file on a row as a child and opens the saved document', async () => {
    const { host, router } = await mount()
    const row = host.querySelector('[data-document-drop-id="1"]')!
    const file = new File(['# Guide'], 'guide.md')
    const over = fileDrag('dragover', [file]); row.dispatchEvent(over.event)
    await nextTick()
    expect(over.event.defaultPrevented).toBe(true)
    expect(over.dataTransfer.dropEffect).toBe('copy')
    expect(row.classList.contains('document-tree-row--drop-inside')).toBe(true)
    expect(host.textContent).toContain('Release to create documents under “Root”')
    vi.mocked(documentApi.listTree).mockResolvedValue([root, imported(2, 1)])
    row.dispatchEvent(fileDrag('drop', [file]).event)
    await settle()
    expect(documentApi.importMarkdown).toHaveBeenCalledWith(7, 1, file, [])
    expect(router.currentRoute.value.params.documentId).toBe('2')
    expect(host.textContent).toContain('Imported')
    expect(host.querySelector('[data-document-tree-id="2"]')).not.toBeNull()
  })

  it('accepts root files in an empty tree and clears highlight when leaving', async () => {
    vi.mocked(documentApi.listTree).mockResolvedValue([])
    const { host } = await mount()
    const area = host.querySelector('.documents-sidebar__tree-scroll')!
    const file = new File(['hello'], 'guide.md')
    area.dispatchEvent(fileDrag('dragover', [file]).event); await nextTick()
    expect(area.classList.contains('documents-sidebar__tree-scroll--file-drop')).toBe(true)
    area.dispatchEvent(fileDrag('dragleave', [file], document.body).event); await nextTick()
    expect(area.classList.contains('documents-sidebar__tree-scroll--file-drop')).toBe(false)
    area.dispatchEvent(fileDrag('dragover', [file]).event)
    vi.mocked(documentApi.listTree).mockResolvedValue([imported()])
    area.dispatchEvent(fileDrag('drop', [file]).event); await settle()
    expect(documentApi.importMarkdown).toHaveBeenCalledWith(7, null, file, [])
  })

  it('imports selected files, isolates failures, and retries the failed file with its captured parent', async () => {
    const { host } = await mount()
    const a = new File(['A'], 'a.md'); const b = new File(['B'], 'b.md')
    vi.mocked(documentApi.importMarkdown).mockResolvedValueOnce(imported(2)).mockRejectedValueOnce(new Error('Offline'))
    const input = host.querySelector('input[type="file"]') as HTMLInputElement
    Object.defineProperty(input, 'files', { configurable: true, value: [a, b] })
    input.dispatchEvent(new Event('change', { bubbles: true })); await settle()
    expect(documentApi.importMarkdown).toHaveBeenCalledTimes(2)
    expect(input.value).toBe('')
    expect(host.textContent).toContain('Offline')
    ;(host.querySelector('.documents-sidebar__imports button') as HTMLButtonElement).click(); await settle()
    expect(documentApi.importMarkdown).toHaveBeenCalledTimes(3)
    expect(documentApi.importMarkdown).toHaveBeenLastCalledWith(7, null, b, [])
    expect(host.textContent).not.toContain('Offline')
  })

  it('imports a Markdown file under the document selected from its actions menu', async () => {
    const { host } = await mount()
    ;(host.querySelector('[aria-label="Actions for Root"]') as HTMLButtonElement).click()
    await nextTick()
    const importButton = Array.from(host.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'))
      .find((item) => item.textContent?.includes('Import Markdown'))!
    importButton.click()
    const file = new File(['# Child'], 'child.md')
    const input = host.querySelector('input[type="file"]') as HTMLInputElement
    Object.defineProperty(input, 'files', { configurable: true, value: [file] })
    input.dispatchEvent(new Event('change', { bubbles: true }))
    await settle()
    expect(documentApi.importMarkdown).toHaveBeenCalledWith(7, 1, file, [])
  })

  it('rejects unsupported and oversized files without sending upload requests', async () => {
    const { host } = await mount()
    const large = new File(['large'], 'large.md'); Object.defineProperty(large, 'size', { value: 5 * 1024 * 1024 + 1 })
    const area = host.querySelector('.documents-sidebar__tree-scroll')!
    const files = [new File(['x'], 'guide.pdf'), large]
    area.dispatchEvent(fileDrag('dragover', files).event)
    area.dispatchEvent(fileDrag('drop', files).event); await settle()
    expect(documentApi.importMarkdown).not.toHaveBeenCalled()
    expect(host.textContent).toContain('Choose a .md file.')
    expect(host.textContent).toContain('5 MiB')
  })

  it('blocks repeated imports while uploading and leaves document move events uncaptured', async () => {
    let resolve!: (doc: ProjectDocument) => void
    vi.mocked(documentApi.importMarkdown).mockReturnValue(new Promise((done) => { resolve = done }))
    const { host } = await mount()
    const area = host.querySelector('.documents-sidebar__tree-scroll')!
    const internal = new Event('dragover', { bubbles: true, cancelable: true })
    Object.defineProperty(internal, 'dataTransfer', { value: { types: ['application/x-linear-lite-document-id'] } })
    area.dispatchEvent(internal)
    expect(internal.defaultPrevented).toBe(false)
    const files = [new File(['x'], 'guide.md')]
    area.dispatchEvent(fileDrag('dragover', files).event)
    area.dispatchEvent(fileDrag('drop', files).event); await settle()
    const over = fileDrag('dragover', files); area.dispatchEvent(over.event)
    expect(over.dataTransfer.dropEffect).toBe('none')
    area.dispatchEvent(fileDrag('drop', files).event)
    expect(documentApi.importMarkdown).toHaveBeenCalledTimes(1)
    expect((host.querySelector('[aria-label="Import Markdown files"]') as HTMLButtonElement).disabled).toBe(true)
    resolve(imported()); await settle()
  })

  it('waits for missing resources, matches selected images, then imports with captured parent', async () => {
    vi.mocked(documentApi.inspectMarkdown).mockResolvedValue({ imagePaths: ['diagram.png', 'img/nested.png'] })
    const { host } = await mount()
    const file = new File(['![图](diagram.png)'], 'guide.md')
    const row = host.querySelector('[data-document-drop-id="1"]')!
    row.dispatchEvent(fileDrag('dragover', [file]).event)
    row.dispatchEvent(fileDrag('drop', [file]).event); await settle()
    expect(documentApi.importMarkdown).not.toHaveBeenCalled()
    expect(host.textContent).toContain('Images needed')
    const proceed = host.querySelector('.documents-sidebar__image-actions button:last-child') as HTMLButtonElement
    expect(proceed.disabled).toBe(true)
    ;(host.querySelector('.documents-sidebar__image-actions button:nth-child(2)') as HTMLButtonElement).click()
    const a = new File(['a'], 'diagram.png', { type: 'image/png' })
    const b = new File(['b'], 'nested.png', { type: 'image/png' })
    Object.defineProperty(a, 'webkitRelativePath', { value: 'resource-root/diagram.png' })
    Object.defineProperty(b, 'webkitRelativePath', { value: 'resource-root/img/nested.png' })
    const input = host.querySelector('[data-markdown-image-directory]') as HTMLInputElement
    Object.defineProperty(input, 'files', { configurable: true, value: [a, b] })
    input.dispatchEvent(new Event('change', { bubbles: true })); await settle()
    expect(proceed.disabled).toBe(false)
    proceed.click(); await settle()
    expect(documentApi.importMarkdown).toHaveBeenCalledWith(7, 1, file, [{ path: 'diagram.png', file: a }, { path: 'img/nested.png', file: b }])
  })

  it('allows replacing a rejected image before retrying the import', async () => {
    vi.mocked(documentApi.inspectMarkdown).mockResolvedValue({ imagePaths: ['diagram.png'] })
    vi.mocked(documentApi.importMarkdown).mockRejectedValueOnce(new Error('Invalid PNG'))
    const { host } = await mount()
    const file = new File(['![图](diagram.png)'], 'guide.md')
    const bad = new File(['bad'], 'diagram.png', { type: 'image/png' })
    const area = host.querySelector('.documents-sidebar__tree-scroll')!
    area.dispatchEvent(fileDrag('dragover', [file, bad]).event)
    area.dispatchEvent(fileDrag('drop', [file, bad]).event); await settle()
    expect(host.textContent).toContain('Invalid PNG')
    ;(host.querySelector('.documents-sidebar__image-resources li button') as HTMLButtonElement).click()
    const good = new File(['good'], 'fixed.png', { type: 'image/png' })
    const input = host.querySelector('[data-markdown-images]') as HTMLInputElement
    expect(input.multiple).toBe(false)
    Object.defineProperty(input, 'files', { value: [good], configurable: true })
    input.dispatchEvent(new Event('change', { bubbles: true })); await settle()
    expect(host.textContent).not.toContain('Invalid PNG')
    ;(host.querySelector('.documents-sidebar__image-actions button:last-child') as HTMLButtonElement).click(); await settle()
    expect(documentApi.importMarkdown).toHaveBeenLastCalledWith(7, null, file, [{ path: 'diagram.png', file: good }])
  })

  it('accepts images alongside Markdown without creating image-named documents', async () => {
    vi.mocked(documentApi.inspectMarkdown).mockResolvedValue({ imagePaths: ['diagram.png'] })
    const { host } = await mount()
    const file = new File(['![图](diagram.png)'], 'guide.md')
    const image = new File(['png'], 'diagram.png', { type: 'image/png' })
    const area = host.querySelector('.documents-sidebar__tree-scroll')!
    area.dispatchEvent(fileDrag('dragover', [file, image]).event)
    area.dispatchEvent(fileDrag('drop', [file, image]).event); await settle()
    expect(documentApi.importMarkdown).toHaveBeenCalledTimes(1)
    expect(documentApi.importMarkdown).toHaveBeenCalledWith(7, null, file, [{ path: 'diagram.png', file: image }])
  })

  it('refreshes the tree after success without re-uploading when the refresh failed', async () => {
    const { host } = await mount()
    vi.mocked(documentApi.listTree).mockRejectedValueOnce(new Error('Tree offline'))
    const area = host.querySelector('.documents-sidebar__tree-scroll')!
    const files = [new File(['x'], 'guide.md')]
    area.dispatchEvent(fileDrag('dragover', files).event)
    area.dispatchEvent(fileDrag('drop', files).event); await settle()
    expect(host.textContent).toContain('Documents were imported, but the tree could not refresh')
    ;(host.querySelector('.documents-sidebar__imports p button') as HTMLButtonElement).click(); await settle()
    expect(documentApi.importMarkdown).toHaveBeenCalledTimes(1)
    expect(host.querySelector('.documents-sidebar__imports p')).toBeNull()
  })
})
