const ACTIVE_CODEX_RUN_STATUSES = new Set(['queued', 'claimed', 'running', 'needs_input'])

export function shouldRefreshCodexResultComment(previousStatus: string | null, nextStatus: string | null): boolean {
  return previousStatus != null && ACTIVE_CODEX_RUN_STATUSES.has(previousStatus) && nextStatus === 'completed'
}
