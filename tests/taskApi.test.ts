import { describe, it, expect, beforeEach, vi } from 'vitest'
import { taskApi } from '../src/services/api/task'

const mockApiResponse = <T>(data: T) => ({
  data: { code: 200, data }
})

const MOCK_PROJECT_ID = 1

const mockApiTask = {
  id: 1,
  taskKey: 'ENG-1',
  title: 'Seed',
  description: null,
  status: 'todo',
  priority: 'low',
  projectId: 1,
  creatorId: 1,
  assigneeId: null,
  createdAt: '2025-01-01T00:00:00',
  updatedAt: '2025-01-01T00:00:00'
}

vi.mock('../src/services/api/index', () => ({
  api: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn()
  },
  unwrap: vi.fn((res: { data: { data: unknown } }) => res.data.data)
}))

const { api } = await import('../src/services/api/index')

beforeEach(() => {
  vi.mocked(api.get).mockReset()
  vi.mocked(api.post).mockReset()
  vi.mocked(api.put).mockReset()
})

describe('taskApi', () => {
  it('list: GET /tasks?projectId=1', async () => {
    vi.mocked(api.get).mockResolvedValue(mockApiResponse([mockApiTask]))

    const tasks = await taskApi.list(MOCK_PROJECT_ID)

    expect(api.get).toHaveBeenCalledWith('/tasks', { params: { projectId: MOCK_PROJECT_ID } })
    expect(tasks).toHaveLength(1)
    expect(tasks[0].id).toBe('ENG-1')
    expect(tasks[0].title).toBe('Seed')
    expect(tasks[0].status).toBe('todo')
    expect(tasks[0].priority).toBe('low')
  })

  it('create: POST /tasks', async () => {
    const created = { ...mockApiTask, taskKey: 'ENG-2', title: 'Test Create' }
    vi.mocked(api.post).mockResolvedValue(mockApiResponse(created))

    const task = await taskApi.create({
      projectId: MOCK_PROJECT_ID,
      title: 'Test Create',
      status: 'todo',
      priority: 'low'
    })

    expect(api.post).toHaveBeenCalledWith('/tasks', {
      projectId: MOCK_PROJECT_ID,
      title: 'Test Create',
      status: 'todo',
      priority: 'low'
    })
    expect(task.id).toBe('ENG-2')
    expect(task.title).toBe('Test Create')
  })

  it('update: PUT /tasks/ENG-1', async () => {
    const updated = { ...mockApiTask, title: 'New Title', priority: 'high' }
    vi.mocked(api.put).mockResolvedValue(mockApiResponse(updated))

    const result = await taskApi.update('ENG-1', {
      title: 'New Title',
      priority: 'high'
    })

    expect(api.put).toHaveBeenCalledWith('/tasks/ENG-1', {
      title: 'New Title',
      priority: 'high'
    })
    expect(result.title).toBe('New Title')
    expect(result.priority).toBe('high')
  })

  it('transition: PUT /tasks/ENG-1 with status', async () => {
    const transitioned = { ...mockApiTask, status: 'done' }
    vi.mocked(api.put).mockResolvedValue(mockApiResponse(transitioned))

    const result = await taskApi.update('ENG-1', { status: 'done' })

    expect(api.put).toHaveBeenCalledWith('/tasks/ENG-1', { status: 'done' })
    expect(result.status).toBe('done')
  })
})
