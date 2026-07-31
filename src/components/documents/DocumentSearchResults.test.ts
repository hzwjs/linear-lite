import { createApp, nextTick } from 'vue'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import type { ProjectDocumentTreeNode } from '../../types/document'
import type { ProjectContentSearchResult } from '../../types/search'
import DocumentSearchResults from './DocumentSearchResults.vue'

const treeNodes: ProjectDocumentTreeNode[] = [
  { id: 1, projectId: 7, parentDocumentId: null, title: 'Root', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' },
  { id: 2, projectId: 7, parentDocumentId: 1, title: 'Security', sortOrder: 0, version: 1, favorited: false, updatedAt: '2026-07-29T08:00:00' }
]

const result: ProjectContentSearchResult = {
  contentType: 'document', resourceId: '2', projectId: 7, projectIdentifier: 'JLNX', projectName: 'JLNX',
  title: '<script>Security</script>', excerpt: 'Review security requirements'
}

describe('DocumentSearchResults', () => {
  it('renders tree path and safe highlighted text, then emits the selected result', async () => {
    const onSelect = vi.fn()
    const host = document.createElement('div')
    const app = createApp(DocumentSearchResults, {
      query: 'security', results: [result], treeNodes, loading: false, error: false, onSelect
    })
    app.use(i18n)
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.document-search-results__path')?.textContent).toBe('文档 / Root')
    expect(host.querySelectorAll('mark')).toHaveLength(2)
    expect(host.querySelector('script')).toBeNull()
    expect(host.textContent).toContain('<script>Security</script>')
    ;(host.querySelector('button') as HTMLButtonElement).click()
    expect(onSelect).toHaveBeenCalledWith(result)
    app.unmount()
  })

  it('shows explicit loading, error retry, and no-result states', async () => {
    const onRetry = vi.fn()
    const host = document.createElement('div')
    const app = createApp(DocumentSearchResults, {
      query: 'security', results: [], treeNodes, loading: true, error: false, onRetry
    })
    app.use(i18n)
    const vm = app.mount(host) as any
    await nextTick()
    expect(host.textContent).toContain('正在搜索当前项目文档')

    vm.$.props.loading = false
    vm.$.props.error = true
    await nextTick()
    ;(host.querySelector('button') as HTMLButtonElement).click()
    expect(onRetry).toHaveBeenCalledOnce()

    vm.$.props.error = false
    await nextTick()
    expect(host.textContent).toContain('当前项目中没有匹配的文档')
    app.unmount()
  })
})
