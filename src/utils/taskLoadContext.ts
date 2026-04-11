export type TaskLoadSnapshot = { taskId: string; numericId: number | null | undefined }

export function captureTaskLoadContext(
  task: { id?: string; numericId?: number | null } | null | undefined
): TaskLoadSnapshot | null {
  if (task?.id == null) return null
  return { taskId: task.id, numericId: task.numericId }
}

export function isTaskLoadStale(
  snap: TaskLoadSnapshot | null,
  current: { id?: string; numericId?: number | null } | null | undefined
): boolean {
  if (snap == null) return true
  if (current?.id !== snap.taskId) return true
  if (
    snap.numericId != null &&
    current.numericId != null &&
    current.numericId !== snap.numericId
  ) {
    return true
  }
  return false
}
