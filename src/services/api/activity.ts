import { api, unwrap } from './index'
import type { ApiResponse, ApiTaskActivity } from './types'
import type { TaskActivity } from '../../types/domain'

function toTaskActivity(activity: ApiTaskActivity): TaskActivity {
  return {
    id: activity.id,
    actionType: activity.actionType,
    fieldName: activity.fieldName ?? null,
    oldValue: activity.oldValue ?? null,
    newValue: activity.newValue ?? null,
    actorName: activity.actorName,
    createdAt: new Date(activity.createdAt).getTime()
  }
}

const DEFAULT_ACTIVITY_LIMIT = 50

export const activityApi = {
  list(taskKey: string, limit = DEFAULT_ACTIVITY_LIMIT): Promise<TaskActivity[]> {
    return api
      .get<ApiResponse<ApiTaskActivity[]>>(`/tasks/${taskKey}/activities`, { params: { limit } })
      .then((res) => unwrap(res).map(toTaskActivity))
  }
}
