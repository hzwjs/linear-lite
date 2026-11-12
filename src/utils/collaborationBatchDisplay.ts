export type BatchTone = 'default' | 'processing' | 'success' | 'warning' | 'error'

const tones: Record<string, BatchTone> = {
  WAITING_ACCEPT: 'default', READY: 'processing', RUNNING: 'processing',
  DEPENDENCY_WAIT: 'warning', PREREQUISITE_REVIEW_WAIT: 'warning', GOAL_AUTHORIZATION_WAIT: 'warning',
  STOPPING_WAIT: 'warning', TASK_WAIT: 'warning', BUDGET_EXHAUSTED: 'warning', BLOCKED: 'warning',
  STOPPING: 'warning', RECOVERY: 'error', ENDED: 'default',
}

export function collaborationBatchTone(state: string, endReason?: string | null): BatchTone {
  if (state === 'ENDED' && endReason === 'completed') return 'success'
  if (state === 'ENDED' && (endReason === 'budget_exhausted' || endReason === 'needs_decision')) return 'warning'
  if (state === 'ENDED' && endReason === 'failed') return 'error'
  return tones[state] ?? 'default'
}

const attemptTones: Record<string, BatchTone> = {
  running: 'processing', stop_requested: 'warning', stopped: 'default', succeeded: 'success', failed: 'error', recovery: 'error',
}

export function collaborationAttemptTone(state: string): BatchTone {
  return attemptTones[state] ?? 'default'
}
