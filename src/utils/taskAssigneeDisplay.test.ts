import { describe, expect, it } from 'vitest'
import type { Task, User } from '../types/domain'
import { assigneeDisplayLabel, resolveAssigneeUser, taskHasAssignableDisplay } from './taskAssigneeDisplay'

const users: User[] = [
  { id: 1, username: 'alice' },
  { id: 2, username: 'bob' }
]

describe('taskAssigneeDisplay', () => {
  it('prefers system username over display name', () => {
    const task: Task = {
      id: 'ENG-1',
      title: 't',
      status: 'todo',
      priority: 'medium',
      createdAt: 0,
      updatedAt: 0,
      assigneeId: 1,
      assigneeDisplayName: 'should be ignored'
    }
    expect(resolveAssigneeUser(task, users)?.username).toBe('alice')
    expect(assigneeDisplayLabel(task, users, '—')).toBe('alice')
  })

  it('uses assigneeDisplayName when no system user', () => {
    const task: Task = {
      id: 'ENG-2',
      title: 't',
      status: 'todo',
      priority: 'medium',
      createdAt: 0,
      updatedAt: 0,
      assigneeId: null,
      assigneeDisplayName: '  导入名  '
    }
    expect(assigneeDisplayLabel(task, users, 'Unassigned')).toBe('导入名')
    expect(taskHasAssignableDisplay(task, users)).toBe(true)
  })
})
