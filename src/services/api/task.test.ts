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
          createdAt: '2026-10-25T00:00:00',
          updatedAt: '2026-10-25T00:00:00'
        }
      }
    } as never)

    await expect(taskApi.get('ENG-1')).resolves.toMatchObject({ assigneeKey: null })
  })
})
