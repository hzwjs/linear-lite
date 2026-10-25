import { describe, expect, it } from 'vitest'
import type { Task } from '../types/domain'
import { assigneeDisplayLabel } from './taskAssigneeDisplay'

describe('taskAssigneeDisplay', () => {
  it('uses the display value resolved by the service', () => {
    const task: Task = {
      id: 'ENG-1',
      title: 't',
      status: 'todo',
      priority: 'medium',
      createdAt: 0,
      updatedAt: 0,
      assigneeKey: 'user:1',
      assigneeName: 'alice'
    }
    expect(assigneeDisplayLabel(task, '—')).toBe('alice')
  })

  it('uses the unassigned label when the service returns no owner', () => {
    const task: Task = {
      id: 'ENG-2',
      title: 't',
      status: 'todo',
      priority: 'medium',
      createdAt: 0,
      updatedAt: 0,
      assigneeKey: null,
      assigneeName: null
    }
    expect(assigneeDisplayLabel(task, 'Unassigned')).toBe('Unassigned')
  })

  it('does not present an owner with no resolved name as unassigned', () => {
    const task: Task = {
      id: 'ENG-3',
      title: 't',
      status: 'todo',
      priority: 'medium',
      createdAt: 0,
      updatedAt: 0,
      assigneeKey: 'external:3',
      assigneeName: null
    }
    expect(assigneeDisplayLabel(task, 'Unassigned')).toBe('')
  })

  it('treats an omitted key as the same unassigned value as null', () => {
    const task: Task = {
      id: 'ENG-4',
      title: 't',
      status: 'todo',
      priority: 'medium',
      createdAt: 0,
      updatedAt: 0
    }
    expect(assigneeDisplayLabel(task, 'Unassigned')).toBe('Unassigned')
  })
})
