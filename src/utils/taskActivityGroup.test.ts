import { describe, expect, it } from 'vitest'
import type { TaskActivity } from '../types/domain'
import { formatTaskActivityDisplayItem, groupTaskActivitiesForDisplay } from './taskActivityGroup'

function activity(
  id: number,
  actorName: string,
  fieldName: string | null,
  createdAt: number
): TaskActivity {
  return {
    id,
    actionType: 'changed',
    actorName,
    fieldName,
    oldValue: 'old',
    newValue: 'new',
    createdAt
  }
}

describe('groupTaskActivitiesForDisplay', () => {
  it('groups adjacent changes by the same actor and field within the short window', () => {
    const base = Date.parse('2026-04-10T12:00:00+08:00')
    const activities = [
      activity(3, 'alice', 'status', base - 2 * 60 * 1000),
      activity(2, 'alice', 'status', base - 4 * 60 * 1000),
      activity(1, 'alice', 'priority', base - 6 * 60 * 1000)
    ]

    expect(groupTaskActivitiesForDisplay(activities)).toEqual([
      {
        kind: 'grouped_changes',
        actorName: 'alice',
        fieldName: 'status',
        activities: [activities[0], activities[1]]
      },
      {
        kind: 'single',
        actorName: 'alice',
        activity: activities[2]
      }
    ])
  })

  it('formats grouped changes as a multi-change summary instead of a single from-to event', () => {
    const base = Date.parse('2026-04-10T12:00:00+08:00')
    const activities = [
      activity(3, 'alice', 'status', base - 1 * 60 * 1000),
      activity(2, 'alice', 'status', base - 2 * 60 * 1000),
      activity(1, 'alice', 'status', base - 3 * 60 * 1000)
    ]
    const [group] = groupTaskActivitiesForDisplay(activities)

    expect(group?.kind).toBe('grouped_changes')
    expect(group && formatTaskActivityDisplayItem(group)).toContain('×3')
    expect(group && formatTaskActivityDisplayItem(group)).not.toContain('from')
    expect(group && formatTaskActivityDisplayItem(group)).not.toContain('to')
  })

  it('does not group when the time gap exceeds the window or the field changes', () => {
    const base = Date.parse('2026-04-10T12:00:00+08:00')
    const activities = [
      activity(5, 'alice', 'status', base - 1 * 60 * 1000),
      activity(4, 'alice', 'status', base - 7 * 60 * 1000),
      activity(3, 'alice', 'priority', base - 8 * 60 * 1000),
      activity(2, 'alice', 'priority', base - 9 * 60 * 1000)
    ]

    expect(groupTaskActivitiesForDisplay(activities)).toEqual([
      {
        kind: 'single',
        actorName: 'alice',
        activity: activities[0]
      },
      {
        kind: 'single',
        actorName: 'alice',
        activity: activities[1]
      },
      {
        kind: 'grouped_changes',
        actorName: 'alice',
        fieldName: 'priority',
        activities: [activities[2], activities[3]]
      }
    ])
  })

  it('sorts unstable input before grouping so older unsorted items still collapse correctly', () => {
    const base = Date.parse('2026-04-10T12:00:00+08:00')
    const activities = [
      activity(1, 'alice', 'status', base - 6 * 60 * 1000),
      activity(3, 'alice', 'status', base - 2 * 60 * 1000),
      activity(2, 'alice', 'status', base - 4 * 60 * 1000)
    ]

    expect(groupTaskActivitiesForDisplay(activities)).toEqual([
      {
        kind: 'grouped_changes',
        actorName: 'alice',
        fieldName: 'status',
        activities: [activities[1], activities[2], activities[0]]
      }
    ])
  })

  it('keeps the actor label stable within a group', () => {
    const base = Date.parse('2026-04-10T12:00:00+08:00')
    const activities = [
      activity(2, '  Alice Chen  ', 'description', base - 30 * 1000),
      activity(1, 'Alice Chen', 'description', base - 90 * 1000)
    ]

    expect(groupTaskActivitiesForDisplay(activities)).toEqual([
      {
        kind: 'grouped_changes',
        actorName: 'Alice Chen',
        fieldName: 'description',
        activities
      }
    ])
  })

  it('does not merge across unrelated adjacent activity items', () => {
    const base = Date.parse('2026-04-10T12:00:00+08:00')
    const activities = [
      activity(4, 'alice', 'status', base - 1 * 60 * 1000),
      {
        id: 3,
        actionType: 'changed',
        actorName: 'bob',
        fieldName: 'status',
        oldValue: 'Todo',
        newValue: 'Done',
        createdAt: base - 2 * 60 * 1000
      } satisfies TaskActivity,
      activity(2, 'alice', 'status', base - 3 * 60 * 1000)
    ]

    expect(groupTaskActivitiesForDisplay(activities)).toEqual([
      {
        kind: 'single',
        actorName: 'alice',
        activity: activities[0]
      },
      {
        kind: 'single',
        actorName: 'bob',
        activity: activities[1]
      },
      {
        kind: 'single',
        actorName: 'alice',
        activity: activities[2]
      }
    ])
  })
})
