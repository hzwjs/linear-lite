import { describe, expect, it } from 'vitest'
import sidebarSource from './SidebarNavigation.vue?raw'

describe('sidebar typography hierarchy', () => {
  it('keeps section labels visually above navigation items', () => {
    expect(sidebarSource).toContain('font-size: var(--font-size-body);')
    expect(sidebarSource).toContain('font-weight: var(--font-weight-semibold);')
    expect(sidebarSource).toContain('line-height: 1.25;')
    expect(sidebarSource).not.toContain('font-size: 12px;\n  font-weight: 500;\n  line-height: 1.2;')
  })
})
