import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import type { ProjectDocumentTreeNode } from '../../types/document'
import DocumentTree from './DocumentTree.vue'

const nodes: ProjectDocumentTreeNode[] = [
  { id: 1, projectId: 7, parentDocumentId: null, contentFormat: 'BLOCKNOTE', title: 'Root', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
  { id: 2, projectId: 7, parentDocumentId: 1, contentFormat: 'BLOCKNOTE', title: 'Child', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
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

  it('opens HTML rows as isolated browser tabs', async () => {
    const host = document.createElement('div'); document.body.append(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes: [{ ...nodes[0]!, contentFormat: 'HTML' }], activeId: null, moving: false }).use(i18n)
    app.mount(host); await nextTick()
    const link = host.querySelector('[data-document-tree-id="1"]') as HTMLAnchorElement
    expect(link.tagName).toBe('A')
    expect(link.getAttribute('href')).toBe('/projects/7/documents/1/html')
    expect(link.target).toBe('_blank')
    expect(link.rel).toBe('noopener noreferrer')
    app.unmount()
  })

  it('exposes treeitem semantics and expands with ArrowRight', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 1, moving: false })
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
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 1, moving: false, onMove })
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

  it('expands ancestors and scrolls the active document into view', async () => {
    const originalScrollIntoView = HTMLElement.prototype.scrollIntoView
    const scrollIntoView = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: scrollIntoView })

    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 2, moving: false })
    try {
      app.use(i18n)
      app.mount(host)
      await nextTick()
      await nextTick()

      expect(host.querySelector('[data-document-tree-id="2"]')).not.toBeNull()
      expect(host.querySelector('[aria-label="Collapse document"]')).not.toBeNull()
      expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' })
    } finally {
      app.unmount()
      if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', { configurable: true, value: originalScrollIntoView })
      else delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView
    }
  })

  it('keeps structural movement out of the document actions menu', async () => {
    const onImportMarkdown = vi.fn()
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 1, moving: false, onImportMarkdown })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    ;(host.querySelector('[aria-label="Actions for Root"]') as HTMLButtonElement).click()
    await nextTick()
    const menuLabels = Array.from(host.querySelectorAll<HTMLElement>('[role="menuitem"]')).map((item) => item.textContent?.trim())
    expect(menuLabels).toEqual(['Add to favorites', 'New child document', 'New HTML document', 'Import Markdown files', 'Archive'])
    const htmlLink = host.querySelector<HTMLAnchorElement>('[role="menuitem"][href*="/documents/html/new"]')!
    expect(htmlLink.getAttribute('href')).toBe('/projects/7/documents/html/new?parentDocumentId=1')
    expect(htmlLink.target).toBe('_blank')
    ;(Array.from(host.querySelectorAll<HTMLButtonElement>('[role="menuitem"]')).find((item) => item.textContent?.includes('Import Markdown'))!).click()
    expect(onImportMarkdown).toHaveBeenCalledWith(1)
    app.unmount()
  })

  it('closes the document actions menu when focus moves outside the row', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 1, moving: false })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    const trigger = host.querySelector('[aria-label="Actions for Root"]') as HTMLButtonElement
    trigger.click()
    await nextTick()
    expect(host.querySelector('[role="menu"]')).not.toBeNull()

    document.body.dispatchEvent(new MouseEvent('click', { bubbles: true }))
    await nextTick()
    expect(host.querySelector('[role="menu"]')).toBeNull()
    app.unmount()
  })

  it('registers one global click listener for a 1000-node tree', async () => {
    const addEventListener = vi.spyOn(document, 'addEventListener')
    const removeEventListener = vi.spyOn(document, 'removeEventListener')
    const largeTree = Array.from({ length: 1000 }, (_, index): ProjectDocumentTreeNode => ({
      id: index + 1,
      projectId: 7,
      parentDocumentId: null,
      contentFormat: 'BLOCKNOTE',
      title: `Document ${index + 1}`,
      sortOrder: index,
      version: 1,
      favorited: false,
      updatedAt: '2026-07-29T08:00:00'
    }))
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes: largeTree, activeId: null, moving: false })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    expect(host.querySelectorAll('[role="treeitem"]')).toHaveLength(1000)
    expect(addEventListener.mock.calls.filter(([type]) => type === 'click')).toHaveLength(1)

    app.unmount()
    expect(removeEventListener.mock.calls.filter(([type]) => type === 'click')).toHaveLength(1)
    addEventListener.mockRestore()
    removeEventListener.mockRestore()
  })

  it('uses the whole document row as the drag source', async () => {
    const onMove = vi.fn()
    const draggableNodes = [
      ...nodes,
      { id: 3, projectId: 7, parentDocumentId: null, title: 'Sibling', sortOrder: 1, version: 1, updatedAt: '2026-07-29T08:00:00' }
    ]
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes: draggableNodes, activeId: 2, moving: false, onMove })
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
