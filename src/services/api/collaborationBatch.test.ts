import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api } from './index'
import { collaborationBatchApi, type CollaborationBatchStart } from './collaborationBatch'

vi.mock('./index', () => ({
  api: { post: vi.fn() }, unwrap: (response: { data: { data: unknown } }) => response.data.data,
  toApiError: (error: unknown) => error,
}))
const reply = (data: unknown) => ({ data: { code: 200, data } })

describe('collaboration batch API', () => {
  beforeEach(() => vi.resetAllMocks())
  it('preserves the exact start payload and uses the same endpoint for batch stop', async () => {
    const input: CollaborationBatchStart = { projectId: 12, operationId: 'op-1', bindingId: 4, bindingRevision: 3,
      taskIds: [9, 7], maxDurationMs: 3600000 }
    vi.mocked(api.post).mockResolvedValue(reply({ batchId: 'batch-1' }))
    await collaborationBatchApi.start(input)
    await collaborationBatchApi.stop(12, 'batch-1', 'stop-1')
    expect(api.post).toHaveBeenNthCalledWith(1, '/collaboration/batches/start', input)
    expect(api.post).toHaveBeenNthCalledWith(2, '/collaboration/batches/stop', { projectId: 12, batchId: 'batch-1', operationId: 'stop-1' })
    expect(Object.keys(input).sort()).toEqual(['bindingId', 'bindingRevision', 'maxDurationMs', 'operationId', 'projectId', 'taskIds'])
  })
  it('submits authorization against the specific server request', async () => {
    const response = { batchId: 'new-batch', lifecycle: 'queued', desiredIntent: 'RUN', intentRevision: 1, displayState: 'WAITING_ACCEPT' }
    vi.mocked(api.post).mockResolvedValue(reply(response))
    await expect(collaborationBatchApi.authorizeGoal(12, 'batch-1', 'request-1')).resolves.toEqual(response)
    expect(api.post).toHaveBeenCalledWith('/collaboration/batches/authorize-goal',
      { projectId: 12, batchId: 'batch-1', requestOperationId: 'request-1' })
  })
  it('reads every page through the shared batch DTO', async () => {
    vi.mocked(api.post).mockResolvedValueOnce(reply({ items: [{ batchId: 'one' }], hasMore: true, nextCursor: 'one' }))
      .mockResolvedValueOnce(reply({ items: [{ batchId: 'two' }], hasMore: false, nextCursor: null }))
    expect(await collaborationBatchApi.listAll(12)).toEqual([{ batchId: 'one' }, { batchId: 'two' }])
    expect(api.post).toHaveBeenNthCalledWith(2, '/collaboration/batches/list', { projectId: 12, limit: 50, beforeBatchId: 'one' })
  })
  it('rejects a repeated cursor instead of looping', async () => {
    vi.mocked(api.post).mockResolvedValue(reply({ items: [], hasMore: true, nextCursor: 'same' }))
    await expect(collaborationBatchApi.listAll(12)).rejects.toThrow('批次分页无效')
  })
})
