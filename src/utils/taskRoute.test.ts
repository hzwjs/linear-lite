import { describe, expect, it } from 'vitest'
import type { RouteLocationNormalizedLoaded } from 'vue-router'
import { buildTaskRoute, getRouteProjectId, getRouteTaskId } from './taskRoute'

function routeMock(params: Record<string, unknown>): RouteLocationNormalizedLoaded {
  return {
    params
  } as RouteLocationNormalizedLoaded
}

describe('taskRoute', () => {
  it('buildTaskRoute prefers canonical project path when projectId exists', () => {
    expect(buildTaskRoute('ENG-1', 3)).toBe('/projects/3/tasks/ENG-1')
    expect(buildTaskRoute('ENG-1', null)).toBe('/tasks/ENG-1')
  })

  it('parses task and project ids from route params', () => {
    expect(getRouteTaskId(routeMock({ taskId: 'ENG-9' }))).toBe('ENG-9')
    expect(getRouteTaskId(routeMock({ taskId: 123 }))).toBeNull()
    expect(getRouteProjectId(routeMock({ projectId: '5' }))).toBe(5)
    expect(getRouteProjectId(routeMock({ projectId: 'x' }))).toBeNull()
  })
})

