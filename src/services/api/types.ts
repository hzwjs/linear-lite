/** 后端统一响应：code / message / data */
export interface ApiResponse<T> {
  code: number
  message?: string
  data: T
}

/** 后端 Task 实体：taskKey、LocalDateTime 为 ISO 字符串 */
export interface ApiTask {
  id: number
  taskKey: string
  title: string
  description?: string
  status: string
  priority: string
  projectId: number
  creatorId?: number
  assigneeId?: number | null
  dueDate?: string | null
  completedAt?: string | null
  createdAt: string
  updatedAt: string
}

export interface LoginRequest {
  username: string
  password: string
}

export interface LoginResponse {
  token: string
  userId: number
  username: string
}

export interface CreateTaskRequest {
  projectId: number
  title: string
  description?: string
  status: string
  priority: string
  assigneeId?: number | null
  dueDate?: string | null // ISO 8601
}

export interface UpdateTaskRequest {
  title?: string
  description?: string
  status?: string
  priority?: string
  assigneeId?: number | null
  dueDate?: string | null // ISO 8601
}
