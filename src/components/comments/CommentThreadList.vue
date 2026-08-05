<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowUp, Reply, Trash2 } from 'lucide-vue-next'
import type { CommentDto, CommentSubmitPayload } from '../../types/comment'
import { toApiError } from '../../services/api/index'
import { getActivityAvatarLabel } from '../../utils/taskActivity'
import { getAvatarColorByUsername } from '../../utils/avatar'
import { renderBody } from '../../utils/blockNoteHtml'
import { renderMarkdown } from '../../utils/markdown'
import { runMermaidIn } from '../../utils/mermaidHydrate'
import { buildCommentThreads } from '../../utils/commentThread'
import BlockNoteEditorWrapper from '../BlockNoteEditorWrapper.vue'

export type CommentSubmitHandler = (payload: CommentSubmitPayload) => Promise<void>
export type CommentDeleteHandler = (comment: CommentDto) => Promise<void>

const props = withDefaults(
  defineProps<{
    comments: CommentDto[]
    loading?: boolean
    currentUserName?: string
    mentionMembers?: Array<{ id: number; label: string }>
    submitComment: CommentSubmitHandler
    deleteComment: CommentDeleteHandler
  }>(),
  {
    loading: false,
    currentUserName: '',
    mentionMembers: () => []
  }
)

const { t } = useI18n()
const commentsMermaidHostRef = ref<HTMLElement | null>(null)
const commentBody = ref('')
const commentSubmitting = ref(false)
const inlineReplyRootId = ref<number | null>(null)
const inlineReplyParentId = ref<number | null>(null)
const replyBodyByRootId = ref<Record<number, string>>({})
const replySubmittingRootIds = ref<Set<number>>(new Set())
const deleteSubmittingId = ref<number | null>(null)
const commentEditorRef = ref<InstanceType<typeof BlockNoteEditorWrapper> | null>(null)
const inlineReplyEditorRef = ref<InstanceType<typeof BlockNoteEditorWrapper> | null>(null)
const commentThreads = computed(() => buildCommentThreads(props.comments, Number.MAX_SAFE_INTEGER))

async function hydrateCommentsMermaid() {
  await nextTick()
  await runMermaidIn(commentsMermaidHostRef.value)
}

function commentTimeFromIso(iso: string): string {
  const timestamp = Date.parse(iso)
  if (Number.isNaN(timestamp)) return ''
  const diff = Date.now() - timestamp
  if (diff < 60 * 1000) return t('taskEditor.justNow')
  if (diff < 60 * 60 * 1000) return t('taskEditor.minutesAgo', { count: Math.floor(diff / 60000) })
  if (diff < 24 * 60 * 60 * 1000) return t('taskEditor.hoursAgo', { count: Math.floor(diff / 3600000) })
  if (diff < 30 * 24 * 60 * 60 * 1000) return t('taskEditor.daysAgo', { count: Math.floor(diff / 86400000) })
  return t('taskEditor.monthsAgo', { count: Math.floor(diff / (30 * 86400000)) })
}

function openInlineReply(rootId: number, parentId = rootId) {
  inlineReplyRootId.value = rootId
  inlineReplyParentId.value = parentId
  if (replyBodyByRootId.value[rootId] != null) return
  replyBodyByRootId.value = { ...replyBodyByRootId.value, [rootId]: '' }
}

function closeInlineReply(rootId: number) {
  if (inlineReplyRootId.value !== rootId) return
  inlineReplyRootId.value = null
  inlineReplyParentId.value = null
}

function updateInlineReplyBody(rootId: number, value: string) {
  replyBodyByRootId.value = { ...replyBodyByRootId.value, [rootId]: value }
}

function parentAuthorId(parentId: number): number | null {
  return props.comments.find((comment) => comment.id === parentId)?.authorId ?? null
}

function replyContextAuthorName(reply: CommentDto, root: CommentDto): string | null {
  if (reply.parentId == null) return null
  const parent = props.comments.find((comment) => comment.id === reply.parentId)
  return parent != null && parent.id !== root.id ? parent.authorName : null
}

function onCommentEditorKeydown(event: KeyboardEvent) {
  if (event.isComposing) return
  if (!(event.metaKey || event.ctrlKey) || event.key !== 'Enter') return
  event.preventDefault()
  void submitComment()
}

function onInlineReplyEditorKeydown(event: KeyboardEvent, rootId: number) {
  if (event.isComposing) return
  if (!(event.metaKey || event.ctrlKey) || event.key !== 'Enter') return
  event.preventDefault()
  void submitReply(rootId)
}

async function submitComment() {
  if (commentSubmitting.value) return
  const body = commentBody.value.trim()
  if (!body) return
  commentSubmitting.value = true
  try {
    await props.submitComment({
      body,
      mentionedUserIds: commentEditorRef.value?.getMentionedUserIdsFromDoc?.() ?? [],
      parentId: null
    })
    commentBody.value = ''
  } catch (error) {
    alert(toApiError(error).message || t('taskEditor.commentSendFailed'))
  } finally {
    commentSubmitting.value = false
  }
}

async function submitReply(rootId: number) {
  if (replySubmittingRootIds.value.has(rootId)) return
  const body = (replyBodyByRootId.value[rootId] ?? '').trim()
  if (!body) return
  const parentId = inlineReplyParentId.value ?? rootId
  const targetAuthorId = parentAuthorId(parentId)
  const editorMentionedIds = inlineReplyEditorRef.value?.getMentionedUserIdsFromDoc?.() ?? []
  const mentionedUserIds = [...new Set(targetAuthorId == null ? editorMentionedIds : [...editorMentionedIds, targetAuthorId])]
  replySubmittingRootIds.value = new Set(replySubmittingRootIds.value).add(rootId)
  try {
    await props.submitComment({ body, mentionedUserIds, parentId })
    replyBodyByRootId.value = { ...replyBodyByRootId.value, [rootId]: '' }
    inlineReplyRootId.value = null
    inlineReplyParentId.value = null
  } catch (error) {
    alert(toApiError(error).message || t('taskEditor.commentSendFailed'))
  } finally {
    const next = new Set(replySubmittingRootIds.value)
    next.delete(rootId)
    replySubmittingRootIds.value = next
  }
}

async function deleteCommentRow(comment: CommentDto) {
  if (!comment.deletable || deleteSubmittingId.value != null) return
  if (!window.confirm(t('taskEditor.deleteCommentConfirm'))) return
  deleteSubmittingId.value = comment.id
  try {
    await props.deleteComment(comment)
  } catch (error) {
    alert(toApiError(error).message || t('taskEditor.commentSendFailed'))
  } finally {
    deleteSubmittingId.value = null
  }
}

watch(
  [() => props.comments, () => props.loading],
  () => {
    if (props.loading) return
    void hydrateCommentsMermaid()
  },
  { deep: true, immediate: true }
)
</script>

<template>
  <div v-if="loading" class="comment-empty">{{ t('taskEditor.commentsLoading') }}</div>
  <div v-else-if="commentThreads.length" ref="commentsMermaidHostRef" class="task-comments-list">
    <div v-for="thread in commentThreads" :key="thread.root.id" class="task-comment-thread">
      <div class="task-comment-row task-comment-row--root">
        <span class="task-comment-avatar" :style="getAvatarColorByUsername(thread.root.authorName)" aria-hidden="true">
          {{ getActivityAvatarLabel(thread.root.authorName) }}
        </span>
        <div class="task-comment-content">
          <div class="task-comment-meta-line">
            <strong>{{ thread.root.authorName }}</strong>
            <span>· {{ commentTimeFromIso(thread.root.createdAt) }}</span>
            <button
              type="button"
              class="task-comment-reply-btn"
              :aria-label="t('taskEditor.reply')"
              :title="t('taskEditor.reply')"
              @click="openInlineReply(thread.root.id)"
            >
              <Reply :size="14" aria-hidden="true" />
            </button>
            <button
              v-if="thread.root.deletable"
              type="button"
              class="task-comment-delete"
              :aria-label="t('taskEditor.deleteCommentAria')"
              :title="t('taskEditor.deleteComment')"
              @click="deleteCommentRow(thread.root)"
            >
              <Trash2 :size="14" aria-hidden="true" />
            </button>
          </div>
          <div class="task-comment-body markdown-body" v-html="renderBody(thread.root.body, renderMarkdown)" />
        </div>
      </div>
      <div v-if="thread.replies.length" class="task-comment-replies">
        <div v-for="reply in thread.replies" :key="reply.id" class="task-comment-row task-comment-row--reply">
          <span class="task-comment-avatar" :style="getAvatarColorByUsername(reply.authorName)" aria-hidden="true">
            {{ getActivityAvatarLabel(reply.authorName) }}
          </span>
          <div class="task-comment-content">
            <div class="task-comment-meta-line">
              <strong>{{ reply.authorName }}</strong>
              <span>· {{ commentTimeFromIso(reply.createdAt) }}</span>
              <button
                type="button"
                class="task-comment-reply-btn"
                :aria-label="t('taskEditor.reply')"
                :title="t('taskEditor.reply')"
                @click="openInlineReply(thread.root.id, reply.id)"
              >
                <Reply :size="14" aria-hidden="true" />
              </button>
              <button
                v-if="reply.deletable"
                type="button"
                class="task-comment-delete"
                :aria-label="t('taskEditor.deleteCommentAria')"
                :title="t('taskEditor.deleteComment')"
                @click="deleteCommentRow(reply)"
              >
                <Trash2 :size="14" aria-hidden="true" />
              </button>
            </div>
            <div class="task-comment-body task-comment-body--reply markdown-body">
              <span v-if="replyContextAuthorName(reply, thread.root)" class="task-comment-reply-context">
                {{ t('taskEditor.reply') }} {{ replyContextAuthorName(reply, thread.root) }}
              </span>
              <span class="task-comment-reply-content" v-html="renderBody(reply.body, renderMarkdown)" />
            </div>
          </div>
        </div>
      </div>
      <div
        v-if="inlineReplyRootId === thread.root.id"
        class="task-comment-reply-compose"
        @keydown.capture="(event) => onInlineReplyEditorKeydown(event, thread.root.id)"
      >
        <span class="comment-compose-avatar" :style="getAvatarColorByUsername(currentUserName)" aria-hidden="true">
          {{ getActivityAvatarLabel(currentUserName) }}
        </span>
        <div class="comment-compose-input comment-compose-input--with-send">
          <BlockNoteEditorWrapper
            ref="inlineReplyEditorRef"
            :model-value="replyBodyByRootId[thread.root.id] ?? ''"
            :mention-members="mentionMembers"
            :mention-menu-search-placeholder="t('taskList.assigneeSearchPlaceholder')"
            :mention-menu-no-matches-text="t('taskEditor.mentionNoMatches')"
            :mention-menu-loading-text="t('common.loading')"
            :placeholder="t('taskEditor.replyPlaceholder')"
            :min-height="44"
            @update:model-value="(value) => updateInlineReplyBody(thread.root.id, value)"
          />
          <button
            type="button"
            class="comment-send-btn comment-send-btn--corner"
            :disabled="replySubmittingRootIds.has(thread.root.id) || !(replyBodyByRootId[thread.root.id] ?? '').trim()"
            :aria-label="t('taskEditor.sendAria')"
            @click="submitReply(thread.root.id)"
          >
            <ArrowUp :size="14" aria-hidden="true" />
          </button>
        </div>
        <button type="button" class="task-comment-reply-cancel" @click="closeInlineReply(thread.root.id)">
          {{ t('common.cancel') }}
        </button>
      </div>
    </div>
  </div>
  <div v-else class="comment-empty">{{ t('taskEditor.noComments') }}</div>

  <div class="comment-compose" @keydown.capture="onCommentEditorKeydown">
    <div class="comment-compose-input comment-compose-input--with-send">
      <BlockNoteEditorWrapper
        ref="commentEditorRef"
        v-model="commentBody"
        :mention-members="mentionMembers"
        :mention-menu-search-placeholder="t('taskList.assigneeSearchPlaceholder')"
        :mention-menu-no-matches-text="t('taskEditor.mentionNoMatches')"
        :mention-menu-loading-text="t('common.loading')"
        :placeholder="t('taskEditor.leaveComment')"
        :min-height="44"
      />
      <button
        type="button"
        class="comment-send-btn comment-send-btn--corner"
        :disabled="commentSubmitting || !commentBody.trim()"
        :aria-label="t('taskEditor.sendAria')"
        @click="submitComment"
      >
        <ArrowUp :size="14" aria-hidden="true" />
      </button>
    </div>
  </div>
</template>

<style scoped>
.comment-empty {
  margin: 4px 0 12px;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
}

.task-comments-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 12px;
}

.task-comments-list :deep(.mermaid) {
  max-width: 100%;
  margin: 8px 0;
  overflow-x: auto;
}

.task-comment-thread {
  display: flex;
  flex-direction: column;
  overflow: hidden;
  padding: 0;
  border: 1px solid var(--color-border-subtle);
  border-radius: 8px;
  background: var(--color-bg-base);
}

.task-comment-row {
  display: flex;
  gap: 8px;
  padding: 12px 14px;
}

.task-comment-row--reply {
  border-top: 1px solid var(--color-border-subtle);
}

.task-comment-avatar,
.comment-compose-avatar {
  display: inline-flex;
  flex: 0 0 18px;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  margin-top: 1px;
  box-sizing: border-box;
  padding: 1px;
  border-radius: var(--radius-full);
  font-size: 8px;
  font-weight: var(--font-weight-semibold);
  line-height: 1;
  white-space: nowrap;
}

.task-comment-content {
  flex: 1;
  min-width: 0;
}

.task-comment-meta-line {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 18px;
}

.task-comment-meta-line strong {
  color: var(--color-text-primary);
  font-size: 13px;
  font-weight: var(--font-weight-semibold);
  line-height: 18px;
}

.task-comment-reply-btn,
.task-comment-delete {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}

.task-comment-delete {
  margin-left: -4px;
}

.task-comment-row:hover .task-comment-reply-btn,
.task-comment-row:hover .task-comment-delete,
.task-comment-reply-btn:focus-visible,
.task-comment-delete:focus-visible {
  opacity: 1;
}

.task-comment-reply-btn:hover,
.task-comment-reply-btn:focus-visible {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}

.task-comment-delete:hover,
.task-comment-delete:focus-visible {
  background: var(--color-bg-hover);
  color: var(--color-danger, #e5484d);
}

.task-comment-body {
  min-height: 18px;
  margin-top: 3px;
  color: var(--color-text-primary);
  font-size: 13px;
  line-height: 1.5;
}

.task-comment-body :deep(img) {
  display: block;
  max-width: 100%;
  height: auto;
}

.task-comment-body--reply {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
}

.task-comment-body :deep(p) {
  margin: 0;
}

.task-comment-body :deep(p:last-child) {
  margin-bottom: 0;
}

.task-comment-reply-context {
  display: inline-flex;
  align-items: center;
  margin-right: 5px;
  color: var(--color-text-secondary);
  font-size: 12px;
  font-weight: var(--font-weight-medium);
  line-height: 18px;
}

.task-comment-reply-content {
  min-width: 0;
  max-width: 100%;
}

.task-comment-reply-content :deep(p) {
  display: inline;
  margin: 0;
}

.comment-compose,
.task-comment-reply-compose {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: var(--color-bg-base);
}

.comment-compose {
  padding: 10px 12px;
  border: 1px solid var(--color-border-subtle);
  border-radius: 8px;
}

.task-comment-reply-compose {
  padding: 10px 14px;
  border-top: 1px solid var(--color-border-subtle);
}

.comment-compose-input {
  display: flex;
  flex: 1;
  min-width: 0;
  flex-direction: column;
}

.comment-compose-input :deep(.blocknote-editor-wrap) {
  min-width: 0;
  border: 0;
  border-radius: 0;
  background: transparent;
}

.comment-compose-input--with-send {
  position: relative;
}

.comment-compose-input--with-send :deep(.blocknote-editor-wrap .bn-editor) {
  min-height: 44px;
  padding: 9px 38px 9px 0;
  font-family: var(--font-family) !important;
  font-size: var(--font-size-body) !important;
  line-height: 1.5 !important;
}

.comment-compose-input--with-send :deep(.blocknote-editor-wrap .bn-inline-content) {
  font-family: var(--font-family) !important;
  font-size: var(--font-size-body) !important;
  line-height: 1.5 !important;
}

.task-comment-reply-cancel {
  align-self: end;
  height: 32px;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 12px;
  cursor: pointer;
}

.task-comment-reply-cancel:hover {
  color: var(--color-text-primary);
}

.comment-send-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  min-width: 28px;
  padding: 0;
  border: none;
  border-radius: var(--radius-full);
  background: color-mix(in srgb, var(--color-bg-subtle) 62%, #9aa3ad 38%);
  color: color-mix(in srgb, var(--color-text-secondary) 72%, #fff 28%);
  font-size: 12px;
  line-height: 1;
  font-weight: 500;
  cursor: pointer;
  flex-shrink: 0;
}

.comment-send-btn--corner {
  position: absolute;
  right: 0;
  bottom: 8px;
}

.comment-send-btn:disabled {
  opacity: 0.9;
  cursor: not-allowed;
}

.comment-send-btn:not(:disabled) {
  background: var(--color-accent, #5e6ad2);
  color: #fff;
}

@media (hover: none) {
  .task-comment-reply-btn,
  .task-comment-delete {
    opacity: 1;
  }
}
</style>
