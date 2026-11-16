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
})
