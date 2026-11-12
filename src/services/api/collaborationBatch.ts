import { api, toApiError, unwrap } from './index'
import type { ApiResponse, PrerequisiteReviewStatus } from './types'

export interface CollaborationBatchStart {
  projectId: number
  operationId: string
  bindingId: number
  bindingRevision: number
  taskIds: number[]
  maxDurationMs: number
}

export interface CollaborationBatchSingleStart {
  projectId: number
  operationId: string
  taskId: number
  bindingId?: number
  maxDurationMs?: number
}

export interface CollaborationBatchCommand {
  batchId: string
  lifecycle: 'queued' | 'accepted' | 'ended'
  desiredIntent: 'RUN' | 'STOP'
  intentRevision: number
  displayState: string
}

export interface CollaborationBatchSummary extends CollaborationBatchCommand {
  projectId: number
  userId: number
  bindingId: number
  bindingRevision: number
  hostAuthorizationId: number
  maxSlots: number
  maxDurationMs: number
  maxAttempts: number
  hostInstanceId: string | null
  runEpoch: number
  runLeaseUntil: string | null
  startedAt: string | null
  deadlineAt: string | null
  endedAt: string | null
  endReason: string | null
  createdAt: string
  attemptsUsed: number
}

export interface TaskExecutionCheckpoint {
  kind?: string
  operationId?: string
  occurredAt: string
  finding?: string
  artifactRefs?: string[]
  blockedReason?: string
}

export interface TaskDeliveryEvidence {
  conditions: Array<{ condition: string; result: string; evidenceRefs: string[] }>
  changedPaths: string[]
  artifactRefs: string[]
  projectCheckpoint?: string
}

export interface CollaborationAttempt {
  id: number
  taskId: number
  bindingId: number
  batchId: string
  hostInstanceId: string
  agentInstanceId: string
  inputRevision: number
  prerequisiteVersions: unknown
  status: string
  leaseUntil: string | null
  checkpoints: TaskExecutionCheckpoint[]
  resultEvidence: TaskDeliveryEvidence | null
  stopReason: string | null
  stopRequestedAt: string | null
  stopConfirmedAt: string | null
  stopConfirmation: unknown
  createdAt: string
  updatedAt: string
  finishedAt: string | null
}

export type CollaborationContextRef =
  | { kind: 'DOCUMENT'; documentId: number; documentVersion: number }
  | { kind: 'PROJECT_FILE'; projectPath: string; contentVersion: string }

export interface AttemptContext {
  attemptId: number
  refs: Array<{ ref: CollaborationContextRef; documentTitle: string | null; status: 'CURRENT' | 'PENDING_CONFIRMATION' | 'REVIEW_REQUIRED' }>
}

export type ArtifactObservation = { path: string } & (
  | { state: 'PRESENT'; contentVersion: string }
  | { state: 'MISSING' }
)

export interface AttemptPrerequisiteReviews {
  attemptId: number
  reviews: Array<{
    operationId: string
    agentInstanceId: string
    occurredAt: string
    outcome: string
    review: {
      sourceTaskId: number
      sourceAttemptId: number
      sourceInputRevision: number
      decision: 'ADOPT' | 'REPAIR_IN_SCOPE' | 'WAIT_FOR_CHANGE' | 'REQUEST_REWORK'
      finding: string
      evidence: string
      observations: ArtifactObservation[]
      goal: string | null
      impact: string | null
    }
  }>
}

export interface DependencyFact {
  operationId: string
  attemptId: number
  actorUserId: number
  batchId: string
  sourceInputRevision: number
  occurredAt: string
  change: {
    action: 'ADD' | 'REMOVE' | 'REQUEST_GOAL'
    prerequisiteTaskId: number | null
    missing: string
    reason: string
    evidence: string
    goal: string | null
    impact: string | null
  }
  accepted: boolean
  changed: boolean
  outcome: string | null
  resultInputRevision: number | null
}

export interface PrerequisiteResult {
  taskId: number
  attemptId: number
  inputRevision: number
  evidence: TaskDeliveryEvidence
}

export interface GoalAuthorizationRequest {
  batchId: string
  requestOperationId: string
  goal: string
  impact: string
  authorizedBatchId: string | null
}

export interface CollaborationTaskView {
  taskId: number
  taskKey: string | null
  title: string | null
  status: string | null
  inputRevision: number | null
  inputsReady: boolean
  inputsWaitingReason: string | null
  activeOrFrozen: boolean
  prerequisiteReviewStatuses: PrerequisiteReviewStatus[]
  prerequisiteResults: PrerequisiteResult[]
  attempts: CollaborationAttempt[]
  contextFacts: AttemptContext[]
  dependencyFacts: DependencyFact[]
  prerequisiteReviewFacts: AttemptPrerequisiteReviews[]
  goalAuthorizationRequests: GoalAuthorizationRequest[]
}

export interface CollaborationBatchDetail { serverTime: string; batch: CollaborationBatchSummary; tasks: CollaborationTaskView[] }
export interface CollaborationBatchPage { items: CollaborationBatchSummary[]; hasMore: boolean; nextCursor: string | null }

export const collaborationBatchApi = {
  async start(input: CollaborationBatchStart): Promise<CollaborationBatchCommand> {
    try { return unwrap(await api.post<ApiResponse<CollaborationBatchCommand>>('/collaboration/batches/start', input)) }
    catch (error) { throw toApiError(error) }
  },
  async startSingle(input: CollaborationBatchSingleStart): Promise<CollaborationBatchCommand> {
    try { return unwrap(await api.post<ApiResponse<CollaborationBatchCommand>>('/collaboration/batches/start-single', input)) }
    catch (error) { throw toApiError(error) }
  },
  async stop(projectId: number, batchId: string, operationId: string): Promise<CollaborationBatchCommand> {
    try { return unwrap(await api.post<ApiResponse<CollaborationBatchCommand>>('/collaboration/batches/stop', { projectId, batchId, operationId })) }
    catch (error) { throw toApiError(error) }
  },
  async authorizeGoal(projectId: number, batchId: string, requestOperationId: string): Promise<CollaborationBatchCommand> {
    try {
      return unwrap(await api.post<ApiResponse<CollaborationBatchCommand>>('/collaboration/batches/authorize-goal',
        { projectId, batchId, requestOperationId }))
    } catch (error) { throw toApiError(error) }
  },
  async list(projectId: number, beforeBatchId: string | null = null): Promise<CollaborationBatchPage> {
    try { return unwrap(await api.post<ApiResponse<CollaborationBatchPage>>('/collaboration/batches/list', { projectId, limit: 50, beforeBatchId })) }
    catch (error) { throw toApiError(error) }
  },
  async listAll(projectId: number): Promise<CollaborationBatchSummary[]> {
    const batches: CollaborationBatchSummary[] = []
    let cursor: string | null = null
    do {
      const page = await collaborationBatchApi.list(projectId, cursor)
      batches.push(...page.items)
      if (!page.hasMore) break
      if (!page.nextCursor || page.nextCursor === cursor) throw new Error('批次分页无效')
      cursor = page.nextCursor
    } while (true)
    return batches
  },
  async get(projectId: number, batchId: string): Promise<CollaborationBatchDetail> {
    try { return unwrap(await api.post<ApiResponse<CollaborationBatchDetail>>('/collaboration/batches/get', { projectId, batchId })) }
    catch (error) { throw toApiError(error) }
  },
}
