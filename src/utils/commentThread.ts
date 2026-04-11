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

export function buildCommentThreads(
  comments: TaskCommentDto[],
  visibleReplyLimit = DEFAULT_VISIBLE_REPLY_LIMIT
): CommentThread[] {
  if (comments.length === 0) return []

  const sorted = [...comments].sort(byCreatedAtThenId)
  const roots = sorted.filter(isRootComment)
  const rootById = new Map<number, TaskCommentDto>(roots.map((c) => [c.id, c]))
  const repliesByRootId = new Map<number, TaskCommentDto[]>()

  for (const comment of sorted) {
    if (isRootComment(comment)) continue
    if (comment.rootId == null) continue

    const root = rootById.get(comment.rootId)
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
