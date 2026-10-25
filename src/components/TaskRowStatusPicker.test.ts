import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import TaskRowStatusPicker from './TaskRowStatusPicker.vue'

function flushPromises() {
  return Promise.resolve()
}

async function mountPicker(status: 'backlog' | 'todo' | 'in_progress' | 'in_review' | 'done' | 'canceled' | 'duplicate') {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(TaskRowStatusPicker, {
    taskId: 'ENG-1',
    status,
    onChange: vi.fn()
  })
  app.use(i18n)
  app.mount(host)
  await nextTick()
  await flushPromises()
  return {
    host,
    app,
    unmount() {
      app.unmount()
      host.remove()
    }
  }
}

describe('TaskRowStatusPicker', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('exposes change status semantics and status-specific classes', async () => {
    const view = await mountPicker('done')
    try {
      const trigger = view.host.querySelector('.task-row-status-trigger') as HTMLButtonElement
      expect(trigger).toBeTruthy()
      expect(trigger.className).toContain('task-row-status-trigger--done')
      expect(trigger.getAttribute('aria-label')).toContain('Change status')
      expect(trigger.title).toBe('Done')
    } finally {
      view.unmount()
    }
  })

  it('uses different identifiable classes for different statuses', async () => {
    const doneView = await mountPicker('done')
    const todoView = await mountPicker('todo')
    try {
      const doneTrigger = doneView.host.querySelector('.task-row-status-trigger') as HTMLButtonElement
      const todoTrigger = todoView.host.querySelector('.task-row-status-trigger') as HTMLButtonElement
      expect(doneTrigger.className).toContain('task-row-status-trigger--done')
      expect(todoTrigger.className).toContain('task-row-status-trigger--todo')
      expect(doneTrigger.className).not.toBe(todoTrigger.className)
    } finally {
      doneView.unmount()
      todoView.unmount()
    }
  })

  it('mounts status options only while the picker is open', async () => {
    const view = await mountPicker('todo')
    try {
      const trigger = view.host.querySelector<HTMLButtonElement>('.task-row-status-trigger')!
      expect(document.querySelector('.task-row-status-panel')).toBeNull()

      trigger.click()
      await nextTick()
      expect(document.querySelectorAll('.task-row-status-panel .custom-select-option')).toHaveLength(7)

      document.querySelector<HTMLElement>('.task-row-status-panel')!.dispatchEvent(
        new KeyboardEvent('keydown', { key: 'Escape', bubbles: true })
      )
      await nextTick()
      expect(document.querySelector('.task-row-status-panel')).toBeNull()
      expect(document.activeElement).toBe(trigger)
    } finally {
      view.unmount()
    }
  })
})
