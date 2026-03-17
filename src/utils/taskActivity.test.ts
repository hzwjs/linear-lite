import { describe, expect, it, beforeEach, afterEach } from 'vitest'
import { i18n } from '../i18n'
import { formatTaskActivity, getActivityAvatarLabel } from './taskActivity'
import type { TaskActivity } from '../types/domain'

describe('formatTaskActivity', () => {
  beforeEach(() => {
    i18n.global.locale.value = 'en'
  })

  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

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

  it('returns translated messages when locale is zh-CN', () => {
    i18n.global.locale.value = 'zh-CN'
    const activity: TaskActivity = {
      id: 4,
      actionType: 'created',
      actorName: 'alice',
      createdAt: 1
    }

    expect(formatTaskActivity(activity)).toBe('alice 创建了任务')
  })

  it('falls back to raw enum values when translation is missing', () => {
    const activity: TaskActivity = {
      id: 5,
      actionType: 'changed',
      fieldName: 'status',
      oldValue: 'weird-status',
      newValue: 'another-status',
      actorName: 'alice',
      createdAt: 1
    }

    expect(formatTaskActivity(activity)).toContain('weird-status')
    expect(formatTaskActivity(activity)).toContain('another-status')
  })

  it('builds avatar initials from actor name (2 chars)', () => {
    expect(getActivityAvatarLabel('Admin User')).toBe('AU')
    expect(getActivityAvatarLabel('alice')).toBe('AL')
    expect(getActivityAvatarLabel('')).toBe('?')
  })
})
