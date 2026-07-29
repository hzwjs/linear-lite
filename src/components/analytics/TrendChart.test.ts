import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { createApp, nextTick, type App } from 'vue'
import { i18n } from '../../i18n'
import TrendChart from './TrendChart.vue'

describe('TrendChart', () => {
  let app: App<Element> | null = null

  beforeEach(() => {
    document.body.innerHTML = '<div id="app"></div>'
  })

  afterEach(() => {
    app?.unmount()
    app = null
    document.body.innerHTML = ''
  })

  it('shows all bucket values when a bar group is hovered', async () => {
    app = createApp(TrendChart, {
      trend: [{
        bucketStart: '2026-07-29T00:00:00',
        bucketEnd: '2026-07-29T23:59:59',
        createdCount: 4,
        completedCount: 2,
        dueCount: 1
      }]
    })
    app.use(i18n)
    app.mount('#app')

    const group = document.querySelector('.bar-group') as HTMLElement
    group.dispatchEvent(new MouseEvent('mouseenter', { bubbles: true, clientX: 120 }))
    await nextTick()

    const tooltip = document.body.querySelector('.chart-tooltip')
    expect(tooltip).not.toBeNull()
    expect(tooltip?.textContent).toContain('07-29')
    expect(tooltip?.textContent).toContain('4')
    expect(tooltip?.textContent).toContain('2')
    expect(tooltip?.textContent).toContain('1')
  })
})
