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
})
