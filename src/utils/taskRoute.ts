import type { RouteLocationNormalizedLoaded } from 'vue-router'

export function buildTaskRoute(taskId: string, projectId?: number | null): string {
  if (projectId == null) return `/tasks/${taskId}`
  return `/projects/${projectId}/tasks/${taskId}`
}

export function getRouteTaskId(route: RouteLocationNormalizedLoaded): string | null {
  const raw = route.params.taskId
  return typeof raw === 'string' && raw.length > 0 ? raw : null
}

export function getRouteProjectId(route: RouteLocationNormalizedLoaded): number | null {
  const raw = route.params.projectId
  if (typeof raw !== 'string' || raw.length === 0) return null
  const parsed = Number(raw)
  return Number.isInteger(parsed) ? parsed : null
}

