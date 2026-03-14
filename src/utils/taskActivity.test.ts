import { describe, expect, it } from 'vitest'
import { formatTaskActivity, getActivityAvatarLabel } from './taskActivity'
import type { TaskActivity } from '../types/domain'

describe('formatTaskActivity', () => {
  it('formats status change with old and new values', () => {
    const activity: TaskActivity = {
      id: 1,
      actionType: 'changed',
      fieldName: 'status',
      oldValue: 'todo',
      newValue: 'in_progress',
      actorName: 'alice',
      createdAt: 1
    }

    expect(formatTaskActivity(activity)).toBe('alice changed status from Todo to In Progress')
  })

  it('formats title change without noisy values', () => {
    const activity: TaskActivity = {
      id: 2,
      actionType: 'changed',
      fieldName: 'title',
      oldValue: 'Old',
      newValue: 'New',
      actorName: 'alice',
      createdAt: 1
    }

    expect(formatTaskActivity(activity)).toBe('alice changed title')
  })

  it('formats unfavorite action', () => {
    const activity: TaskActivity = {
      id: 3,
      actionType: 'unfavorited',
      actorName: 'alice',
      createdAt: 1
    }

    expect(formatTaskActivity(activity)).toBe('alice removed the issue from favorites')
  })

  it('builds avatar initials from actor name', () => {
    expect(getActivityAvatarLabel('Admin User')).toBe('A')
    expect(getActivityAvatarLabel('alice')).toBe('A')
    expect(getActivityAvatarLabel('')).toBe('?')
  })
})
