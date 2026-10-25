import { createApp, nextTick } from 'vue'
import { createPinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import type { AssigneeOption, Task } from '../types/domain'
import { getAvatarColorByUsername } from '../utils/avatar'
import TaskRowAssigneePicker from './TaskRowAssigneePicker.vue'

function styleSignature(style: { background?: string; color?: string }): string {
  const probe = document.createElement('div')
  Object.assign(probe.style, style)
  document.body.appendChild(probe)
  const bg = probe.style.background || probe.style.backgroundColor
  const fg = probe.style.color
  probe.remove()
  return `${bg}|${fg}`
}

async function mountPicker(task: Task, assignees: AssigneeOption[]) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(TaskRowAssigneePicker, {
    taskId: task.id,
    task,
    assignees,
    onPick: vi.fn()
  })
  app.use(i18n)
  app.use(createPinia())
  app.mount(host)
  await nextTick()
  return {
    host,
    app,
    unmount() {
      app.unmount()
      host.remove()
    }
  }
}

describe('TaskRowAssigneePicker', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    vi.clearAllMocks()
    i18n.global.locale.value = 'en'
  })

  it('selected avatar trigger uses Chinese initials from getInitials()', async () => {
    const task: Task = {
      id: 'ENG-2',
      title: 't',
      status: 'todo',
      priority: 'low',
      createdAt: 0,
      updatedAt: 0,
      assigneeKey: 'user:101', assigneeName: '黄志文'
    }
    const assignees: AssigneeOption[] = [{ key: 'user:101', name: '黄志文', principalType: 'human' }]
    const view = await mountPicker(task, assignees)
    try {
      const avatar = view.host.querySelector('.assignee-trigger .assignee-avatar') as HTMLElement
      expect(avatar).toBeTruthy()
      expect(avatar.textContent?.trim()).toBe('志文')
    } finally {
      view.unmount()
    }
  })

})
