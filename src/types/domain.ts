export type Status = 'todo' | 'in_progress' | 'done'
export type Priority = 'low' | 'medium' | 'high' | 'urgent'

export interface Task {
  id: string // 与后端 taskKey 一致，如 ENG-1，用于展示与 PUT 路径
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
