import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import type { ProjectDocumentTreeNode } from '../../types/document'
import DocumentTree from './DocumentTree.vue'

const nodes: ProjectDocumentTreeNode[] = [
  { id: 1, projectId: 7, parentDocumentId: null, title: 'Root', sortOrder: 0, version: 1, updatedAt: '2026-07-29T08:00:00' },
  { id: 2, projectId: 7, parentDocumentId: 1, title: 'Child', sortOrder: 0, version: 1, updatedAt: '2026-07-29T08:00:00' }
]

function createDragEvent(type: string, dataTransfer: Partial<DataTransfer>, clientX = 0, clientY = 0) {
  const event = new Event(type, { bubbles: true, cancelable: true }) as DragEvent
  Object.defineProperties(event, {
    dataTransfer: { value: dataTransfer },
    clientX: { value: clientX },
    clientY: { value: clientY }
  })
  return event
}

describe('DocumentTree keyboard interaction', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    localStorage.clear()
    i18n.global.locale.value = 'en'
  })

  it('exposes treeitem semantics and expands with ArrowRight', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 1, query: '', moving: false })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    const root = host.querySelector('[role="treeitem"]') as HTMLElement
    expect(root.getAttribute('aria-level')).toBe('1')
    expect(root.getAttribute('aria-selected')).toBe('true')
    expect(root.getAttribute('aria-expanded')).toBe('false')

    const rootButton = host.querySelector('[data-document-tree-id="1"]') as HTMLButtonElement
    rootButton.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowRight', bubbles: true }))
    await nextTick()
    expect(host.querySelector('[data-document-tree-id="2"]')).toBeTruthy()

    rootButton.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true }))
    expect(document.activeElement).toBe(host.querySelector('[data-document-tree-id="2"]'))
    app.unmount()
  })

  it('supports keyboard hierarchy changes from the document row', async () => {
    const onMove = vi.fn()
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 2, query: '', moving: false, onMove })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    ;(host.querySelector('[aria-label="Expand document"]') as HTMLButtonElement).click()
    await nextTick()
    const childItem = Array.from(host.querySelectorAll('[role="treeitem"]')).find((item) => item.getAttribute('aria-level') === '2')!
    const documentButton = childItem.querySelector('[data-document-tree-id="2"]') as HTMLButtonElement
    documentButton.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowLeft', altKey: true, bubbles: true }))

    expect(onMove).toHaveBeenCalledWith({ documentId: 2, parentDocumentId: null, previousSiblingId: 1 })
    app.unmount()
  })

  it('keeps structural movement out of the document actions menu', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 1, query: '', moving: false })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    ;(host.querySelector('[aria-label="Actions for Root"]') as HTMLButtonElement).click()
    await nextTick()
    const menuLabels = Array.from(host.querySelectorAll<HTMLElement>('[role="menuitem"]')).map((item) => item.textContent?.trim())
    expect(menuLabels).toEqual(['New child document', 'Archive'])
    app.unmount()
  })

  it('uses the whole document row as the drag source', async () => {
    const onMove = vi.fn()
    const draggableNodes = [
      ...nodes,
      { id: 3, projectId: 7, parentDocumentId: null, title: 'Sibling', sortOrder: 1, version: 1, updatedAt: '2026-07-29T08:00:00' }
    ]
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes: draggableNodes, activeId: 2, query: '', moving: false, onMove })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    ;(host.querySelector('[aria-label="Expand document"]') as HTMLButtonElement).click()
    await nextTick()
    const sourceRow = (host.querySelector('[data-document-tree-id="2"]') as HTMLButtonElement).closest('.document-tree-row') as HTMLElement
    const targetRow = (host.querySelector('[data-document-tree-id="3"]') as HTMLButtonElement).closest('.document-tree-row') as HTMLElement
    targetRow.getBoundingClientRect = () => ({ top: 0, bottom: 30, left: 0, right: 240, width: 240, height: 30, x: 0, y: 0, toJSON: () => ({}) })
    const dataTransfer = { setData: vi.fn(), setDragImage: vi.fn(), effectAllowed: 'none', dropEffect: 'none' }

    sourceRow.dispatchEvent(createDragEvent('dragstart', dataTransfer, 180, 15))
    await nextTick()
    targetRow.dispatchEvent(createDragEvent('dragover', dataTransfer, 180, 15))
    targetRow.dispatchEvent(createDragEvent('drop', dataTransfer, 180, 15))

    expect(onMove).toHaveBeenCalledWith({ documentId: 2, parentDocumentId: 3, previousSiblingId: null })
    app.unmount()
  })
})
