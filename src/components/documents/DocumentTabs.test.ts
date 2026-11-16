import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it } from 'vitest'
import { i18n } from '../../i18n'
import type { DocumentTabItem } from '../../store/documentTabsStore'
import DocumentTabs from './DocumentTabs.vue'

const testTabs: DocumentTabItem[] = [
  { id: 1, projectId: 10, title: 'Overview', contentFormat: 'BLOCKNOTE' },
  { id: 2, projectId: 10, title: 'API Reference', contentFormat: 'HTML' },
  { id: 3, projectId: 10, title: 'Design System', contentFormat: 'BLOCKNOTE' }
]

describe('DocumentTabs.vue', () => {
  let host: HTMLElement

  beforeEach(() => {
    host = document.createElement('div')
    document.body.appendChild(host)
  })

  it('renders all tabs with titles and marks active tab', async () => {
    const app = createApp(DocumentTabs, {
      tabs: testTabs,
      activeId: 2
    }).use(i18n)
    app.mount(host)
    await nextTick()

    const tabs = host.querySelectorAll('.document-tab')
    expect(tabs).toHaveLength(3)
    expect(tabs[0]?.textContent).toContain('Overview')
    expect(tabs[1]?.textContent).toContain('API Reference')
    expect(tabs[1]?.classList.contains('document-tab--active')).toBe(true)
    expect(tabs[0]?.classList.contains('document-tab--active')).toBe(false)
    app.unmount()
  })

  it('emits select when clicking a tab', async () => {
    let selectedId: number | null = null
    const app = createApp(DocumentTabs, {
      tabs: testTabs,
      activeId: 1,
      onSelect: (id: number) => {
        selectedId = id
      }
    }).use(i18n)
    app.mount(host)
    await nextTick()

    const tabs = host.querySelectorAll('.document-tab')
    ;(tabs[2] as HTMLElement).click()
    await nextTick()

    expect(selectedId).toBe(3)
    app.unmount()
  })

  it('emits close when clicking tab close button', async () => {
    let closedId: number | null = null
    let selectedId: number | null = null
    const app = createApp(DocumentTabs, {
      tabs: testTabs,
      activeId: 1,
      onClose: (id: number) => {
        closedId = id
      },
      onSelect: (id: number) => {
        selectedId = id
      }
    }).use(i18n)
    app.mount(host)
    await nextTick()

    const closeBtn = host.querySelectorAll('.document-tab__close')[1] as HTMLButtonElement
    closeBtn.click()
    await nextTick()

    expect(closedId).toBe(2)
    expect(selectedId).toBeNull()
    app.unmount()
  })

  it('does not render when tabs list is empty', async () => {
    const app = createApp(DocumentTabs, {
      tabs: [],
      activeId: null
    }).use(i18n)
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.document-tabs')).toBeNull()
    app.unmount()
  })

  it('renders pinned tab with pin icon and hides close button', async () => {
    const pinnedTabs: DocumentTabItem[] = [
      { id: 1, projectId: 10, title: 'Pinned Doc', contentFormat: 'BLOCKNOTE', pinned: true },
      { id: 2, projectId: 10, title: 'Normal Doc', contentFormat: 'BLOCKNOTE' }
    ]
    const app = createApp(DocumentTabs, {
      tabs: pinnedTabs,
      activeId: 1
    }).use(i18n)
    app.mount(host)
    await nextTick()

    const tabs = host.querySelectorAll('.document-tab')
    expect(tabs[0]?.classList.contains('document-tab--pinned')).toBe(true)
    expect(tabs[0]?.querySelector('.document-tab__pin-icon')).not.toBeNull()
    // Pinned tab should not render direct close button
    expect(tabs[0]?.querySelector('.document-tab__close')).toBeNull()

    // Normal tab has close button
    expect(tabs[1]?.querySelector('.document-tab__close')).not.toBeNull()
    app.unmount()
  })

  it('renders dirty indicator when document id is in dirtyIds', async () => {
    const app = createApp(DocumentTabs, {
      tabs: testTabs,
      activeId: 1,
      dirtyIds: [2]
    }).use(i18n)
    app.mount(host)
    await nextTick()

    const tabs = host.querySelectorAll('.document-tab')
    expect(tabs[0]?.querySelector('.document-tab__dirty-dot')).toBeNull()
    expect(tabs[1]?.querySelector('.document-tab__dirty-dot')).not.toBeNull()
    app.unmount()
  })

  it('emits reorder event when dropping a tab', async () => {
    let reorderEvent: { sourceId: number; targetId: number; place: string } | null = null
    const app = createApp(DocumentTabs, {
      tabs: testTabs,
      activeId: 1,
      onReorder: (sourceId: number, targetId: number, place: 'before' | 'after') => {
        reorderEvent = { sourceId, targetId, place }
      }
    }).use(i18n)
    app.mount(host)
    await nextTick()

    const tabs = host.querySelectorAll('.document-tab')
    const sourceEl = tabs[0] as HTMLElement
    const targetEl = tabs[2] as HTMLElement

    // Mock dragstart
    const dragEvent = new Event('dragstart', { bubbles: true }) as DragEvent
    Object.defineProperty(dragEvent, 'dataTransfer', {
      value: { setData: () => {}, effectAllowed: 'move' }
    })
    sourceEl.dispatchEvent(dragEvent)
    await nextTick()

    // Mock dragover
    const dragOverEvent = new Event('dragover', { bubbles: true, cancelable: true }) as DragEvent
    Object.defineProperty(dragOverEvent, 'clientX', { value: 50 })
    Object.defineProperty(dragOverEvent, 'dataTransfer', { value: {} })
    targetEl.getBoundingClientRect = () => ({
      left: 0,
      right: 100,
      width: 100,
      top: 0,
      bottom: 38,
      height: 38,
      x: 0,
      y: 0,
      toJSON: () => {}
    })
    targetEl.dispatchEvent(dragOverEvent)
    await nextTick()

    // Mock drop
    const dropEvent = new Event('drop', { bubbles: true, cancelable: true }) as DragEvent
    targetEl.dispatchEvent(dropEvent)
    await nextTick()

    expect(reorderEvent).not.toBeNull()
    expect(reorderEvent?.sourceId).toBe(1)
    expect(reorderEvent?.targetId).toBe(3)
    app.unmount()
  })

  it('toggles overflow more menu and selects tab from it', async () => {
    let selectedId: number | null = null
    const app = createApp(DocumentTabs, {
      tabs: testTabs,
      activeId: 1,
      onSelect: (id: number) => {
        selectedId = id
      }
    }).use(i18n)
    app.mount(host)
    await nextTick()

    const moreBtn = host.querySelector('.document-tabs__more-btn') as HTMLButtonElement
    expect(moreBtn).not.toBeNull()
    moreBtn.click()
    await nextTick()

    const menu = document.body.querySelector('.document-tabs-more-menu')
    expect(menu).not.toBeNull()

    const items = menu?.querySelectorAll('.document-tabs-more-menu__item')
    expect(items).toHaveLength(3)

    ;(items?.[1] as HTMLElement).click()
    await nextTick()

    expect(selectedId).toBe(2)
    app.unmount()
  })
})
