import { api, toApiError, unwrap } from './index'
import type { ApiResponse, PrerequisiteReviewStatus } from './types'
import type { AttemptContext, AttemptPrerequisiteReviews, CollaborationAttempt, DependencyFact,
  GoalAuthorizationRequest, PrerequisiteResult } from './collaborationBatch'
import type { Status } from '../../types/domain'

export interface TaskCollaborationState {
  serverTime: string
  inputRevision: number
  status: Status
  progressPercent: number
  completedAt: string | null
  updatedAt: string
  activeOrFrozen: boolean
  attempts: CollaborationAttempt[]
  attemptBindings: Array<{ id: number; deviceId: string }>
  bindings: Array<{
    bindingId: number
    deviceId: string
    inputsReady: boolean
    inputsWaitingReason: string | null
    prerequisiteReviewStatuses: PrerequisiteReviewStatus[]
    prerequisiteResults: PrerequisiteResult[]
  }>
  contextFacts: AttemptContext[]
  dependencyFacts: DependencyFact[]
  prerequisiteReviewFacts: AttemptPrerequisiteReviews[]
  goalAuthorizationRequests: GoalAuthorizationRequest[]
}

export const taskCollaborationApi = {
  async state(taskKey: string): Promise<TaskCollaborationState> {
    try {
      return unwrap(await api.post<ApiResponse<TaskCollaborationState>>('/collaboration/tasks/state', { taskKey }))
    } catch (error) { throw toApiError(error) }
  },
}
