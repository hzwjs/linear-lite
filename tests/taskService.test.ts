import { describe, it, expect, beforeEach, vi } from 'vitest'
import { taskService } from '../src/services/taskService'
import { taskApi } from '../src/services/api/task'

vi.mock('../src/services/api/task', () => ({
  taskApi: {
    list: vi.fn(),
    create: vi.fn(),
    update: vi.fn()
  }
}))

const MOCK_PROJECT_ID = 1

beforeEach(() => {
  vi.mocked(taskApi.list).mockReset()
  vi.mocked(taskApi.create).mockReset()
  vi.mocked(taskApi.update).mockReset()
})

describe('taskService', () => {
  it('should list tasks by projectId', async () => {
    const mockTasks = [
      {
        id: 'ENG-1',
        title: 'Seed',
        status: 'todo' as const,
        priority: 'low' as const,
        createdAt: 0,
        updatedAt: 0
      }
    ]
    vi.mocked(taskApi.list).mockResolvedValue(mockTasks)

    const tasks = await taskService.listTasks(MOCK_PROJECT_ID)
    expect(tasks.length).toBeGreaterThan(0)
    expect(tasks[0]).toHaveProperty('id')
    expect(tasks[0]).toHaveProperty('title')
    expect(taskApi.list).toHaveBeenCalledWith(MOCK_PROJECT_ID)
  })

  it('should create a task', async () => {
    const created = {
      id: 'ENG-2',
      title: 'Test Create',
      status: 'todo' as const,
      priority: 'low' as const,
      createdAt: Date.now(),
      updatedAt: Date.now()
    }
    vi.mocked(taskApi.create).mockResolvedValue(created)

    const task = await taskService.createTask({
      projectId: MOCK_PROJECT_ID,
      title: 'Test Create',
      status: 'todo',
      priority: 'low'
    })
    expect(task.id).toBeDefined()
    expect(task.title).toBe('Test Create')
    expect(task.status).toBe('todo')
    expect(taskApi.create).toHaveBeenCalledWith(
      expect.objectContaining({
        projectId: MOCK_PROJECT_ID,
        title: 'Test Create',
        status: 'todo',
        priority: 'low'
      })
    )
  })

  it('should update a task', async () => {
    const updated = {
      id: 'ENG-1',
      title: 'New Title',
      status: 'todo' as const,
      priority: 'high' as const,
      createdAt: 0,
      updatedAt: Date.now()
    }
    vi.mocked(taskApi.update).mockResolvedValue(updated)

    const result = await taskService.updateTask('ENG-1', {
      title: 'New Title',
      priority: 'high'
    })
    expect(result.title).toBe('New Title')
    expect(result.priority).toBe('high')
    expect(result.status).toBe('todo')
    expect(taskApi.update).toHaveBeenCalledWith('ENG-1', {
      title: 'New Title',
      priority: 'high',
      description: undefined,
      status: undefined,
      assigneeId: undefined
    })
  })

  it('should transition task status', async () => {
    const transitioned = {
      id: 'ENG-1',
      title: 'Transition Me',
      status: 'done' as const,
      priority: 'low' as const,
      createdAt: 0,
      updatedAt: Date.now()
    }
    vi.mocked(taskApi.update).mockResolvedValue(transitioned)

    const result = await taskService.transitionTask('ENG-1', 'done')
    expect(result.status).toBe('done')
    expect(taskApi.update).toHaveBeenCalledWith('ENG-1', { status: 'done' })
  })
})
