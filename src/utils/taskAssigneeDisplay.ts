import type { Task, User } from '../types/domain'

/** 已绑定系统用户且能在成员列表中解析到时返回该用户 */
export function resolveAssigneeUser(task: Task, users: User[] | undefined): User | undefined {
  if (task.assigneeId == null || !users?.length) return undefined
  return users.find((u) => u.id === task.assigneeId)
}

/**
 * 列表/卡片展示用负责人文案：优先系统成员 username，其次导入展示名，最后为未分配文案。
 */
export function assigneeDisplayLabel(task: Task, users: User[] | undefined, unassignedLabel: string): string {
  const u = resolveAssigneeUser(task, users)
  if (u) return u.username
  const ext = task.assigneeDisplayName?.trim()
  if (ext) return ext
  return unassignedLabel
}

/** 是否有可展示的负责人（系统用户或外部展示名） */
export function taskHasAssignableDisplay(task: Task, users: User[] | undefined): boolean {
  return resolveAssigneeUser(task, users) != null || Boolean(task.assigneeDisplayName?.trim())
}
