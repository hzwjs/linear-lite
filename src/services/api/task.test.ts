import { afterEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { taskApi } from './task'

describe('task API assignee mapping', () => {
  afterEach(() => vi.restoreAllMocks())

  it('preserves an unassigned null key from the service response', async () => {
    vi.spyOn(api, 'get').mockResolvedValue({
      data: {
        code: 200,
        data: {
          id: 1,
          taskKey: 'ENG-1',
          title: 'Task',
          status: 'todo',
          priority: 'medium',
          projectId: 2,
          assigneeKey: null,
          assigneeName: null,
          agentExecutionEnabled: false,
          inputRevision: 1,
          writeScopes: [],
          publicContextRefs: [],
          createdAt: '2026-10-25T00:00:00',
          updatedAt: '2026-10-25T00:00:00'
        }
      }
    } as never)

    await expect(taskApi.get('ENG-1')).resolves.toMatchObject({ assigneeKey: null })
  })

  it('preserves collaboration fields from detail and list responses', async () => {
    const task = {
      id: 1, taskKey: 'ENG-1', title: 'Task', status: 'todo', priority: 'medium', projectId: 2,
      agentExecutionEnabled: true, inputRevision: 7, writeScopes: ['src/', 'docs/说明.md'],
      publicContextRefs: [{ documentId: 101, version: 3 }],
      createdAt: '2026-10-25T00:00:00', updatedAt: '2026-10-25T00:00:00'
    }
    vi.spyOn(api, 'get')
      .mockResolvedValueOnce({ data: { code: 200, data: task } } as never)
      .mockResolvedValueOnce({ data: { code: 200, data: [task] } } as never)

    const expected = { agentExecutionEnabled: true, inputRevision: 7, writeScopes: ['src/', 'docs/说明.md'],
      publicContextRefs: [{ documentId: 101, version: 3 }] }
    await expect(taskApi.get('ENG-1')).resolves.toMatchObject(expected)
    await expect(taskApi.list(2)).resolves.toEqual([expect.objectContaining(expected)])
  })
})
