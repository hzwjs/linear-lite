import type { CollaborationBatchStart } from '../services/api/collaborationBatch'

const key = (userId: number, projectId: number) => `linear-lite.batch-start.${userId}.${projectId}`

/** 保留原启动请求，使刷新后的重试使用同一 operationId 与参数。 */
export function readPendingBatchStart(userId: number, projectId: number): CollaborationBatchStart | null {
  const raw = localStorage.getItem(key(userId, projectId))
  if (raw === null) return null
  const value: unknown = JSON.parse(raw)
  if (!value || typeof value !== 'object') throw new Error('保存的启动请求无效')
  const input = value as Record<string, unknown>
  if (Object.keys(input).sort().join(',') !== 'bindingId,bindingRevision,maxDurationMs,operationId,projectId,taskIds'
      || input.projectId !== projectId || typeof input.operationId !== 'string' || !input.operationId
      || !Array.isArray(input.taskIds) || !input.taskIds.length
      || !input.taskIds.every(id => Number.isSafeInteger(id) && id > 0)
      || new Set(input.taskIds).size !== input.taskIds.length
      || !['bindingId', 'bindingRevision', 'maxDurationMs'].every(field =>
        Number.isSafeInteger(input[field]) && (input[field] as number) > 0)) throw new Error('保存的启动请求无效')
  return value as CollaborationBatchStart
}

export function savePendingBatchStart(userId: number, input: CollaborationBatchStart): void {
  localStorage.setItem(key(userId, input.projectId), JSON.stringify(input))
}

export function clearPendingBatchStart(userId: number, projectId: number): void {
  localStorage.removeItem(key(userId, projectId))
}
