import { createApp, nextTick } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'
import type { Task } from '../../types/domain'
import MobileTaskRow from './MobileTaskRow.vue'

const taskBase: Task = {
  id: 'ENG-1',
  title: 'Test task',
  status: 'todo',
  priority: 'medium',
  createdAt: 0,
  updatedAt: 0,
  assigneeKey: 'external:1',
  assigneeName: null
}

afterEach(() => {
  document.body.innerHTML = ''
})

describe('MobileTaskRow assignee display', () => {
  it('uses the key for avatar presence and does not label an unresolved owner as unassigned', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(MobileTaskRow, { task: taskBase })
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.mobile-avatar--small')).not.toBeNull()
    expect(host.querySelector('.mobile-avatar--small')?.getAttribute('aria-label')).toBe('负责人：')
    app.unmount()
  })

  it('shows the unassigned label and no avatar when the key is null', async () => {
    const host = document.createElement('div')
    document.body.appendChild(host)
    const app = createApp(MobileTaskRow, { task: { ...taskBase, assigneeKey: null } })
    app.mount(host)
    await nextTick()

    expect(host.querySelector('.mobile-avatar--small')).toBeNull()
    app.unmount()
  })
})
