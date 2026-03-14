export type Status = 'todo' | 'in_progress' | 'done'
export type Priority = 'low' | 'medium' | 'high' | 'urgent'

export interface Task {
  id: string // 与后端 taskKey 一致，如 ENG-1，用于展示与 PUT 路径
  /** 后端数据库主键，用于创建子任务时传 parentId、按父查子等 */
  numericId?: number
  title: string
  description?: string
  status: Status
  priority: Priority
  projectId?: number
  creatorId?: number
  assigneeId?: number | null
  dueDate?: number | null // 截止日期，毫秒时间戳
  completedAt?: number | null // 实际完成时间，由后端在终态时写入
  createdAt: number
  updatedAt: number
  /** Phase 7: 父任务 id，空为顶层 */
  parentId?: string | null
  /** 子任务总数（后端或前端计算） */
  subIssueCount?: number
  /** 已完成子任务数 */
  completedSubIssueCount?: number
  /** 当前用户是否已收藏 */
  favorited?: boolean
}

export interface TaskActivity {
  id: number
  actionType: 'created' | 'changed' | 'favorited' | 'unfavorited'
  fieldName?: string | null
  oldValue?: string | null
  newValue?: string | null
  actorName: string
  createdAt: number
}

export interface Project {
  id: number
  name: string
  identifier: string
  createdAt: string // ISO 字符串，如需数字可再转
}

export interface User {
  id: number
  username: string
  avatar_url?: string
}
