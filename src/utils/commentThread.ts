import type { TaskCommentDto } from '../services/api/taskComments'

export interface CommentThread {
  root: TaskCommentDto
  replies: TaskCommentDto[]
  visibleReplies: TaskCommentDto[]
  hiddenReplyCount: number
}

const DEFAULT_VISIBLE_REPLY_LIMIT = 3

function toTime(value: string): number {
  const t = Date.parse(value)
  return Number.isFinite(t) ? t : 0
}

function byCreatedAtThenId(a: TaskCommentDto, b: TaskCommentDto): number {
  const timeDiff = toTime(a.createdAt) - toTime(b.createdAt)
  if (timeDiff !== 0) return timeDiff
  return a.id - b.id
}

function isRootComment(comment: TaskCommentDto): boolean {
  return comment.depth === 0
}

function resolveThreadRootId(
  comment: TaskCommentDto,
  rootById: Map<number, TaskCommentDto>,
  commentById: Map<number, TaskCommentDto>
): number | null {
  if (comment.rootId != null && rootById.has(comment.rootId)) return comment.rootId

  const visited = new Set<number>([comment.id])
  let parentId = comment.parentId
  while (parentId != null) {
    if (visited.has(parentId)) return null
    visited.add(parentId)

    const parent = commentById.get(parentId)
    if (!parent) return null
    if (isRootComment(parent)) return parent.id
    if (parent.rootId != null && rootById.has(parent.rootId)) return parent.rootId
    parentId = parent.parentId
  }

  return null
}

export function buildCommentThreads(
  comments: TaskCommentDto[],
  visibleReplyLimit = DEFAULT_VISIBLE_REPLY_LIMIT
): CommentThread[] {
  if (comments.length === 0) return []

  const sorted = [...comments].sort(byCreatedAtThenId)
  const commentById = new Map<number, TaskCommentDto>(sorted.map((c) => [c.id, c]))
  const roots = sorted.filter(isRootComment)
  const rootById = new Map<number, TaskCommentDto>(roots.map((c) => [c.id, c]))
  const repliesByRootId = new Map<number, TaskCommentDto[]>()

  for (const comment of sorted) {
    if (isRootComment(comment)) continue
    const threadRootId = resolveThreadRootId(comment, rootById, commentById)
    if (threadRootId == null) continue

    const root = rootById.get(threadRootId)
    if (!root) continue

    const list = repliesByRootId.get(root.id)
    if (list) list.push(comment)
    else repliesByRootId.set(root.id, [comment])
  }

  return roots.map((root) => {
    const replies = repliesByRootId.get(root.id) ?? []
    const visibleReplies = replies.slice(0, Math.max(0, visibleReplyLimit))
    return {
      root,
      replies,
      visibleReplies,
      hiddenReplyCount: Math.max(0, replies.length - visibleReplies.length)
    }
  })
}
