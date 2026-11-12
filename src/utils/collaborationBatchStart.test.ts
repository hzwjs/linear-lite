import { beforeEach, describe, expect, it } from 'vitest'
import { clearPendingBatchStart, readPendingBatchStart, savePendingBatchStart } from './collaborationBatchStart'
import type { CollaborationBatchStart } from '../services/api/collaborationBatch'

const request: CollaborationBatchStart = { projectId: 12, operationId: 'op-1', bindingId: 4,
  bindingRevision: 2, taskIds: [9, 7], maxDurationMs: 3600000 }

describe('batch start recovery', () => {
  beforeEach(() => localStorage.clear())
  it('preserves the request and task order across reads, isolated by user and project', () => {
    savePendingBatchStart(7, request)
    expect(readPendingBatchStart(7, 12)).toEqual(request)
    expect(readPendingBatchStart(8, 12)).toBeNull()
    expect(readPendingBatchStart(7, 13)).toBeNull()
    clearPendingBatchStart(7, 12)
    expect(readPendingBatchStart(7, 12)).toBeNull()
  })
  it('rejects corrupt and cross-project stored input', () => {
    localStorage.setItem('linear-lite.batch-start.7.12', JSON.stringify({ ...request, projectId: 13 }))
    expect(() => readPendingBatchStart(7, 12)).toThrow()
  })
})
