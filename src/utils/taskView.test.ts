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
    assigneeKey: 'user:2', assigneeName: 'bob',
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
    assigneeKey: 'user:1', assigneeName: 'alice',
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
    assigneeKey: null,
    projectId: 10,
    dueDate: 20,
    createdAt: 2,
    updatedAt: 20
  },
  {
    id: 'ENG-4',
    title: 'Delta',
    status: 'in_review',
    priority: 'medium',
    assigneeKey: 'user:2', assigneeName: 'bob',
    projectId: 20,
    dueDate: 35,
    createdAt: 4,
    updatedAt: 40
  }
]

const users: User[] = [
  { id: 1, username: 'alice' },
  { id: 2, username: 'bob' }
]

const baseConfig: ViewConfig = {
  layout: 'list',
  groupBy: 'status',
  listGroupBy: 'none',
  orderBy: 'updatedAt',
  orderDirection: 'desc',
  visibleProperties: ['priority'],
  showEmptyGroups: false,
  scope: 'all',
  showSubIssues: false,
  nestedSubIssues: true
}

describe('buildTaskGroups', () => {
  it('groups by status with stable order', () => {
    const groups = buildTaskGroups(tasks, baseConfig, users)

    expect(groups.map((group) => group.key)).toEqual(['todo', 'in_progress', 'in_review', 'done'])
    expect(groups[0]?.tasks.map((task) => task.id)).toEqual(['ENG-1'])
    expect(groups[1]?.tasks.map((task) => task.id)).toEqual(['ENG-2'])
    expect(groups[2]?.tasks.map((task) => task.id)).toEqual(['ENG-4'])
    expect(groups[3]?.tasks.map((task) => task.id)).toEqual(['ENG-3'])
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
    expect(groups[0]?.tasks.map((task) => task.id)).toEqual(['ENG-2', 'ENG-4', 'ENG-1', 'ENG-3'])
  })

  it('sorts active tasks by priority across todo, in-progress, and review statuses', () => {
    const groups = buildTaskGroups(
      tasks,
      {
        ...baseConfig,
        groupBy: 'none',
        scope: 'active',
        orderBy: 'priority',
        orderDirection: 'asc'
      },
      users
    )

    expect(groups).toHaveLength(1)
    expect(groups[0]?.tasks.map((task) => [task.id, task.status])).toEqual([
      ['ENG-1', 'todo'],
      ['ENG-2', 'in_progress'],
      ['ENG-4', 'in_review']
    ])
  })

  it('sorts active tasks by due date in both directions across statuses', () => {
    const ascending = buildTaskGroups(
      tasks,
      {
        ...baseConfig,
        groupBy: 'none',
        scope: 'active',
        orderBy: 'dueDate',
        orderDirection: 'asc'
      },
      users
    )
    const descending = buildTaskGroups(
      tasks,
      {
        ...baseConfig,
        groupBy: 'none',
        scope: 'active',
        orderBy: 'dueDate',
        orderDirection: 'desc'
      },
      users
    )

    expect(ascending[0]?.tasks.map((task) => task.id)).toEqual(['ENG-2', 'ENG-4', 'ENG-1'])
    expect(descending[0]?.tasks.map((task) => task.id)).toEqual(['ENG-1', 'ENG-4', 'ENG-2'])
  })

  it('keeps the configured cross-status order after a task changes status', () => {
    const changedTasks = tasks.map((task) =>
      task.id === 'ENG-1' ? { ...task, status: 'in_review' as const, updatedAt: 25 } : task
    )
    const groups = buildTaskGroups(
      changedTasks,
      {
        ...baseConfig,
        groupBy: 'none',
        scope: 'active',
        orderBy: 'updatedAt',
        orderDirection: 'desc'
      },
      users
    )

    expect(groups).toHaveLength(1)
    expect(groups[0]?.tasks.map((task) => task.id)).toEqual(['ENG-4', 'ENG-1', 'ENG-2'])
    expect(groups[0]?.tasks.map((task) => task.status)).toEqual([
      'in_review',
      'in_review',
      'in_progress'
    ])
  })

  it('can hide completed issues', () => {
    const groups = buildTaskGroups(
      tasks,
      { ...baseConfig, groupBy: 'status', scope: 'active' },
      users
    )

    expect(groups.map((group) => group.key)).toEqual(['todo', 'in_progress', 'in_review'])
    expect(groups.flatMap((group) => group.tasks.map((task) => task.id))).toEqual(['ENG-1', 'ENG-2', 'ENG-4'])
  })

  it('open_only excludes backlog (待规划) as well as terminal statuses', () => {
    const withBacklog: Task[] = [
      ...tasks,
      {
        id: 'ENG-4',
        title: 'Backlog item',
        status: 'backlog',
        priority: 'medium',
        assigneeKey: null,
        projectId: 10,
        createdAt: 4,
        updatedAt: 40
      }
    ]
    const groups = buildTaskGroups(
      withBacklog,
      { ...baseConfig, groupBy: 'status', scope: 'active' },
      users
    )

    expect(groups.map((group) => group.key)).toEqual(['todo', 'in_progress', 'in_review'])
    expect(groups.flatMap((group) => group.tasks.map((task) => task.id))).toEqual(['ENG-1', 'ENG-2', 'ENG-4'])
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

    expect(groups[0]?.tasks.map((task) => task.id)).toEqual(['ENG-3', 'ENG-1', 'ENG-4', 'ENG-2'])
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

  it('shows active children as flat rows when their done or backlog parents are out of scope', () => {
    const withFilteredParents: Task[] = [
      {
        id: 'ENG-10',
        numericId: 110,
        title: 'Done parent',
        status: 'done',
        priority: 'high',
        createdAt: 1,
        updatedAt: 1
      },
      {
        id: 'ENG-11',
        numericId: 111,
        title: 'Todo child',
        status: 'todo',
        priority: 'medium',
        parentId: '110',
        createdAt: 2,
        updatedAt: 2
      },
      {
        id: 'ENG-20',
        numericId: 120,
        title: 'Backlog parent',
        status: 'backlog',
        priority: 'high',
        createdAt: 3,
        updatedAt: 3
      },
      {
        id: 'ENG-21',
        numericId: 121,
        title: 'Review child',
        status: 'in_review',
        priority: 'low',
        parentId: '120',
        createdAt: 4,
        updatedAt: 4
      }
    ]
    const groups = buildTaskGroups(
      withFilteredParents,
      {
        ...baseConfig,
        groupBy: 'none',
        scope: 'active',
        showSubIssues: true
      },
      users
    )

    expect(groups).toHaveLength(1)
    expect(groups[0]?.tasks.map((task) => task.id)).toEqual(['ENG-21', 'ENG-11'])
    expect(groups[0]?.rows?.map((row) => [row.task.id, row.depth])).toEqual([
      ['ENG-21', 0],
      ['ENG-11', 0]
    ])
  })

  it('keeps same-status children nested and places different-status children in their own group once', () => {
    const withMixedStatusChildren: Task[] = [
      {
        id: 'ENG-30',
        numericId: 130,
        title: 'Todo parent',
        status: 'todo',
        priority: 'high',
        createdAt: 1,
        updatedAt: 1,
        subIssueCount: 2
      },
      {
        id: 'ENG-31',
        numericId: 131,
        title: 'Todo child',
        status: 'todo',
        priority: 'medium',
        parentId: '130',
        createdAt: 2,
        updatedAt: 2
      },
      {
        id: 'ENG-32',
        numericId: 132,
        title: 'Review child',
        status: 'in_review',
        priority: 'low',
        parentId: '130',
        createdAt: 3,
        updatedAt: 3
      }
    ]
    const groups = buildTaskGroups(
      withMixedStatusChildren,
      {
        ...baseConfig,
        groupBy: 'status',
        scope: 'active',
        showSubIssues: true
      },
      users
    )
    const todoRows = groups.find((group) => group.key === 'todo')?.rows ?? []
    const reviewRows = groups.find((group) => group.key === 'in_review')?.rows ?? []
    const visibleIds = [...todoRows, ...reviewRows].map((row) => row.task.id)

    expect(todoRows.map((row) => [row.task.id, row.depth])).toEqual([
      ['ENG-30', 0],
      ['ENG-31', 1]
    ])
    expect(reviewRows.map((row) => [row.task.id, row.depth])).toEqual([['ENG-32', 0]])
    expect(visibleIds).toHaveLength(new Set(visibleIds).size)
  })

  it('when taskFiltersActive, subtasks that pass the filter are grouped (not dropped as non-top-level)', () => {
    const withSubs: Task[] = [
      {
        id: 'ENG-1',
        numericId: 101,
        title: 'Parent',
        status: 'todo',
        priority: 'high',
        assigneeKey: 'user:1', assigneeName: 'alice',
        createdAt: 1,
        updatedAt: 1
      },
      {
        id: 'ENG-2',
        numericId: 102,
        title: 'Child',
        status: 'todo',
        priority: 'high',
        parentId: '101',
        assigneeKey: 'user:2', assigneeName: 'bob',
        createdAt: 2,
        updatedAt: 2
      }
    ]
    const filtered = withSubs.filter((t) => t.assigneeKey === 'user:2')
    const groups = buildTaskGroups(filtered, baseConfig, users, { taskFiltersActive: true })
    expect(groups).toHaveLength(1)
    expect(groups[0]?.tasks.map((t) => t.id)).toEqual(['ENG-2'])
  })

  it('when taskFiltersActive and showSubIssues, parent and child in same group keep subtask depth', () => {
    const withSubs: Task[] = [
      {
        id: 'ENG-1',
        numericId: 101,
        title: 'Parent',
        status: 'todo',
        priority: 'high',
        assigneeKey: 'user:1', assigneeName: 'alice',
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
        assigneeKey: 'user:1', assigneeName: 'alice',
        createdAt: 2,
        updatedAt: 2
      }
    ]
    const filtered = withSubs.filter((t) => t.assigneeKey === 'user:1')
    const groups = buildTaskGroups(
      filtered,
      { ...baseConfig, showSubIssues: true },
      users,
      { taskFiltersActive: true }
    )
    const rows = groups[0]?.rows ?? []
    expect(rows.map((r) => ({ id: r.task.id, depth: r.depth }))).toEqual([
      { id: 'ENG-1', depth: 0 },
      { id: 'ENG-2', depth: 1 }
    ])
    expect(rows[1]?.subtaskExpandPath).toEqual(['ENG-1'])
  })

  it('when searchActive, matching subtasks appear in groups as flat rows', () => {
    const withSubs: Task[] = [
      {
        id: 'ENG-1',
        numericId: 101,
        title: 'Parent only',
        status: 'todo',
        priority: 'high',
        createdAt: 1,
        updatedAt: 1
      },
      {
        id: 'ENG-2',
        numericId: 102,
        title: 'Child match',
        status: 'todo',
        priority: 'high',
        parentId: '101',
        createdAt: 2,
        updatedAt: 2
      }
    ]
    const filtered = withSubs.filter((t) => t.title.toLowerCase().includes('child'))
    const groups = buildTaskGroups(filtered, { ...baseConfig, showSubIssues: true }, users, {
      searchActive: true
    })
    expect(groups).toHaveLength(1)
    expect(groups[0]?.tasks.map((t) => t.id)).toEqual(['ENG-2'])
    const rows = groups[0]?.rows ?? []
    expect(rows.map((r) => ({ id: r.task.id, depth: r.depth }))).toEqual([{ id: 'ENG-2', depth: 0 }])
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
