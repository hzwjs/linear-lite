import { describe, expect, it } from 'vitest'
import source from './MobileApp.vue?raw'

describe('MobileApp document route', () => {
  it('renders the read-only document route before task and tab surfaces', () => {
    expect(source).toContain("route.name !== 'project-document-detail'")
    expect(source).toMatch(/<MobileDocumentReadView\s+v-if="routeDocumentContext"/s)
    expect(source).toMatch(/<MobileTaskDetailView\s+v-else-if="routeTaskId"/s)
    expect(source.indexOf('v-if="routeDocumentContext"')).toBeLessThan(source.indexOf('v-else-if="routeTaskId"'))
  })

  it('keeps Documents out of the mobile primary navigation', () => {
    const tabbar = source.match(/<nav class="mobile-tabbar"[\s\S]*?<\/nav>/)?.[0] ?? ''
    expect(tabbar).not.toContain('文档')
  })
})
