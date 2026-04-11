import { createApp, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../i18n'
import type { Task, User } from '../types/domain'
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

async function mountPicker(task: Task, users: User[]) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const app = createApp(TaskRowAssigneePicker, {
    taskId: task.id,
    task,
    users,
    onPick: vi.fn()
  })
  app.use(i18n)
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

  it('fallback avatar uses background from getAvatarColorByUsername(username), not userId', async () => {
    const username = 'PaletteProbeUser'
    const task: Task = {
      id: 'ENG-1',
      title: 't',
      status: 'todo',
      priority: 'low',
      createdAt: 0,
      updatedAt: 0,
      assigneeId: null
    }
    const users: User[] = [{ id: 999, username }]
    const view = await mountPicker(task, users)
    try {
      const trigger = view.host.querySelector('.task-row-assignee-picker-trigger') as HTMLButtonElement
      expect(trigger).toBeTruthy()
      trigger.click()
      await nextTick()

      const fallback = document.querySelector(
        '.assignee-option-avatar.fallback'
      ) as HTMLElement | null
      expect(fallback).toBeTruthy()
      const domSig = `${fallback!.style.background || fallback!.style.backgroundColor}|${fallback!.style.color}`
      const expectedSig = styleSignature(getAvatarColorByUsername(username))
      expect(domSig).toBe(expectedSig)
    } finally {
      view.unmount()
    }
  })
})
