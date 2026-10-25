import { createApp, computed, h, nextTick } from 'vue'
import { createPinia, setActivePinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import type { Task, User } from '../types/domain'
import { buildTaskGroups } from '../utils/taskView'
import { DEFAULT_VIEW_CONFIG } from '../utils/viewPreference'
import TaskListView from './TaskListView.vue'
import { useViewModeStore } from '../store/viewModeStore'

describe('task scope switching workload', () => {
  it('keeps every list scope flat with bounded rows and closed editors', async () => {
    localStorage.clear()
    const pinia = createPinia()
    setActivePinia(pinia)
    const tasks: Task[] = Array.from({ length: 1000 }, (_, index) => ({
      id: `PERF-${index}`, numericId: index + 1, projectId: 7,
      title: `Task ${index}`, status: index < 100 ? 'in_progress' : index < 200 ? 'backlog' : 'done',
      priority: 'medium', assigneeKey: `user:${index % 20 + 1}`, assigneeName: `User ${index % 20 + 1}`,
      createdAt: index, updatedAt: index, subIssueCount: 0
    }))
    const users: User[] = Array.from({ length: 20 }, (_, index) => ({ id: index + 1, username: `User ${index}` }))
    const viewStore = useViewModeStore()
    let groupingMs = 0
    const groups = computed(() => {
      const started = performance.now()
      const result = buildTaskGroups(tasks, viewStore.effectiveViewConfig, users)
      groupingMs = performance.now() - started
      return result
    })
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp({
      setup: () => () => h(TaskListView, {
        groups: groups.value, grouped: viewStore.groupBy !== 'none', projectId: 7,
        expandGroupsOnEntry: viewStore.viewConfig.scope === 'active',
        visibleProperties: DEFAULT_VIEW_CONFIG.visibleProperties
      })
    })
    app.use(pinia).use(i18n)
    const started = performance.now()
    app.mount(host)
    await nextTick()
    const scrollRoot = host.querySelector<HTMLElement>('.list-view-scroll')!
    Object.defineProperty(scrollRoot, 'clientHeight', { configurable: true, value: 420 })
    scrollRoot.dispatchEvent(new Event('scroll'))
    await nextTick()
    const activeMs = performance.now() - started
    try {
      const before = performance.now()
      viewStore.setScope('all')
      await nextTick()
      const switchingMs = performance.now() - before
      const rows = host.querySelectorAll('.task-row').length
      const statusOptions = document.querySelectorAll('.task-row-status-panel [role="option"]').length
      console.info(JSON.stringify({ activeMs, switchingMs, groupingMs, rows, statusOptions, nodes: document.querySelectorAll('*').length }))
      expect(rows).toBeGreaterThan(10)
      expect(rows).toBeLessThan(80)
      expect(groups.value).toHaveLength(1)
      expect(groups.value[0]?.tasks).toHaveLength(1000)
      expect(host.querySelectorAll('.group-header')).toHaveLength(0)
      expect(statusOptions).toBe(0)
      expect(document.querySelectorAll('.assignee-popover, .custom-date-picker-panel')).toHaveLength(0)

      viewStore.setScope('backlog')
      await nextTick()
      expect(groups.value).toHaveLength(1)
      expect(groups.value[0]?.tasks).toHaveLength(100)
      expect(host.querySelectorAll('.group-header')).toHaveLength(0)
      expect(host.querySelectorAll('.task-row').length).toBeGreaterThan(10)
      expect(host.querySelectorAll('.task-row').length).toBeLessThan(80)

      viewStore.setScope('all')
      viewStore.setGroupBy('status')
      await nextTick()
      expect(host.querySelectorAll('.group-header')).toHaveLength(3)
      expect(host.querySelectorAll('.task-row').length).toBeLessThan(80)
    } finally {
      app.unmount()
      host.remove()
      vi.restoreAllMocks()
    }
  }, 30000)
})
