import { describe, expect, it } from 'vitest'
import type { Task, User } from '../types/domain'
import type { ViewConfig } from './viewPreference'
import { buildTaskGroups, filterVisibleTaskRows, getAdjacentTaskIds } from './taskView'

const tasks: Task[] = [
  {
    id: 'ENG-3',
    title: 'Gamma',
    status: 'done',
    priority: 'low',
    assigneeId: 2,
    projectId: 20,
    createdAt: 3,
    updatedAt: 30,
    completedAt: 30
  },
  {
    id: 'ENG-1',
    title: 'Alpha',
    status: 'todo',
    priority: 'urgent',
    assigneeId: 1,
    projectId: 10,
    dueDate: 50,
    createdAt: 1,
    updatedAt: 10
  },
  {
    id: 'ENG-2',
    title: 'Beta',
    status: 'in_progress',
    priority: 'high',
    assigneeId: null,
    projectId: 10,
    dueDate: 20,
    createdAt: 2,
    updatedAt: 20
  }
]

const users: User[] = [
  { id: 1, username: 'alice' },
  { id: 2, username: 'bob' }
]

const baseConfig: ViewConfig = {
  layout: 'list',
  groupBy: 'status',
  orderBy: 'updatedAt',
  orderDirection: 'desc',
  visibleProperties: ['priority'],
  showEmptyGroups: false,
  completedVisibility: 'all',
  showSubIssues: false,
  nestedSubIssues: true
}

describe('buildTaskGroups', () => {
  it('groups by status with stable order', () => {
    const groups = buildTaskGroups(tasks, baseConfig, users)

    expect(groups.map((group) => group.key)).toEqual(['todo', 'in_progress', 'done'])
    expect(groups[0]?.tasks.map((task) => task.id)).toEqual(['ENG-1'])
    expect(groups[1]?.tasks.map((task) => task.id)).toEqual(['ENG-2'])
    expect(groups[2]?.tasks.map((task) => task.id)).toEqual(['ENG-3'])
  })

  it('can group by assignee label', () => {
    const groups = buildTaskGroups(tasks, { ...baseConfig, groupBy: 'assignee' }, users)

    expect(groups.map((group) => group.label)).toEqual(['alice', 'bob', 'Unassigned'])
  })

  it('can order by due date ascending', () => {
    const groups = buildTaskGroups(
      tasks,
      { ...baseConfig, groupBy: 'none', orderBy: 'dueDate', orderDirection: 'asc' },
      users
    )

    expect(groups).toHaveLength(1)
    expect(groups[0]?.tasks.map((task) => task.id)).toEqual(['ENG-2', 'ENG-1', 'ENG-3'])
  })

  it('can hide completed issues', () => {
    const groups = buildTaskGroups(
      tasks,
      { ...baseConfig, groupBy: 'status', completedVisibility: 'open_only' },
      users
    )

    expect(groups.map((group) => group.key)).toEqual(['todo', 'in_progress'])
    expect(groups.flatMap((group) => group.tasks.map((task) => task.id))).toEqual(['ENG-1', 'ENG-2'])
  })

  it('can include empty status groups when configured', () => {
    const todoOnly = tasks.filter((task) => task.status === 'todo')
    const groups = buildTaskGroups(
      todoOnly,
      { ...baseConfig, groupBy: 'status', showEmptyGroups: true },
      users
    )

    expect(groups.map((group) => ({ key: group.key, count: group.tasks.length }))).toEqual([
      { key: 'backlog', count: 0 },
      { key: 'todo', count: 1 },
      { key: 'in_progress', count: 0 },
      { key: 'in_review', count: 0 },
      { key: 'done', count: 0 },
      { key: 'canceled', count: 0 },
      { key: 'duplicate', count: 0 }
    ])
  })

  it('supports descending due date ordering', () => {
    const groups = buildTaskGroups(
      tasks,
      { ...baseConfig, groupBy: 'none', orderBy: 'dueDate', orderDirection: 'desc' },
      users
    )

    expect(groups[0]?.tasks.map((task) => task.id)).toEqual(['ENG-3', 'ENG-1', 'ENG-2'])
  })

  it('when showSubIssues, rows include subtaskExpandPath for nested list collapse', () => {
    const withSubs: Task[] = [
      {
        id: 'ENG-1',
        numericId: 101,
        title: 'Parent',
        status: 'todo',
        priority: 'high',
        createdAt: 1,
        updatedAt: 1,
        subIssueCount: 1
      },
      {
        id: 'ENG-2',
        numericId: 102,
        title: 'Child',
        status: 'todo',
        priority: 'high',
        parentId: '101',
        createdAt: 2,
        updatedAt: 2
      }
    ]
    const groups = buildTaskGroups(withSubs, { ...baseConfig, showSubIssues: true }, users)
    const rows = groups[0]?.rows ?? []
    expect(rows.map((r) => r.task.id)).toEqual(['ENG-1', 'ENG-2'])
    expect(rows[0]?.depth).toBe(0)
    expect(rows[0]?.subtaskExpandPath).toBeUndefined()
    expect(rows[1]?.depth).toBe(1)
    expect(rows[1]?.subtaskExpandPath).toEqual(['ENG-1'])

    const collapsed = filterVisibleTaskRows(rows, {})
    expect(collapsed.map((r) => r.task.id)).toEqual(['ENG-1'])

    const expanded = filterVisibleTaskRows(rows, { 'ENG-1': true })
    expect(expanded.map((r) => r.task.id)).toEqual(['ENG-1', 'ENG-2'])
  })
})

describe('getAdjacentTaskIds', () => {
  it('returns prev and next task ids around current id', () => {
    expect(getAdjacentTaskIds(['ENG-1', 'ENG-2', 'ENG-3'], 'ENG-2')).toEqual({
      previousTaskId: 'ENG-1',
      nextTaskId: 'ENG-3',
      position: 2,
      total: 3
    })
  })

  it('handles bounds and missing current id', () => {
    expect(getAdjacentTaskIds(['ENG-1', 'ENG-2'], 'ENG-1')).toEqual({
      previousTaskId: null,
      nextTaskId: 'ENG-2',
      position: 1,
      total: 2
    })
    expect(getAdjacentTaskIds(['ENG-1', 'ENG-2'], 'ENG-9')).toEqual({
      previousTaskId: null,
      nextTaskId: null,
      position: null,
      total: 2
    })
  })
})
