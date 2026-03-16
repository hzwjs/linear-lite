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
  parentId?: number | string | null
  subIssueCount?: number
  completedSubIssueCount?: number
  favorited?: boolean
}

export interface ApiTaskActivity {
  id: number
  actionType: 'created' | 'changed' | 'favorited' | 'unfavorited'
  fieldName?: string | null
  oldValue?: string | null
  newValue?: string | null
  actorName: string
  createdAt: string
}

export interface LoginRequest {
  identity: string
  password: string
}

export interface LoginResponse {
  token: string
  userId: number
  username: string
}

export interface SendRegisterCodeRequest {
  email: string
}

export interface RegisterRequest {
  email: string
  code: string
  username: string
  password: string
}

export interface CreateTaskRequest {
  projectId: number
  title: string
  description?: string
  status: string
  priority: string
  assigneeId?: number | null
  dueDate?: string | null // ISO 8601
  /** 父任务数据库 id（Long），非 task_key */
  parentId?: number | null
}

export interface UpdateTaskRequest {
  title?: string
  description?: string
  status?: string
  priority?: string
  assigneeId?: number | null
  /** true 时清空指派人 */
  clearAssignee?: boolean
  dueDate?: string | null // ISO 8601
  parentId?: string | null
}

export interface TaskImportRowRequest {
  lineNumber: number
  importId: string
  parentImportId?: string | null
  title: string
  description?: string
  status: string
  priority: string
  assigneeId?: number | null
  dueDate?: string | null
}

export interface TaskImportRequest {
  projectId: number
  rows: TaskImportRowRequest[]
}

export interface TaskImportResponse {
  createdCount: number
  parentCount: number
  subtaskCount: number
  taskKeys: string[]
}

export interface ImageUploadResponse {
  url: string
  key: string
}

/** 任务附件（列表与上传返回） */
export interface TaskAttachment {
  id: number
  taskId?: number
  objectKey?: string
  fileName: string
  fileSize: number
  contentType?: string | null
  url: string
  createdAt: string
}
