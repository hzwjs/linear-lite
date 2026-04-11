import { describe, expect, it } from 'vitest'
import type { TaskCommentDto } from '../services/api/taskComments'
import { buildCommentThreads } from './commentThread'

function comment(partial: Partial<TaskCommentDto> & Pick<TaskCommentDto, 'id'>): TaskCommentDto {
  return {
    id: partial.id,
    authorId: partial.authorId ?? 1,
    authorName: partial.authorName ?? 'user',
    body: partial.body ?? `comment-${partial.id}`,
    parentId: partial.parentId ?? null,
    rootId: partial.rootId ?? null,
    depth: partial.depth ?? 0,
    createdAt: partial.createdAt ?? '2026-04-11T10:00:00.000Z',
    deletable: partial.deletable ?? true
  }
}

describe('buildCommentThreads', () => {
  it('groups roots and replies into thread buckets', () => {
    const comments: TaskCommentDto[] = [
      comment({ id: 11, depth: 1, parentId: 10, rootId: 10, createdAt: '2026-04-11T10:02:00.000Z' }),
      comment({ id: 10, depth: 0, parentId: null, rootId: null, createdAt: '2026-04-11T10:01:00.000Z' }),
      comment({ id: 21, depth: 1, parentId: 20, rootId: 20, createdAt: '2026-04-11T10:04:00.000Z' }),
      comment({ id: 20, depth: 0, parentId: null, rootId: null, createdAt: '2026-04-11T10:03:00.000Z' }),
      comment({ id: 12, depth: 2, parentId: 11, rootId: 10, createdAt: '2026-04-11T10:05:00.000Z' })
    ]

    const threads = buildCommentThreads(comments)

    expect(threads).toHaveLength(2)
    expect(threads[0]?.root.id).toBe(10)
    expect(threads[0]?.replies.map((r) => r.id)).toEqual([11, 12])
    expect(threads[1]?.root.id).toBe(20)
    expect(threads[1]?.replies.map((r) => r.id)).toEqual([21])
  })

  it('collapses replies after the first 3 and keeps stable order', () => {
    const comments: TaskCommentDto[] = [
      comment({ id: 100, depth: 0, parentId: null, rootId: null, createdAt: '2026-04-11T10:00:00.000Z' }),
      comment({ id: 101, depth: 1, parentId: 100, rootId: 100, createdAt: '2026-04-11T10:01:00.000Z' }),
      comment({ id: 103, depth: 1, parentId: 100, rootId: 100, createdAt: '2026-04-11T10:03:00.000Z' }),
      comment({ id: 102, depth: 1, parentId: 100, rootId: 100, createdAt: '2026-04-11T10:02:00.000Z' }),
      comment({ id: 105, depth: 1, parentId: 100, rootId: 100, createdAt: '2026-04-11T10:05:00.000Z' }),
      comment({ id: 104, depth: 1, parentId: 100, rootId: 100, createdAt: '2026-04-11T10:04:00.000Z' })
    ]

    const [thread] = buildCommentThreads(comments)

    expect(thread?.replies.map((r) => r.id)).toEqual([101, 102, 103, 104, 105])
    expect(thread?.visibleReplies.map((r) => r.id)).toEqual([101, 102, 103])
    expect(thread?.hiddenReplyCount).toBe(2)
  })

  it('ignores orphan replies without a valid root association', () => {
    const comments: TaskCommentDto[] = [
      comment({ id: 1, depth: 0, parentId: null, rootId: null, createdAt: '2026-04-11T10:00:00.000Z' }),
      comment({ id: 2, depth: 1, parentId: 1, rootId: 1, createdAt: '2026-04-11T10:01:00.000Z' }),
      comment({ id: 3, depth: 1, parentId: 999, rootId: 999, createdAt: '2026-04-11T10:02:00.000Z' }),
      comment({ id: 4, depth: 1, parentId: 1, rootId: null, createdAt: '2026-04-11T10:03:00.000Z' })
    ]

    const [thread] = buildCommentThreads(comments)

    expect(thread?.root.id).toBe(1)
    expect(thread?.replies.map((r) => r.id)).toEqual([2, 4])
    expect(thread?.hiddenReplyCount).toBe(0)
  })

  it('falls back to parentId as root when rootId is missing in dirty reply data', () => {
    const comments: TaskCommentDto[] = [
      comment({ id: 1, depth: 0, parentId: null, rootId: null, createdAt: '2026-04-11T10:00:00.000Z' }),
      comment({ id: 2, depth: 1, parentId: 1, rootId: null, createdAt: '2026-04-11T10:01:00.000Z' })
    ]

    const [thread] = buildCommentThreads(comments)

    expect(thread?.root.id).toBe(1)
    expect(thread?.replies.map((r) => r.id)).toEqual([2])
    expect(thread?.hiddenReplyCount).toBe(0)
  })

  it('resolves root through parent chain for deep replies when rootId is missing', () => {
    const comments: TaskCommentDto[] = [
      comment({ id: 10, depth: 0, parentId: null, rootId: null, createdAt: '2026-04-11T10:00:00.000Z' }),
      comment({ id: 11, depth: 1, parentId: 10, rootId: 10, createdAt: '2026-04-11T10:01:00.000Z' }),
      comment({ id: 12, depth: 2, parentId: 11, rootId: null, createdAt: '2026-04-11T10:02:00.000Z' })
    ]

    const [thread] = buildCommentThreads(comments)

    expect(thread?.root.id).toBe(10)
    expect(thread?.replies.map((r) => r.id)).toEqual([11, 12])
  })
})
