import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import type { ProjectDocumentTreeNode } from '../../types/document'
import DocumentTree from './DocumentTree.vue'

const nodes: ProjectDocumentTreeNode[] = [
  { id: 1, projectId: 7, parentDocumentId: null, title: 'Root', sortOrder: 0, version: 1, updatedAt: '2026-07-29T08:00:00' },
  { id: 2, projectId: 7, parentDocumentId: 1, title: 'Child', sortOrder: 0, version: 1, updatedAt: '2026-07-29T08:00:00' }
]

describe('DocumentTree keyboard interaction', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    localStorage.clear()
    i18n.global.locale.value = 'en'
  })

  it('exposes treeitem semantics and expands with ArrowRight', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 1, query: '' })
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

  it('offers an outdent action as a keyboard-accessible alternative to dragging', async () => {
    const onMove = vi.fn()
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(DocumentTree, { projectId: 7, nodes, activeId: 2, query: '', onMove })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    ;(host.querySelector('[aria-label="Expand document"]') as HTMLButtonElement).click()
    await nextTick()
    const childItem = Array.from(host.querySelectorAll('[role="treeitem"]')).find((item) => item.getAttribute('aria-level') === '2')!
    ;(childItem.querySelector('[aria-label="Actions for Child"]') as HTMLButtonElement).click()
    await nextTick()
    const outdent = Array.from(childItem.querySelectorAll<HTMLButtonElement>('[role="menuitem"]'))
      .find((button) => button.textContent?.includes('Move out one level'))!
    outdent.click()

    expect(onMove).toHaveBeenCalledWith({ documentId: 2, parentDocumentId: null, previousSiblingId: 1 })
    app.unmount()
  })
})
