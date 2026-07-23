const ERROR_CODE_MAX_LENGTH = 64

export function runnerErrorCode(error: unknown): string {
  const message = error instanceof Error ? error.message : String(error)
  const match = /^([A-Z][A-Z_]*)(?::|$)/.exec(message)
  return (match?.[1] ?? 'RUNNER_EXECUTION_FAILED').slice(0, ERROR_CODE_MAX_LENGTH)
}
