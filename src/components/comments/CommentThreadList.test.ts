import { createApp, defineComponent, nextTick } from 'vue'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { i18n } from '../../i18n'
import type { CommentDto } from '../../types/comment'
import CommentThreadList from './CommentThreadList.vue'

vi.mock('../../utils/mermaidHydrate', () => ({
  runMermaidIn: vi.fn().mockResolvedValue(undefined)
}))

vi.mock('../BlockNoteEditorWrapper.vue', () => ({
  default: defineComponent({
    name: 'BlockNoteEditorStub',
    props: {
      modelValue: { type: String, default: '' },
      placeholder: { type: String, default: '' }
    },
    emits: ['update:modelValue'],
    setup(_, { expose }) {
      expose({ getMentionedUserIdsFromDoc: () => [] })
      return {}
    },
    template:
      '<textarea data-testid="comment-editor" :placeholder="placeholder" :value="modelValue" @input="$emit(\'update:modelValue\', $event.target.value)" />'
  })
}))

function comment(overrides: Partial<CommentDto>): CommentDto {
  return {
    id: 10,
    authorId: 2,
    authorName: 'Alice',
    body: 'Root comment',
    parentId: null,
    rootId: null,
    depth: 0,
    createdAt: '2026-04-10T00:00:00.000Z',
    deletable: true,
    ...overrides
  }
}

function mountComments(comments: CommentDto[]) {
  const host = document.createElement('div')
  document.body.appendChild(host)
  const submitComment = vi.fn().mockResolvedValue(undefined)
  const deleteComment = vi.fn().mockResolvedValue(undefined)
  const app = createApp(CommentThreadList, {
    comments,
    currentUserName: 'Tester',
    mentionMembers: [{ id: 2, label: 'Alice' }],
    submitComment,
    deleteComment
  })
  app.use(i18n)
  app.mount(host)
  return { app, host, submitComment, deleteComment }
}

describe('CommentThreadList', () => {
  beforeEach(() => {
    document.body.innerHTML = ''
    i18n.global.locale.value = 'en'
  })

  it('submits a root comment through the resource adapter', async () => {
    const view = mountComments([])
    try {
      const editor = view.host.querySelector('[data-testid="comment-editor"]') as HTMLTextAreaElement
      editor.value = 'A new comment'
      editor.dispatchEvent(new Event('input', { bubbles: true }))
      await nextTick()

      view.host.querySelector<HTMLButtonElement>('[aria-label="Send"]')?.click()
      await nextTick()

      expect(view.submitComment).toHaveBeenCalledWith({
        body: 'A new comment',
        mentionedUserIds: [],
        parentId: null
      })
    } finally {
      view.app.unmount()
    }
  })

  it('submits a reply with the parent author mentioned automatically', async () => {
    const view = mountComments([comment({})])
    try {
      view.host.querySelector<HTMLButtonElement>('[aria-label="Reply"]')?.click()
      await nextTick()

      const editors = view.host.querySelectorAll<HTMLTextAreaElement>('[data-testid="comment-editor"]')
      const replyEditor = editors[0]
      replyEditor.value = 'A nested reply'
      replyEditor.dispatchEvent(new Event('input', { bubbles: true }))
      await nextTick()

      const sendButtons = view.host.querySelectorAll<HTMLButtonElement>('[aria-label="Send"]')
      sendButtons[0]?.click()
      await nextTick()

      expect(view.submitComment).toHaveBeenCalledWith({
        body: 'A nested reply',
        mentionedUserIds: [2],
        parentId: 10
      })
    } finally {
      view.app.unmount()
    }
  })
})
