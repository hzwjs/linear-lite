<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, computed, nextTick, type Component } from 'vue'
import { useI18n } from 'vue-i18n'

const AUTO_SAVE_DEBOUNCE_MS = 600
const SAVED_INDICATOR_MS = 2000

import type { Task, Status, Priority, TaskActivity, User } from '../types/domain'
import { useAuthStore } from '../store/authStore'
import { useNotificationStore } from '../store/notificationStore'
import { useTaskStore } from '../store/taskStore'
import { useFavoriteStore } from '../store/favoriteStore'
import { useProjectStore } from '../store/projectStore'
import { useIssuePanelStore } from '../store/issuePanelStore'
import { useRouter } from 'vue-router'
import { toApiError } from '../services/api/index'
import { projectApi } from '../services/api/project'
import { documentApi } from '../services/api/documents'
import type { ProjectDocumentTreeNode } from '../types/document'
import { activityApi } from '../services/api/activity'
import { taskCommentsApi, type TaskCommentDto } from '../services/api/taskComments'
import { attachmentsApi } from '../services/api/attachments'
import { toLabelWriteItems } from '../utils/taskLabelWrite'
import type { TaskAttachment } from '../services/api/types'
import {
  type TaskActivityDisplayItem,
  formatTaskActivityDisplayItem,
  getTaskActivityDisplaySource,
  groupTaskActivitiesForDisplay
} from '../utils/taskActivityGroup'
import {
  getActivityAvatarLabel,
  getTaskActivityLabelChange,
  isTaskActivityTimelineEvent
} from '../utils/taskActivity'
import { getAvatarColorByUsername } from '../utils/avatar'
import { getTaskLabelTone } from '../utils/taskLabelTone'
import { renderMarkdown } from '../utils/markdown'
import { renderBody } from '../utils/blockNoteHtml'
import { runMermaidIn } from '../utils/mermaidHydrate'
import { buildCommentThreads } from '../utils/commentThread'
import { randomClientId } from '../utils/clientId'
import { formatDateInputValue, parseDateInputValue, todayDateInputValue } from '../utils/taskDate'
import { saveTaskEditDraft, clearTaskEditDraft, readTaskEditDraft } from '../utils/taskEditDraft'
import { blockNoteDocHasPersistableContent, parseBlockNoteStoredBlocks } from '../utils/blockNoteDescription'
import { getPriorityLabel, getStatusLabel } from '../utils/enumLabels'
import { getTaskDueState } from '../utils/taskDueState'
import { captureTaskLoadContext, isTaskLoadStale } from '../utils/taskLoadContext'
import { readTaskDetailSnapshot } from '../utils/taskDetailPreload'
import BlockNoteEditorWrapper from './BlockNoteEditorWrapper.vue'
import CustomSelect from './ui/CustomSelect.vue'
import CustomDatePicker from './ui/CustomDatePicker.vue'
import AssigneeSelect from './ui/AssigneeSelect.vue'
import TaskLabelCombobox from './TaskLabelCombobox.vue'
import type { CustomSelectOption } from './ui/CustomSelect.vue'
import {
  PriorityUrgentIcon,
  PriorityHighIcon,
  PriorityMediumIcon,
  PriorityLowIcon
} from './icons/PriorityIcons'
import {
  Circle,
  CircleDashed,
  Loader2,
  CheckCircle,
  CircleX,
  Copy,
  Eye,
  Star,
  Maximize2,
  Minimize2,
  Paperclip,
  Folder,
  Tag,
  Reply,
  Trash2,
  CalendarDays,
  UserRound,
  UsersRound,
  ArrowUp,
  BarChart3,
  Pencil,
  ChevronDown,
  ChevronRight,
  Plus
} from 'lucide-vue-next'
import TaskRowStatusPicker from './TaskRowStatusPicker.vue'

const props = withDefaults(
  defineProps<{
    mode: 'create' | 'edit'
    task?: Task | null
    /** P4-6.5: 列头 + 新建时的默认状态 */
    defaultStatus?: Status
    previousTaskId?: string | null
    nextTaskId?: string | null
    position?: number | null
    total?: number
    /** 内联时无遮罩、占满主区；overlay 为浮层（已废弃，保留兼容） */
    variant?: 'inline' | 'overlay'
  }>(),
  { variant: 'inline' }
)

const emit = defineEmits<{
  close: []
  navigate: [taskId: string]
}>()

const authStore = useAuthStore()
const notificationStore = useNotificationStore()
const store = useTaskStore()
const favoriteStore = useFavoriteStore()
const projectStore = useProjectStore()
const issuePanelStore = useIssuePanelStore()
const router = useRouter()
const { t } = useI18n()

const formTitle = ref('')
const formDescription = ref('')
const descriptionUploadState = ref({ hasPending: false, hasFailed: false })
const isDescriptionEditing = ref(false)
const descriptionEditorRef = ref<InstanceType<typeof BlockNoteEditorWrapper> | null>(null)

function focusDescription() {
  nextTick(() => descriptionEditorRef.value?.focus())
}

function onDescriptionUploadStateChange(state: { hasPending: boolean; hasFailed: boolean }) {
  descriptionUploadState.value = state
}
const formStatus = ref<Status>('todo')
const formPriority = ref<Priority>('medium')
const formAssigneeId = ref<string | number>('')

const importedAssigneeOnlyLabel = computed(() => {
  if (props.mode !== 'edit' || !props.task) return ''
  if (props.task.assigneeId != null) return ''
  return props.task.assigneeDisplayName?.trim() ?? ''
})
const formPlannedStartDate = ref('') // YYYY-MM-DD
const formDueDate = ref('') // YYYY-MM-DD for input[type=date]
/** 0–100，与后端 progressPercent 一致 */
const formProgressPercent = ref(0)
/** 侧栏标签编辑：有 id 为已持久化标签，无 id 为待创建 */
const formLabels = ref<{ id?: number; name: string }[]>([])
const labelPickerOpen = ref(false)
const labelInput = ref('')
const taskLabelComboboxRef = ref<{
  removeFromSuggestions: (labelId: number) => void
} | null>(null)
const descriptionSectionRef = ref<HTMLElement | null>(null)
const isDescriptionFullscreen = ref(false)
const userList = ref<User[]>([])
const saveStatus = ref<'idle' | 'saving' | 'saved' | 'failed'>('idle')
const progressStatusHint = ref('')
let progressStatusHintTimer: ReturnType<typeof setTimeout> | null = null
let codexTaskRefreshTimer: ReturnType<typeof setInterval> | null = null
const activities = ref<TaskActivity[]>([])
const activitiesLoading = ref(false)
const activityDisplayItems = computed(() =>
  groupTaskActivitiesForDisplay(activities.value.filter(isTaskActivityTimelineEvent))
)

function activityDisplayRowKey(item: TaskActivityDisplayItem): string {
  if (item.kind === 'single') return `activity-${item.activity.id}`
  const first = item.activities[0]
  return `activity-group-${first?.id ?? 'unknown'}-${item.activities.length}`
}

function activityDisplayRowTime(item: TaskActivityDisplayItem): number {
  if (item.kind === 'single') return item.activity.createdAt
  return item.activities[0]?.createdAt ?? 0
}

function activityDisplaySource(item: TaskActivityDisplayItem): TaskActivity {
  return getTaskActivityDisplaySource(item)
}

function activityDisplayActor(item: TaskActivityDisplayItem): string {
  return item.actorName
}

function activityDisplayIcon(item: TaskActivityDisplayItem): Component | null {
  const activity = activityDisplaySource(item)
  if (activity.actionType !== 'changed') return null

  switch (activity.fieldName) {
    case 'status':
      return statusOptions.value.find((option) => option.value === activity.newValue)?.icon ?? Circle
    case 'priority':
      switch (activity.newValue) {
        case 'urgent': return PriorityUrgentIcon
        case 'high': return PriorityHighIcon
        case 'medium': return PriorityMediumIcon
        case 'low': return PriorityLowIcon
        default: return BarChart3
      }
    case 'labels': return Tag
    case 'assigneeId': return UserRound
    case 'dueDate':
    case 'plannedStartDate': return CalendarDays
    case 'progressPercent': return BarChart3
    default: return Pencil
  }
}

function activityDisplayLabels(item: TaskActivityDisplayItem): string[] {
  const { added, removed } = getTaskActivityLabelChange(activityDisplaySource(item))
  return added.length ? added : removed
}

const comments = ref<TaskCommentDto[]>([])
const commentsLoading = ref(false)
const commentsMermaidHostRef = ref<HTMLElement | null>(null)

async function hydrateCommentsMermaid() {
  await nextTick()
  await runMermaidIn(commentsMermaidHostRef.value)
}
const commentBody = ref('')
const commentSubmitting = ref(false)
const inlineReplyRootId = ref<number | null>(null)
const inlineReplyParentId = ref<number | null>(null)
const replyBodyByRootId = ref<Record<number, string>>({})
const replySubmittingRootIds = ref<Set<number>>(new Set())
const attachmentInputRef = ref<HTMLInputElement | null>(null)
const attachments = ref<TaskAttachment[]>([])
const attachmentsLoading = ref(false)
const attachmentUploadError = ref('')
const attachmentsCollapsed = ref(false)
/** 当前队列中正在上传的附件（顺序上传，通常仅一条） */
const attachmentPendingUploads = ref<
  { localId: string; fileName: string; fileSize: number; progress: number }[]
>([])
/** 一次选择多文件时，在「单文件完成」与「下一文件开始」之间仍为 true，避免回形针短暂可点 */
const attachmentUploadBatchActive = ref(false)
const dueStateNow = ref(Date.now())
let dueStateNowTimer: ReturnType<typeof setInterval> | null = null
const MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024 // 10MB
/** 刚由本端保存的任务 id，避免 save 后 loadForm 用接口返回值覆盖编辑器内容 */
const justSavedTaskId = ref<string | null>(null)
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null
let autoSaveQueuedWhileSaving = false
let lastAcknowledgedSave: { taskId: string; signature: string } | null = null

const statusOptions = computed<CustomSelectOption[]>(() => [
  { value: 'backlog', label: getStatusLabel('backlog'), icon: CircleDashed, shortcut: '1' },
  { value: 'todo', label: getStatusLabel('todo'), icon: Circle, shortcut: '2' },
  { value: 'in_progress', label: getStatusLabel('in_progress'), icon: Loader2, shortcut: '3' },
  { value: 'in_review', label: getStatusLabel('in_review'), icon: Eye, shortcut: '4' },
  { value: 'done', label: getStatusLabel('done'), icon: CheckCircle, shortcut: '5' },
  { value: 'canceled', label: getStatusLabel('canceled'), icon: CircleX, shortcut: '6' },
  { value: 'duplicate', label: getStatusLabel('duplicate'), icon: Copy, shortcut: '7' }
])

function safeArray<T>(value: T[] | null | undefined): T[] {
  return Array.isArray(value) ? value : []
}
const priorityOptions = computed<CustomSelectOption[]>(() => [
  { value: 'low', label: getPriorityLabel('low'), icon: PriorityLowIcon },
  { value: 'medium', label: getPriorityLabel('medium'), icon: PriorityMediumIcon },
  { value: 'high', label: getPriorityLabel('high'), icon: PriorityHighIcon },
  { value: 'urgent', label: getPriorityLabel('urgent'), icon: PriorityUrgentIcon }
])
const mentionCandidates = computed(() => {
  const selfId = authStore.currentUser?.id
  return userList.value.filter((u) => u.id !== selfId)
})

/** 评论编辑器 @ 建议数据源（与 mentionCandidates 一致，格式供 TipTap Mention 使用） */
const mentionMembersForCommentEditor = computed(() =>
  safeArray(mentionCandidates.value).map((u) => ({
    id: u.id,
    label: (u.username ?? '').trim() || `user-${u.id}`,
  }))
)

const mentionDocumentsForDescription = ref<ProjectDocumentTreeNode[]>([])
let mentionDocumentsLoadSequence = 0

const mentionMembersForDescription = computed(() =>
  mentionCandidates.value.map((user) => ({ id: user.id, label: user.username.trim() }))
)

const commentEditorRef = ref<InstanceType<typeof BlockNoteEditorWrapper> | null>(null)
const inlineReplyEditorRef = ref<InstanceType<typeof BlockNoteEditorWrapper> | null>(null)
const commentThreads = computed(() => buildCommentThreads(comments.value, Number.MAX_SAFE_INTEGER))

const breadcrumbScopeName = computed(() => {
  if (props.mode !== 'edit' || !props.task?.projectId) {
    const active = projectStore.projects.find((p) => p.id === projectStore.activeProjectId)
    return active?.name ?? t('common.workspace')
  }
  const project = projectStore.projects.find((p) => p.id === props.task!.projectId)
  return project?.name ?? t('common.workspace')
})

const showBreadcrumb = computed(() => props.mode === 'edit' && !!props.task)
const workspaceSourceLabel = computed(() => {
  if (props.mode !== 'edit' || !props.task?.id) return null
  if (issuePanelStore.workspaceTaskId !== props.task.id) return null
  return issuePanelStore.workspaceSourceLabel
})
const taskDueState = computed(() => getTaskDueState(parseDateInputValue(formDueDate.value), new Date(dueStateNow.value)))
const taskDueDateToneClass = computed(() => {
  if (formStatus.value === 'done') return ''
  const state = taskDueState.value
  if (state.kind === 'today') return 'prop-trigger--due-today'
  if (state.kind === 'overdue') return 'prop-trigger--due-overdue'
  return ''
})
const showAttachmentBody = computed(
  () =>
    attachmentsLoading.value ||
    attachmentUploadError.value.length > 0 ||
    attachments.value.length > 0 ||
    attachmentPendingUploads.value.length > 0
)
const attachmentsCountDisplay = computed(
  () => attachments.value.length + attachmentPendingUploads.value.length
)
const attachmentUploadInProgress = computed(
  () => attachmentPendingUploads.value.length > 0 || attachmentUploadBatchActive.value
)
const isFavorited = computed(() => {
  if (!props.task?.id) return false
  return favoriteStore.isFavorite(props.task.id) || props.task.favorited === true
})
const descriptionFullscreenAriaLabel = computed(() =>
  isDescriptionFullscreen.value ? t('taskEditor.exitFullscreen') : t('taskEditor.enterFullscreen')
)

const creatorName = computed(() => {
  if (props.mode !== 'edit' || !props.task?.creatorId) return null
  const u = userList.value.find((x) => x.id === props.task!.creatorId)
  return u?.username ?? t('common.someone')
})

function relativeTimeFromNow(timestamp: number) {
  const sec = Math.floor((Date.now() - timestamp) / 1000)
  if (sec < 60) return t('taskEditor.justNow')
  const min = Math.floor(sec / 60)
  if (min < 60) return t('taskEditor.minutesAgo', { count: min })
  const h = Math.floor(min / 60)
  if (h < 24) return t('taskEditor.hoursAgo', { count: h })
  const d = Math.floor(h / 24)
  if (d < 30) return t('taskEditor.daysAgo', { count: d })
  const mo = Math.floor(d / 30)
  return t('taskEditor.monthsAgo', { count: mo })
}

const createdAgoText = computed(() => {
  if (!props.task?.createdAt) return ''
  return relativeTimeFromNow(props.task.createdAt)
})

const taskProjectName = computed(() => {
  if (!props.task?.projectId) return null
  const p = projectStore.projects.find((x) => x.id === props.task!.projectId)
  return p?.name ?? null
})

const effectiveProjectId = computed((): number | null => {
  if (props.mode === 'edit' && props.task?.projectId != null) return props.task.projectId
  return projectStore.activeProjectId
})
const showPropRowLabels = computed(
  () => effectiveProjectId.value != null || (props.mode === 'edit' && formLabels.value.length > 0)
)

/** Phase 7: 父任务（用于 Sub-issue of XXX 链接） */
const parentTask = computed(() => {
  if (!props.task?.parentId) return null
  const parentNumericId = String(props.task.parentId)
  return store.tasks.find((t) => t.numericId != null && String(t.numericId) === parentNumericId) ?? null
})
const parentBreadcrumbLabel = computed(() => parentTask.value?.id ?? '')

/** Phase 7: Sub-issues 区块 */
const subIssuesCollapsed = ref(false)
const subIssueRows = ref<{ task: Task; depth: number }[]>([])
const subIssuesLoading = ref(false)
const showSubIssueForm = ref(false)
const subIssueFormTitle = ref('')
const subIssueFormDescription = ref('')
const subIssueFormStatus = ref<Status>('backlog')
const subIssueFormPriority = ref<Priority>('medium')
const subIssueFormAssigneeId = ref<string | number>('')
const subIssueFormPlannedStartDate = ref('')
const subIssueFormDueDate = ref('')
const subIssueSaving = ref(false)
const subIssueTitleInputRef = ref<HTMLInputElement | null>(null)

const subIssueCountDisplay = computed(() => {
  const rows = subIssueRows.value
  const total = rows.length
  const done = rows.filter((r) => r.task.status === 'done').length
  return { done, total }
})

function subIssueAssigneeLabel(task: Task): string {
  const externalName = task.assigneeDisplayName?.trim()
  if (externalName) return externalName
  if (task.assigneeId == null) return ''
  return userList.value.find((user) => user.id === task.assigneeId)?.username?.trim() ?? ''
}

function subIssueAssigneeInitial(task: Task): string {
  return subIssueAssigneeLabel(task).slice(0, 1).toUpperCase()
}

async function loadSubIssues(options?: { preferSnapshot?: boolean }) {
  if (props.mode !== 'edit' || !props.task?.id || props.task.numericId == null) {
    subIssueRows.value = []
    return
  }
  const ctx = captureTaskLoadContext(props.task)
  if (ctx == null) {
    subIssueRows.value = []
    return
  }
  const rootNumericId = props.task.numericId
  const snapshot = options?.preferSnapshot ? readTaskDetailSnapshot(ctx.taskId) : undefined
  if (snapshot) {
    subIssueRows.value = snapshot.subIssueRows
    subIssuesLoading.value = false
    return
  }
  subIssuesLoading.value = true
  try {
    const direct = await store.fetchSubIssues(rootNumericId)
    if (isTaskLoadStale(ctx, props.task)) return
    const rows: { task: Task; depth: number }[] = []
    async function appendChildren(parentNumericId: number, depth: number): Promise<boolean> {
      const children = await store.fetchSubIssues(parentNumericId)
      if (isTaskLoadStale(ctx, props.task)) return false
      for (const t of children) {
        rows.push({ task: t, depth })
        if (t.numericId != null) {
          const ok = await appendChildren(t.numericId, depth + 1)
          if (!ok) return false
        }
      }
      return true
    }
    for (const t of direct) {
      rows.push({ task: t, depth: 0 })
      if (t.numericId != null) {
        const ok = await appendChildren(t.numericId, 1)
        if (!ok) return
      }
    }
    if (isTaskLoadStale(ctx, props.task)) return
    subIssueRows.value = rows
  } finally {
    if (!isTaskLoadStale(ctx, props.task)) {
      subIssuesLoading.value = false
    }
  }
}

async function loadActivities(options?: { silent?: boolean; preferSnapshot?: boolean }) {
  if (props.mode !== 'edit' || !props.task?.id) {
    activities.value = []
    return
  }
  const ctx = captureTaskLoadContext(props.task)
  if (ctx == null) {
    activities.value = []
    return
  }
  const snapshot = options?.preferSnapshot ? readTaskDetailSnapshot(ctx.taskId) : undefined
  if (snapshot) {
    activities.value = snapshot.activities
    activitiesLoading.value = false
    return
  }
  const silent = options?.silent === true && activities.value.length > 0
  if (!silent) activitiesLoading.value = true
  try {
    const list = await activityApi.list(ctx.taskId)
    if (isTaskLoadStale(ctx, props.task)) return
    activities.value = list
  } finally {
    if (!silent && !isTaskLoadStale(ctx, props.task)) {
      activitiesLoading.value = false
    }
  }
}

async function loadComments(options?: { silent?: boolean; preferSnapshot?: boolean }) {
  if (props.mode !== 'edit' || !props.task?.id) {
    comments.value = []
    return
  }
  const ctx = captureTaskLoadContext(props.task)
  if (ctx == null) {
    comments.value = []
    return
  }
  const snapshot = options?.preferSnapshot ? readTaskDetailSnapshot(ctx.taskId) : undefined
  if (snapshot) {
    comments.value = snapshot.comments
    commentsLoading.value = false
    return
  }
  const silent = options?.silent === true && comments.value.length > 0
  if (!silent) commentsLoading.value = true
  try {
    const list = await taskCommentsApi.list(ctx.taskId)
    if (isTaskLoadStale(ctx, props.task)) return
    comments.value = list
  } catch {
    if (!isTaskLoadStale(ctx, props.task)) {
      comments.value = []
    }
  } finally {
    if (!silent && !isTaskLoadStale(ctx, props.task)) {
      commentsLoading.value = false
    }
  }
}

function commentTimeFromIso(iso: string) {
  const ms = Date.parse(iso)
  if (Number.isNaN(ms)) return ''
  return relativeTimeFromNow(ms)
}

function onCommentEditorKeydown(e: KeyboardEvent) {
  if (e.isComposing) return
  if (!(e.metaKey || e.ctrlKey) || e.key !== 'Enter') return
  e.preventDefault()
  void submitComment()
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

function visibleRepliesForThread(thread: ReturnType<typeof buildCommentThreads>[number]) {
  return thread.replies
}

function replyParentFor(comment: TaskCommentDto): TaskCommentDto | null {
  if (comment.parentId == null) return null
  return comments.value.find((item) => item.id === comment.parentId) ?? null
}

function replyContextAuthorName(reply: TaskCommentDto, root: TaskCommentDto): string | null {
  const parent = replyParentFor(reply)
  return parent != null && parent.id !== root.id ? parent.authorName : null
}

function inlineReplyParentAuthorId(rootId: number): number | null {
  const parentId = inlineReplyParentId.value ?? rootId
  return comments.value.find((item) => item.id === parentId)?.authorId ?? null
}

function onInlineReplyEditorKeydown(event: KeyboardEvent, rootId: number) {
  if (event.isComposing) return
  if (!(event.metaKey || event.ctrlKey) || event.key !== 'Enter') return
  event.preventDefault()
  void submitReply(rootId)
}

async function submitComment() {
  if (props.mode !== 'edit' || !props.task?.id || commentSubmitting.value) return
  const body = commentBody.value.trim()
  if (!body) return
  commentSubmitting.value = true
  try {
    const fromDoc = commentEditorRef.value?.getMentionedUserIdsFromDoc?.() ?? []
    await taskCommentsApi.create(props.task.id, {
      body,
      mentionedUserIds: fromDoc,
      parentId: null
    })
    commentBody.value = ''
    await loadComments({ silent: true })
    void notificationStore.refreshUnread()
  } catch (e) {
    console.error(e)
    alert(toApiError(e).message || t('taskEditor.commentSendFailed'))
  } finally {
    commentSubmitting.value = false
  }
}

async function submitReply(rootId: number) {
  if (props.mode !== 'edit' || !props.task?.id) return
  if (replySubmittingRootIds.value.has(rootId)) return
  const body = (replyBodyByRootId.value[rootId] ?? '').trim()
  if (!body) return
  const nextSubmitting = new Set(replySubmittingRootIds.value)
  nextSubmitting.add(rootId)
  replySubmittingRootIds.value = nextSubmitting
  try {
    const fromDoc = inlineReplyEditorRef.value?.getMentionedUserIdsFromDoc?.() ?? []
    const targetAuthorId = inlineReplyParentAuthorId(rootId)
    const ids = [...new Set(targetAuthorId == null ? fromDoc : [...fromDoc, targetAuthorId])]
    await taskCommentsApi.create(props.task.id, {
      body,
      mentionedUserIds: ids,
      parentId: inlineReplyParentId.value ?? rootId
    })
    replyBodyByRootId.value = { ...replyBodyByRootId.value, [rootId]: '' }
    inlineReplyRootId.value = null
    inlineReplyParentId.value = null
    await loadComments({ silent: true })
    void notificationStore.refreshUnread()
  } catch (e) {
    console.error(e)
    alert(toApiError(e).message || t('taskEditor.commentSendFailed'))
  } finally {
    const next = new Set(replySubmittingRootIds.value)
    next.delete(rootId)
    replySubmittingRootIds.value = next
  }
}

async function deleteCommentRow(c: TaskCommentDto) {
  if (!props.task?.id || !c.deletable) return
  if (!window.confirm(t('taskEditor.deleteCommentConfirm'))) return
  try {
    await taskCommentsApi.delete(props.task.id, c.id)
    await loadComments({ silent: true })
  } catch (e) {
    console.error(e)
    alert(toApiError(e).message || t('taskEditor.commentSendFailed'))
  }
}

function openSubIssueForm() {
  if (showSubIssueForm.value) {
    nextTick(() => subIssueTitleInputRef.value?.focus())
    return
  }
  showSubIssueForm.value = true
  subIssueFormTitle.value = ''
  subIssueFormDescription.value = ''
  subIssueFormStatus.value = 'todo'
  subIssueFormPriority.value = props.task?.priority ?? 'medium'
  subIssueFormAssigneeId.value = ''
  subIssueFormPlannedStartDate.value = ''
  subIssueFormDueDate.value = ''
  nextTick(() => subIssueTitleInputRef.value?.focus())
}

function closeSubIssueForm() {
  showSubIssueForm.value = false
}

function onSubIssueFormEscape(e: KeyboardEvent) {
  if (e.defaultPrevented) return
  e.preventDefault()
  closeSubIssueForm()
}

async function submitSubIssue() {
  if (!subIssueFormTitle.value.trim() || !props.task?.id || subIssueSaving.value) return
  const parentNumericId = props.task.numericId
  if (parentNumericId == null) return
  subIssueSaving.value = true
  try {
    const newTask = await store.createTask({
      title: subIssueFormTitle.value.trim(),
      description: subIssueFormDescription.value.trim() || undefined,
      status: subIssueFormStatus.value,
      priority: subIssueFormPriority.value,
      assigneeId: subIssueFormAssigneeId.value === '' ? null : Number(subIssueFormAssigneeId.value),
      plannedStartDate: parseDateInputValue(subIssueFormPlannedStartDate.value),
      dueDate: parseDateInputValue(subIssueFormDueDate.value),
      parentId: parentNumericId
    })
    subIssueRows.value = [...subIssueRows.value, { task: newTask, depth: 0 }]
    subIssueFormTitle.value = ''
    subIssueFormDescription.value = ''
    closeSubIssueForm()
  } finally {
    subIssueSaving.value = false
  }
}

async function onSubIssueStatusPicked(task: Task, nextStatus: Status) {
  await store.transitionTask(task.id, nextStatus)
  await loadSubIssues()
}

async function loadAttachments(opts?: { silent?: boolean; preferSnapshot?: boolean }) {
  const silent = opts?.silent === true
  if (props.mode !== 'edit' || !props.task?.id) {
    attachments.value = []
    return
  }
  const ctx = captureTaskLoadContext(props.task)
  if (ctx == null) {
    attachments.value = []
    return
  }
  const snapshot = opts?.preferSnapshot ? readTaskDetailSnapshot(ctx.taskId) : undefined
  if (snapshot) {
    attachments.value = snapshot.attachments
    attachmentsLoading.value = false
    return
  }
  if (!silent) {
    attachmentsLoading.value = true
    attachmentUploadError.value = ''
  }
  try {
    const list = await attachmentsApi.list(ctx.taskId)
    if (isTaskLoadStale(ctx, props.task)) return
    attachments.value = list
  } catch (e) {
    console.error('Failed to load attachments:', e)
    if (!isTaskLoadStale(ctx, props.task)) {
      attachments.value = []
    }
  } finally {
    if (!silent && !isTaskLoadStale(ctx, props.task)) {
      attachmentsLoading.value = false
    }
  }
}

function openAttachmentInput() {
  if (props.mode === 'edit' && props.task?.id && !attachmentUploadInProgress.value) {
    attachmentInputRef.value?.click()
  }
}

function onAttachmentInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  if (!files?.length || !props.task?.id) return
  attachmentUploadError.value = ''
  attachmentsCollapsed.value = false
  const taskKey = props.task.id
  ;(async () => {
    attachmentUploadBatchActive.value = true
    try {
      for (let i = 0; i < files.length; i++) {
        const file = files[i]
        if (!file) continue
        if (file.size > MAX_ATTACHMENT_SIZE) {
          attachmentUploadError.value = `"${file.name}" ${t('attachments.fileTooLargeSkipped', { size: '10MB' })}`
          continue
        }
        const localId = randomClientId()
        attachmentPendingUploads.value = [
          ...attachmentPendingUploads.value,
          { localId, fileName: file.name, fileSize: file.size, progress: 0 }
        ]
        try {
          await attachmentsApi.upload(taskKey, file, (pct) => {
            const list = attachmentPendingUploads.value
            const idx = list.findIndex((p) => p.localId === localId)
            if (idx < 0) return
            const prev = list[idx]!
            const next = [...list]
            next[idx] = {
              localId: prev.localId,
              fileName: prev.fileName,
              fileSize: prev.fileSize,
              progress: pct
            }
            attachmentPendingUploads.value = next
          })
          attachmentPendingUploads.value = attachmentPendingUploads.value.filter((p) => p.localId !== localId)
          await loadAttachments({ silent: true })
        } catch (e) {
          attachmentPendingUploads.value = attachmentPendingUploads.value.filter((p) => p.localId !== localId)
          attachmentUploadError.value = e instanceof Error ? e.message : t('attachments.uploadFailed')
        }
      }
    } finally {
      attachmentUploadBatchActive.value = false
      input.value = ''
    }
  })()
}

function formatAttachmentSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

function formatAttachmentDate(iso: string): string {
  try {
    const d = new Date(iso)
    const now = new Date()
    const diff = now.getTime() - d.getTime()
    if (diff < 60 * 1000) return t('taskEditor.justNow')
    if (diff < 60 * 60 * 1000) return t('taskEditor.minutesAgo', { count: Math.floor(diff / 60000) })
    if (diff < 24 * 60 * 60 * 1000) return t('taskEditor.hoursAgo', { count: Math.floor(diff / 3600000) })
    return d.toLocaleDateString()
  } catch {
    return iso
  }
}

async function downloadAttachment(att: TaskAttachment) {
  if (!props.task?.id) return
  try {
    await attachmentsApi.download(props.task.id, att.id, att.fileName)
  } catch (e) {
    attachmentUploadError.value = e instanceof Error ? e.message : t('attachments.downloadFailed')
  }
}

async function deleteAttachment(att: TaskAttachment) {
  if (!props.task?.id) return
  try {
    await attachmentsApi.delete(props.task.id, att.id)
    await loadAttachments()
  } catch (e) {
    attachmentUploadError.value = e instanceof Error ? e.message : t('attachments.deleteFailed')
  }
}

watch(
  [() => props.task?.id, () => props.mode],
  () => {
    attachmentPendingUploads.value = []
    attachmentUploadBatchActive.value = false
    loadSubIssues({ preferSnapshot: true })
    loadActivities({ preferSnapshot: true })
    loadComments({ preferSnapshot: true })
    loadAttachments({ preferSnapshot: true })
  },
  { immediate: true }
)
watch(
  [comments, commentsLoading],
  () => {
    if (commentsLoading.value) return
    void hydrateCommentsMermaid()
  },
  { deep: true }
)

async function loadProjectMembers(projectId: number | null) {
  if (projectId == null) {
    userList.value = []
    return
  }
  try {
    userList.value = await projectApi.listMembers(projectId)
  } catch (e) {
    console.error('Failed to load project members:', e)
    userList.value = []
  }
}

async function loadProjectMentionDocuments(projectId: number | null) {
  const sequence = ++mentionDocumentsLoadSequence
  if (projectId == null) {
    mentionDocumentsForDescription.value = []
    return
  }
  try {
    const documents = await documentApi.listTree(projectId)
    if (sequence === mentionDocumentsLoadSequence) mentionDocumentsForDescription.value = documents
  } catch (error) {
    if (sequence !== mentionDocumentsLoadSequence) return
    console.error('Failed to load project documents for mentions:', error)
    mentionDocumentsForDescription.value = []
  }
}

function syncCodexTaskRefresh() {
  if (codexTaskRefreshTimer != null) {
    clearInterval(codexTaskRefreshTimer)
    codexTaskRefreshTimer = null
  }
  if (props.mode !== 'edit' || !props.task) return
  const assignee = userList.value.find((user) => user.id === props.task?.assigneeId)
  if (
    assignee?.userType !== 'codex'
    || props.task.status === 'done'
    || props.task.status === 'canceled'
    || props.task.status === 'duplicate'
  ) return
  codexTaskRefreshTimer = setInterval(() => {
    if (!props.task || saveStatus.value === 'saving' || autoSaveTimer != null || isDescriptionEditing.value) return
    const previousProgress = props.task.progressPercent ?? 0
    void store.fetchTaskByKey(props.task.id).then((task) => {
      if ((task.progressPercent ?? 0) === previousProgress) return
      void loadActivities({ silent: true })
      if (task.status === 'done') void loadComments({ silent: true })
    }).catch(() => undefined)
  }, 2_000)
}

onMounted(async () => {
  await Promise.all([
    loadProjectMembers(effectiveProjectId.value),
    loadProjectMentionDocuments(effectiveProjectId.value)
  ])
})

onMounted(() => {
  dueStateNowTimer = setInterval(() => {
    dueStateNow.value = Date.now()
  }, 60_000)
  void hydrateCommentsMermaid()
})

watch(effectiveProjectId, (id) => {
  loadProjectMembers(id)
  loadProjectMentionDocuments(id)
})

watch(
  [() => props.task?.id, () => props.task?.assigneeId, () => props.task?.status, () => props.task?.progressPercent, userList],
  syncCodexTaskRefresh,
  { immediate: true }
)

onBeforeUnmount(() => {
  mentionDocumentsLoadSequence += 1
  if (codexTaskRefreshTimer != null) {
    clearInterval(codexTaskRefreshTimer)
    codexTaskRefreshTimer = null
  }
  if (dueStateNowTimer != null) {
    clearInterval(dueStateNowTimer)
    dueStateNowTimer = null
  }
})

function toDateInputValue(ms: number | undefined | null): string {
  return formatDateInputValue(ms)
}

function formLabelStableKey(rows: { id?: number; name: string }[]): string {
  return [...safeArray(rows)]
    .map((r) => {
      const n = r.name.trim()
      if (!n) return null
      return r.id != null ? `i:${r.id}` : `n:${n.toLowerCase()}`
    })
    .filter((x): x is string => x != null)
    .sort()
    .join('|')
}

function taskLabelsStableKey(labels: Task['labels'] | undefined): string {
  return [...safeArray(labels)]
    .map((l) => `i:${l.id}`)
    .sort()
    .join('|')
}

function labelNameTaken(name: string): boolean {
  const n = name.trim().toLowerCase()
  if (!n) return true
  return formLabels.value.some((c) => c.name.trim().toLowerCase() === n)
}

function activityLabelTone(name: string): number {
  const normalized = name.trim().toLowerCase()
  const label = formLabels.value.find((entry) => entry.name.trim().toLowerCase() === normalized)
  return getTaskLabelTone(label ?? { name })
}

function addFormLabel(entry: { id?: number; name: string }) {
  const n = entry.name.trim()
  if (!n || labelNameTaken(n)) return
  formLabels.value = [...formLabels.value, { id: entry.id, name: n }]
}

function pickSuggestion(s: { id: number; name: string }) {
  addFormLabel(s)
  labelInput.value = ''
}

function commitLabelInput(name: string) {
  addFormLabel({ name })
  labelInput.value = ''
}

function removeFormLabel(idx: number) {
  formLabels.value = formLabels.value.filter((_, i) => i !== idx)
}

function onLabelPickerOpenChange(isOpen: boolean) {
  labelPickerOpen.value = isOpen
  if (
    !isOpen &&
    props.mode === 'edit' &&
    props.task &&
    formLabelStableKey(formLabels.value) !== taskLabelsStableKey(props.task.labels)
  ) {
    persistFormDraftIfNeeded()
    scheduleAutoSave()
  }
}

async function onDeleteLabelDefinition(labelId: number) {
  const pid = effectiveProjectId.value
  if (pid == null) return
  try {
    await projectApi.deleteLabel(pid, labelId)
    store.stripProjectLabelFromTasks(pid, labelId)
    formLabels.value = formLabels.value.filter((c) => c.id !== labelId)
    taskLabelComboboxRef.value?.removeFromSuggestions(labelId)
  } catch (e) {
    console.error('Failed to delete project label:', e)
  }
}

/** 全选删除列表后可能留下仅空列表项（如 "- \n- "）。仅在保存时视为空，不往编辑器回写，避免可见的覆盖过程 */
function clampTaskProgress(value: unknown): number {
  const n = typeof value === 'number' && Number.isFinite(value) ? Math.round(value) : 0
  return Math.max(0, Math.min(100, n))
}

/** 与 TaskService.OPEN_STATUSES_FOR_PROGRESS_LINKAGE 一致，用于拖动进度时即时联动状态 */
const OPEN_STATUSES_FOR_PROGRESS: Status[] = ['backlog', 'todo', 'in_progress', 'in_review']

function syncFormStatusFromProgress() {
  if (props.mode !== 'edit' || !props.task) return
  const p = clampTaskProgress(formProgressPercent.value)
  const st = formStatus.value
  if (st === 'done' && p < 100) {
    formStatus.value = 'in_progress'
    showProgressStatusHint(t('taskEditor.progressReopensTask'))
    return
  }
  if (p === 100 && OPEN_STATUSES_FOR_PROGRESS.includes(st)) {
    formStatus.value = 'done'
    showProgressStatusHint(t('taskEditor.progressCompletesTask'))
  }
}

/** 完成状态与进度双向联动：状态切换为已完成时，进度立即反映为 100%。 */
function syncFormProgressFromStatus() {
  if (props.mode !== 'edit' || !props.task) return
  if (formStatus.value === 'done' && clampTaskProgress(formProgressPercent.value) < 100) {
    formProgressPercent.value = 100
  }
}

function showProgressStatusHint(message: string) {
  progressStatusHint.value = message
  if (progressStatusHintTimer) clearTimeout(progressStatusHintTimer)
  progressStatusHintTimer = setTimeout(() => {
    progressStatusHint.value = ''
    progressStatusHintTimer = null
  }, SAVED_INDICATOR_MS)
}

function descriptionForSave(desc: string | undefined): string {
  const s = (desc ?? '').trim()
  if (!s) return ''
  const blockDoc = parseBlockNoteStoredBlocks(s)
  if (blockDoc !== undefined) {
    return blockNoteDocHasPersistableContent(blockDoc) ? s : ''
  }
  const emptyListLine = /^\s*[-*+]\s*$|^\s*\d+\.\s*$/
  const onlyEmptyLists = s.split(/\n/).every((line) => !line.trim() || emptyListLine.test(line.trim()))
  return onlyEmptyLists ? '' : s
}

function loadForm(options?: { preserveDescription?: boolean }) {
  const preserveDescription = options?.preserveDescription === true
  /** currentTask 短暂为 null（列表刷新、项目切换等）时不要走下方 else 整表重置 */
  if (props.mode === 'edit' && !props.task) {
    return
  }
  if (props.mode === 'edit' && props.task) {
    formTitle.value = props.task.title
    if (!preserveDescription) {
      formDescription.value = props.task.description || ''
    }
    formStatus.value = props.task.status
    formPriority.value = props.task.priority
    formAssigneeId.value = props.task.assigneeId ?? ''
    formPlannedStartDate.value = toDateInputValue(props.task.plannedStartDate ?? undefined)
    formDueDate.value = toDateInputValue(props.task.dueDate ?? undefined)
    formProgressPercent.value = clampTaskProgress(props.task.progressPercent ?? 0)
    formLabels.value = safeArray(props.task.labels).map((l) => ({ id: l.id, name: l.name }))
    labelInput.value = ''

    const draft = readTaskEditDraft(props.task.id)
    if (draft && draft.savedAt > props.task.updatedAt) {
      formTitle.value = draft.title
      if (!preserveDescription) {
        formDescription.value = draft.description
      }
      formStatus.value = draft.status
      formPriority.value = draft.priority
      formAssigneeId.value = draft.assigneeId == null ? '' : draft.assigneeId
      formPlannedStartDate.value = draft.plannedStartDate
      formDueDate.value = draft.dueDate
      formProgressPercent.value = clampTaskProgress(draft.progressPercent)
    } else if (draft) {
      clearTaskEditDraft(props.task.id)
    }
  } else {
    formTitle.value = ''
    formDescription.value = ''
    formStatus.value = props.defaultStatus ?? 'todo'
    formPriority.value = 'medium'
    formAssigneeId.value = ''
    formPlannedStartDate.value = todayDateInputValue()
    formDueDate.value = ''
    formProgressPercent.value = 0
    formLabels.value = []
    labelInput.value = ''
  }
}

watch(
  () => props.task,
  (nextTask, prevTask) => {
    const taskChanged = nextTask?.id !== prevTask?.id
    if (justSavedTaskId.value !== null && props.task?.id === justSavedTaskId.value) {
      return
    }
    if (!taskChanged && isDescriptionEditing.value) {
      loadForm({ preserveDescription: true })
      return
    }
    loadForm()
  },
  { immediate: true }
)
watch(() => props.mode, () => loadForm())
watch(() => props.defaultStatus, () => {
  if (props.mode === 'create') formStatus.value = props.defaultStatus ?? 'todo'
})

watch(formProgressPercent, () => syncFormStatusFromProgress())
watch(formStatus, () => syncFormProgressFromStatus())

function getPayload() {
  const plannedStartMs = parseDateInputValue(formPlannedStartDate.value)
  const dueDateMs = parseDateInputValue(formDueDate.value)
  const rawAssignee = formAssigneeId.value
  const assigneeId =
    rawAssignee === '' || rawAssignee == null
      ? null
      : (() => {
          const n = Number(rawAssignee)
          return Number.isFinite(n) ? n : null
        })()
  return {
    title: formTitle.value.trim(),
    description: descriptionForSave(formDescription.value),
    status: formStatus.value,
    priority: formPriority.value,
    assigneeId,
    plannedStartDate: plannedStartMs,
    dueDate: dueDateMs,
    progressPercent: clampTaskProgress(formProgressPercent.value)
  }
}

function dueDateKey(ms: number | undefined | null): string {
  if (ms == null) return ''
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function isPayloadEqual(
  a: {
    title: string
    description?: string
    status: Status
    priority: Priority
    assigneeId: number | null
    plannedStartDate?: number
    dueDate?: number
    progressPercent: number
  },
  b: {
    title: string
    description?: string
    status: Status
    priority: Priority
    assigneeId?: number | null
    plannedStartDate?: number | null
    dueDate?: number | null
    progressPercent?: number
  }
) {
  return (
    a.title === (b.title ?? '') &&
    descriptionForSave(a.description) === descriptionForSave(b.description) &&
    a.status === b.status &&
    a.priority === b.priority &&
    (a.assigneeId ?? null) === (b.assigneeId ?? null) &&
    dueDateKey(a.plannedStartDate) === dueDateKey(b.plannedStartDate ?? undefined) &&
    dueDateKey(a.dueDate) === dueDateKey(b.dueDate ?? undefined) &&
    a.progressPercent === clampTaskProgress(b.progressPercent ?? 0)
  )
}

function formSaveSignature(): string {
  const payload = getPayload()
  return JSON.stringify([
    payload.title,
    payload.description ?? '',
    payload.status,
    payload.priority,
    payload.assigneeId,
    dueDateKey(payload.plannedStartDate),
    dueDateKey(payload.dueDate),
    payload.progressPercent,
    formLabelStableKey(formLabels.value)
  ])
}

function isFormDirty(): boolean {
  if (props.mode !== 'edit' || !props.task) return false
  if (
    lastAcknowledgedSave?.taskId === props.task.id &&
    lastAcknowledgedSave.signature === formSaveSignature()
  ) {
    return false
  }
  const payload = getPayload()
  const current = {
    title: props.task.title,
    description: props.task.description,
    status: props.task.status,
    priority: props.task.priority,
    assigneeId: props.task.assigneeId ?? null,
    plannedStartDate: props.task.plannedStartDate ?? null,
    dueDate: props.task.dueDate ?? null,
    progressPercent: props.task.progressPercent ?? 0
  }
  return (
    !isPayloadEqual(payload, current) ||
    formLabelStableKey(formLabels.value) !== taskLabelsStableKey(props.task.labels)
  )
}

async function performAutoSave() {
  if (props.mode !== 'edit' || !props.task) return
  const payload = getPayload()
  if (!payload.title) return
  if (!isFormDirty()) return
  if (descriptionUploadState.value.hasPending || descriptionUploadState.value.hasFailed) return

  const clearPlannedStart =
    !formPlannedStartDate.value && props.task.plannedStartDate != null ? true : undefined
  const clearDueDate =
    !formDueDate.value && props.task.dueDate != null ? true : undefined

  persistFormDraftIfNeeded()

  saveStatus.value = 'saving'
  justSavedTaskId.value = props.task.id
  try {
    const labelsDirty =
      formLabelStableKey(formLabels.value) !== taskLabelsStableKey(props.task.labels)
    const merged = await store.updateTask(props.task.id, {
      title: payload.title,
      description: payload.description,
      status: payload.status,
      priority: payload.priority,
      assigneeId: payload.assigneeId,
      clearAssignee:
        payload.assigneeId === null &&
        (props.task.assigneeId != null || !!(props.task.assigneeDisplayName?.trim())),
      plannedStartDate: payload.plannedStartDate,
      clearPlannedStart,
      dueDate: payload.dueDate,
      clearDueDate,
      progressPercent: payload.progressPercent,
      ...(labelsDirty ? { labels: toLabelWriteItems(formLabels.value) } : {})
    })
    // 保存后故意跳过 loadForm，避免覆盖正文；服务端进度↔状态联动需从合并结果写回
    formStatus.value = merged.status
    formProgressPercent.value = clampTaskProgress(merged.progressPercent ?? 0)
    formLabels.value = safeArray(merged.labels).map((l) => ({ id: l.id, name: l.name }))
    lastAcknowledgedSave = { taskId: props.task.id, signature: formSaveSignature() }
    clearTaskEditDraft(props.task.id)
    await loadActivities({ silent: true })
    saveStatus.value = 'saved'
    setTimeout(() => {
      saveStatus.value = 'idle'
    }, SAVED_INDICATOR_MS)
  } catch (error) {
    console.error('Auto-save failed:', error)
    saveStatus.value = 'failed'
  } finally {
    // 乐观更新（同步）与 API 返回（异步）分两次触发 props.task watcher；
    // 等微任务队列清空后再解锁，确保两次触发都被 justSavedTaskId 屏蔽。
    await nextTick()
    justSavedTaskId.value = null
    const shouldRecheck = autoSaveQueuedWhileSaving
    autoSaveQueuedWhileSaving = false
    if (shouldRecheck && !labelPickerOpen.value && isFormDirty()) scheduleAutoSave()
  }
}

function retryAutoSave() {
  if (saveStatus.value === 'saving') return
  void performAutoSave()
}

function persistFormDraftIfNeeded() {
  if (props.mode !== 'edit' || !props.task) return
  const payload = getPayload()
  if (!payload.title) return
  saveTaskEditDraft(props.task.id, {
    title: payload.title,
    description: payload.description ?? '',
    status: payload.status,
    priority: payload.priority,
    assigneeId: payload.assigneeId,
    plannedStartDate: formPlannedStartDate.value,
    dueDate: formDueDate.value,
    progressPercent: payload.progressPercent
  })
}

function scheduleAutoSave() {
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = null
    performAutoSave()
  }, AUTO_SAVE_DEBOUNCE_MS)
}

/** 展示层草稿 flush：取消本地 debounce 并将最新表单提交到 store lane */
async function flushEditorDraft() {
  if (props.mode !== 'edit' || !props.task) return
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer)
    autoSaveTimer = null
  }
  await performAutoSave()
}

/** 关抽屉 / 切换任务前调用：flushEditorDraft -> flushTask -> drainTask */
async function flushPendingSave() {
  if (props.mode !== 'edit' || !props.task) return
  await flushEditorDraft()
  store.flushTask(props.task.id)
  const drained = await store.drainTask(props.task.id)
  if (!drained.ok) {
    if (drained.reason === 'save_failed') {
      notifySaveState(t('taskEditor.saveFailedBlockClose'))
      throw drained.lastError ?? new Error('save_failed')
    }
    notifySaveState(t('taskEditor.saveTimeoutWarn'))
  }
}

function notifySaveState(message: string) {
  try {
    window.alert(message)
  } catch {
    console.warn(message)
  }
}

onBeforeUnmount(() => {
  if (progressStatusHintTimer) clearTimeout(progressStatusHintTimer)
  document.removeEventListener('fullscreenchange', syncDescriptionFullscreenState)
  if (props.mode !== 'edit' || !props.task) return
  void flushEditorDraft().catch((e) => {
    console.warn('Flush editor draft on unmount failed:', e)
  })
  store.flushTask(props.task.id)
})

defineExpose({ flushPendingSave })

function onDescriptionBlur() {
  isDescriptionEditing.value = false
  if (props.mode !== 'edit' || !props.task) return
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer)
    autoSaveTimer = null
  }
  const payload = getPayload()
  const current = {
    title: props.task.title,
    description: props.task.description,
    status: props.task.status,
    priority: props.task.priority,
    assigneeId: props.task.assigneeId ?? null,
    plannedStartDate: props.task.plannedStartDate ?? null,
    dueDate: props.task.dueDate ?? null,
    progressPercent: props.task.progressPercent ?? 0
  }
  if (isPayloadEqual(payload, current)) return
  void performAutoSave()
}

function onDescriptionFocus() {
  isDescriptionEditing.value = true
}

watch(
  () => [
    formTitle.value,
    formDescription.value,
    formStatus.value,
    formPriority.value,
    formAssigneeId.value,
    formPlannedStartDate.value,
    formDueDate.value,
    formProgressPercent.value,
    formLabels.value
  ],
  () => {
    if (props.mode !== 'edit' || !props.task) return
    if (!isFormDirty()) {
      clearTaskEditDraft(props.task.id)
      return
    }

    persistFormDraftIfNeeded()
    if (labelPickerOpen.value) return
    if (saveStatus.value === 'saving' || justSavedTaskId.value === props.task.id) {
      autoSaveQueuedWhileSaving = true
      return
    }
    scheduleAutoSave()
  },
  { deep: true }
)

const closeEditor = () => {
  emit('close')
}

function navigateTo(taskId: string | null | undefined) {
  if (!taskId) return
  emit('navigate', taskId)
}
function navigateToParentTask() {
  if (!parentTask.value?.id) return
  navigateTo(parentTask.value.id)
}

function navigateToProject() {
  if (props.task?.projectId != null) {
    projectStore.setActiveProject(props.task.projectId)
  }
  router.push('/')
}

async function toggleFavorite() {
  if (!props.task) return
  await favoriteStore.toggleFavorite(props.task)
  await loadActivities({ silent: true })
}

function syncDescriptionFullscreenState() {
  isDescriptionFullscreen.value = document.fullscreenElement === descriptionSectionRef.value
}

onMounted(() => {
  document.addEventListener('fullscreenchange', syncDescriptionFullscreenState)
})

async function toggleDescriptionFullscreen() {
  const target = descriptionSectionRef.value
  if (!target) return
  if (document.fullscreenElement === target) {
    await document.exitFullscreen()
    return
  }
  await target.requestFullscreen()
}
</script>

<template>
  <aside
    class="editor-panel"
    :class="{
      'editor-panel--inline': props.variant === 'inline',
      'editor-panel--create': props.mode === 'create'
    }"
    :aria-label="t('taskEditor.workspaceAria')"
  >
    <div class="editor-header">
      <div class="editor-header-meta">
        <nav v-if="showBreadcrumb" class="editor-breadcrumb" :aria-label="t('taskEditor.breadcrumbAria')">
          <button type="button" class="editor-breadcrumb-link" @click="navigateToProject">
            {{ breadcrumbScopeName }}
          </button>
          <template v-if="parentTask">
            <span class="editor-breadcrumb-separator">/</span>
            <button type="button" class="editor-breadcrumb-link" @click="navigateToParentTask">
              {{ parentBreadcrumbLabel }}
            </button>
          </template>
          <span class="editor-breadcrumb-separator">/</span>
          <span class="editor-breadcrumb-current">{{ task?.id }}</span>
        </nav>
        <template v-else>
          <span v-if="task?.id" class="issue-id">{{ task.id }}</span>
          <h2>{{ mode === 'create' ? t('taskEditor.newIssue') : t('taskEditor.issue') }}</h2>
        </template>
        <button
          v-if="showBreadcrumb"
          type="button"
          class="header-icon-btn"
          :class="{ 'header-icon-btn--active': isFavorited }"
          :aria-label="isFavorited ? t('taskEditor.removeFromFavorites') : t('taskEditor.addToFavorites')"
          @click="toggleFavorite"
        >
          <Star class="icon-16" :fill="isFavorited ? 'currentColor' : 'none'" />
        </button>
      </div>
      <div class="editor-header-actions">
        <div class="save-indicator-slot" role="status" aria-live="polite">
          <span v-if="saveStatus === 'saved'" class="save-indicator save-indicator--saved">
            {{ t('taskEditor.saved') }}
          </span>
          <span v-else-if="saveStatus === 'saving'" class="save-indicator save-indicator--saving">
            {{ t('taskEditor.saving') }}
          </span>
          <span v-else-if="saveStatus === 'failed'" class="save-indicator save-indicator--failed">
            {{ t('taskEditor.saveFailed') }}
            <button type="button" class="save-indicator-retry" @click="retryAutoSave">
              {{ t('taskEditor.retrySave') }}
            </button>
          </span>
        </div>
        <div v-if="workspaceSourceLabel" class="issue-source">{{ workspaceSourceLabel }}</div>
        <div v-if="position && total" class="issue-position">{{ position }} / {{ total }}</div>
        <button
          class="nav-btn"
          :disabled="!previousTaskId"
          :aria-label="t('taskEditor.previousIssue')"
          @click="navigateTo(previousTaskId)"
        >
          ←
        </button>
        <button
          class="nav-btn"
          :disabled="!nextTaskId"
          :aria-label="t('taskEditor.nextIssue')"
          @click="navigateTo(nextTaskId)"
        >
          →
        </button>
        <button class="close-btn" @click="closeEditor" :aria-label="t('common.close')">×</button>
      </div>
    </div>
    <div class="editor-body">
      <div class="editor-content">
        <div class="editor-content-scroll">
        <section class="content-section content-section--title">
          <input
            v-model="formTitle"
            type="text"
            class="title-input"
            :placeholder="t('taskEditor.issueTitlePlaceholder')"
            autofocus
            @keydown.enter.exact.prevent="focusDescription"
          />
        </section>

          <section
            ref="descriptionSectionRef"
            class="content-section description-section"
            :class="{ 'description-section--fullscreen': isDescriptionFullscreen }"
          >
            <div class="description-section__toolbar">
              <button
                type="button"
                class="content-action-btn"
                :aria-label="descriptionFullscreenAriaLabel"
                :title="descriptionFullscreenAriaLabel"
                @click="toggleDescriptionFullscreen"
              >
                <Minimize2 v-if="isDescriptionFullscreen" class="icon-14" />
                <Maximize2 v-else class="icon-14" />
              </button>
            </div>
            <div class="description-section__surface">
              <BlockNoteEditorWrapper
                ref="descriptionEditorRef"
                v-model="formDescription"
                :block-chrome="true"
                :mention-members="mentionMembersForDescription"
                :mention-documents="mentionDocumentsForDescription"
                :mention-members-group-text="t('documents.mentionMembersGroup')"
                :mention-documents-group-text="t('documents.mentionDocumentsGroup')"
                :mention-menu-no-matches-text="t('documents.mentionNoMatches')"
                :mention-menu-loading-text="t('common.loading')"
                @upload-state-change="onDescriptionUploadStateChange"
                @focus="onDescriptionFocus"
                @blur="onDescriptionBlur"
                :placeholder="t('taskEditor.descriptionPlaceholder')"
                :min-height="isDescriptionFullscreen ? 520 : 96"
              />
            </div>
          </section>

        <input
          ref="attachmentInputRef"
          type="file"
          multiple
          style="display: none"
          @change="onAttachmentInputChange"
        />
        <section v-if="mode === 'edit' && task" class="content-section subdued linear-section">
          <div class="linear-section-head-wrap">
            <button
              v-if="showAttachmentBody"
              type="button"
              class="linear-section-head"
              :aria-expanded="!attachmentsCollapsed"
              @click="attachmentsCollapsed = !attachmentsCollapsed"
            >
              <span class="linear-section-chevron">{{ attachmentsCollapsed ? '▸' : '▾' }}</span>
              <span class="linear-section-title">{{ t('taskEditor.attachments') }}</span>
              <span class="linear-section-count">{{ attachmentsCountDisplay }}</span>
            </button>
            <div v-else class="linear-section-head linear-section-head--static">
              <span class="linear-section-title">{{ t('taskEditor.attachments') }}</span>
              <span class="linear-section-count">{{ attachmentsCountDisplay }}</span>
            </div>
            <button
              type="button"
              class="content-action-btn linear-section-action"
              :aria-label="attachmentUploadInProgress ? t('taskEditor.attachmentsUploading') : t('common.attach')"
              :disabled="attachmentUploadInProgress"
              @click="openAttachmentInput"
            >
              <Loader2 v-if="attachmentUploadInProgress" class="icon-14 linear-attachment-upload-spin" />
              <Paperclip v-else class="icon-14" />
            </button>
          </div>
          <div v-if="showAttachmentBody" v-show="!attachmentsCollapsed" class="linear-section-body">
            <p v-if="attachmentsLoading" class="linear-placeholder">{{ t('common.loading') }}</p>
            <template v-else>
              <p v-if="attachmentUploadError" class="linear-placeholder linear-placeholder--error">{{ attachmentUploadError }}</p>
              <ul v-if="attachmentPendingUploads.length" class="linear-sub-list">
                <li
                  v-for="pending in attachmentPendingUploads"
                  :key="pending.localId"
                  class="linear-sub-item linear-attachment-row linear-attachment-row--uploading"
                >
                  <Loader2 class="icon-14 linear-attachment-upload-spin" aria-hidden="true" />
                  <div class="linear-attachment-pending-body">
                    <span class="linear-attachment-pending-name">{{ pending.fileName }}</span>
                    <div
                      class="linear-attachment-progress-track"
                      role="progressbar"
                      :aria-valuenow="pending.progress"
                      aria-valuemin="0"
                      aria-valuemax="100"
                      :aria-label="t('attachments.uploading')"
                    >
                      <div class="linear-attachment-progress-fill" :style="{ width: `${pending.progress}%` }" />
                    </div>
                  </div>
                  <span class="linear-sub-meta">{{ formatAttachmentSize(pending.fileSize) }}</span>
                </li>
              </ul>
              <ul v-if="attachments.length" class="linear-sub-list">
                <li v-for="att in attachments" :key="att.id" class="linear-sub-item linear-attachment-row">
                  <button type="button" class="linear-sub-link linear-sub-link--btn" @click="downloadAttachment(att)">{{ att.fileName }}</button>
                  <span class="linear-sub-meta">{{ formatAttachmentSize(att.fileSize) }} · {{ formatAttachmentDate(att.createdAt) }}</span>
                  <button
                    type="button"
                    class="content-action-btn linear-attachment-delete"
                    :aria-label="t('taskEditor.deleteAttachment')"
                    @click="deleteAttachment(att)"
                  >
                    ×
                  </button>
                </li>
              </ul>
              <p v-if="!attachments.length && !attachmentPendingUploads.length" class="linear-placeholder">
                {{ t('taskEditor.noAttachments') }}
              </p>
            </template>
          </div>
        </section>

        <section v-if="mode === 'edit' && task" class="content-section subdued linear-section">
          <div class="sub-issue-toolbar">
            <button
              type="button"
              class="linear-section-head"
              :aria-expanded="!subIssuesCollapsed"
              :aria-label="subIssuesCollapsed ? t('taskEditor.expandSubIssues') : t('taskEditor.collapseSubIssues')"
              @click="subIssuesCollapsed = !subIssuesCollapsed"
            >
              <ChevronRight v-if="subIssuesCollapsed" class="icon-14 linear-section-chevron" aria-hidden="true" />
              <ChevronDown v-else class="icon-14 linear-section-chevron" aria-hidden="true" />
              <span class="linear-section-title">{{ t('taskEditor.subIssues') }}</span>
              <span class="linear-section-count">{{ subIssueCountDisplay.done }}/{{ subIssueCountDisplay.total }}</span>
            </button>
            <div v-if="!subIssuesCollapsed" class="sub-issue-toolbar-actions">
              <button
                type="button"
                class="sub-issue-icon-action"
                :aria-label="t('taskEditor.createNewSubIssue')"
                :title="t('taskEditor.createNewSubIssue')"
                @click="openSubIssueForm"
              >
                <Plus class="icon-14" aria-hidden="true" />
              </button>
            </div>
          </div>
          <div v-show="!subIssuesCollapsed" class="linear-section-body sub-issue-body">
            <p v-if="subIssuesLoading" class="linear-placeholder">{{ t('common.loading') }}</p>
            <template v-else>
              <ul v-if="subIssueRows.length" class="linear-sub-list sub-issue-list">
                <li
                  v-for="row in subIssueRows"
                  :key="row.task.id"
                  class="linear-sub-item linear-sub-row"
                  :style="{ paddingLeft: row.depth > 0 ? `${row.depth * 20}px` : undefined }"
                >
                  <TaskRowStatusPicker
                    :task-id="row.task.id"
                    :status="row.task.status"
                    @change="(s) => onSubIssueStatusPicked(row.task, s)"
                  />
                  <button
                    type="button"
                    class="linear-sub-link"
                    @click="navigateTo(row.task.id)"
                  >
                    <span class="sub-issue-title">{{ row.task.title }}</span>
                    <span v-if="(row.task.subIssueCount ?? 0) > 0" class="linear-sub-xy">
                      {{ row.task.completedSubIssueCount ?? 0 }}/{{ row.task.subIssueCount }}
                    </span>
                    <span
                      v-if="subIssueAssigneeInitial(row.task)"
                      class="sub-issue-assignee"
                      :title="subIssueAssigneeLabel(row.task)"
                    >
                      {{ subIssueAssigneeInitial(row.task) }}
                    </span>
                  </button>
                </li>
              </ul>
              <div
                v-if="showSubIssueForm"
                class="linear-inline-form"
                @keydown.esc="onSubIssueFormEscape"
              >
                <div class="linear-inline-heading">
                  <CustomSelect
                    class="linear-inline-status-select"
                    v-model="subIssueFormStatus"
                    :options="statusOptions"
                    :search-placeholder="t('boardView.filterByStatus')"
                    search-shortcut-badge="S"
                    :aria-label="t('common.status')"
                    trigger-class="linear-inline-status-trigger"
                  />
                  <input
                    ref="subIssueTitleInputRef"
                    v-model="subIssueFormTitle"
                    type="text"
                    class="linear-inline-title"
                    :placeholder="t('taskEditor.issueTitlePlaceholder')"
                    @keydown.enter.exact.prevent="submitSubIssue"
                  />
                </div>
                <textarea
                  v-model="subIssueFormDescription"
                  class="linear-inline-description"
                  rows="2"
                  :placeholder="t('taskEditor.subIssueDescriptionPlaceholder')"
                />
                <div class="linear-inline-footer">
                  <div class="linear-inline-props">
                    <span class="linear-inline-project" :title="taskProjectName ?? t('taskEditor.noProject')">
                      <Folder class="icon-14" aria-hidden="true" />
                      <span>{{ taskProjectName ?? t('taskEditor.noProject') }}</span>
                    </span>
                    <CustomSelect
                      v-model="subIssueFormPriority"
                      :options="priorityOptions"
                      :aria-label="t('common.priority')"
                      trigger-class="linear-inline-trigger"
                    />
                    <AssigneeSelect
                      v-model="subIssueFormAssigneeId"
                      :users="userList"
                      :placeholder="t('common.unassigned')"
                      :aria-label="t('common.assignee')"
                      trigger-class="linear-inline-trigger"
                      variant="compact"
                    />
                    <CustomDatePicker
                      class="linear-inline-date-select"
                      v-model="subIssueFormPlannedStartDate"
                      :placeholder="t('common.plannedStartDate')"
                      :aria-label="t('common.plannedStartDate')"
                      trigger-class="linear-inline-trigger linear-inline-date-trigger"
                    />
                    <CustomDatePicker
                      class="linear-inline-date-select"
                      v-model="subIssueFormDueDate"
                      :placeholder="t('common.dueDate')"
                      :aria-label="t('common.dueDate')"
                      trigger-class="linear-inline-trigger linear-inline-date-trigger"
                    />
                  </div>
                  <div class="linear-inline-actions">
                    <button type="button" class="linear-inline-discard" @click="closeSubIssueForm">
                      {{ t('taskEditor.discard') }}
                    </button>
                    <button
                      type="button"
                      class="linear-inline-create"
                      :disabled="!subIssueFormTitle.trim() || subIssueSaving"
                      @click="submitSubIssue"
                    >
                      {{ subIssueSaving ? t('taskEditor.creatingSubIssue') : t('taskEditor.createSubIssue') }}
                    </button>
                  </div>
                </div>
              </div>
            </template>
          </div>
        </section>

        <section v-if="mode === 'edit' && task" class="content-section subdued linear-section">
          <div class="linear-section-head linear-section-head--static">
            <span class="linear-section-title">{{ t('taskEditor.comments') }}</span>
          </div>
          <div class="linear-section-body">
            <div v-if="commentsLoading" class="activity-empty">{{ t('taskEditor.commentsLoading') }}</div>
            <div
              v-else-if="commentThreads.length"
              ref="commentsMermaidHostRef"
              class="task-comments-list"
            >
              <div v-for="thread in commentThreads" :key="thread.root.id" class="task-comment-thread">
                <div class="task-comment-row task-comment-row--root">
                  <span
                    class="task-comment-avatar"
                    :style="getAvatarColorByUsername(thread.root.authorName)"
                    aria-hidden="true"
                  >
                    {{ getActivityAvatarLabel(thread.root.authorName) }}
                  </span>
                  <div class="task-comment-content">
                    <div class="task-comment-head">
                      <div class="task-comment-meta">
                        <div class="task-comment-meta-line">
                          <strong>{{ thread.root.authorName }}</strong>
                          <span>· {{ commentTimeFromIso(thread.root.createdAt) }}</span>
                          <button
                            type="button"
                            class="task-comment-reply-btn"
                            :aria-label="t('taskEditor.reply')"
                            :title="t('taskEditor.reply')"
                            @click="openInlineReply(thread.root.id, thread.root.id)"
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
                      </div>
                    </div>
                    <div class="task-comment-body markdown-body" v-html="renderBody(thread.root.body, renderMarkdown)" />
                  </div>
                </div>
                <div v-if="thread.replies.length" class="task-comment-replies">
                  <div v-for="reply in visibleRepliesForThread(thread)" :key="reply.id" class="task-comment-row task-comment-row--reply">
                    <span
                      class="task-comment-avatar"
                      :style="getAvatarColorByUsername(reply.authorName)"
                      aria-hidden="true"
                    >
                      {{ getActivityAvatarLabel(reply.authorName) }}
                    </span>
                    <div class="task-comment-content">
                      <div class="task-comment-head">
                        <div class="task-comment-meta">
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
                        </div>
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
                <div v-if="inlineReplyRootId === thread.root.id" class="task-comment-reply-compose" @keydown.capture="(e) => onInlineReplyEditorKeydown(e, thread.root.id)">
                  <span
                    class="comment-compose-avatar"
                    :style="getAvatarColorByUsername(authStore.currentUser?.username ?? '')"
                    aria-hidden="true"
                  >
                    {{ getActivityAvatarLabel(authStore.currentUser?.username ?? '') }}
                  </span>
                  <div class="comment-compose-input comment-compose-input--with-send">
                    <BlockNoteEditorWrapper
                      ref="inlineReplyEditorRef"
                      :model-value="replyBodyByRootId[thread.root.id] ?? ''"
                      :mention-members="mentionMembersForCommentEditor"
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
            <div v-else class="activity-empty">{{ t('taskEditor.noComments') }}</div>
            <div class="comment-compose" @keydown.capture="onCommentEditorKeydown">
              <div class="comment-compose-input comment-compose-input--with-send">
                <BlockNoteEditorWrapper
                  ref="commentEditorRef"
                  v-model="commentBody"
                  :mention-members="mentionMembersForCommentEditor"
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
          </div>
        </section>

        <section class="content-section subdued linear-section activity-section">
          <div class="linear-section-head linear-section-head--static activity-section-head">
            <span class="linear-section-title">{{ t('taskEditor.activity') }}</span>
            <div class="activity-section-actions">
              <button type="button" class="linear-unsubscribe">{{ t('taskEditor.unsubscribe') }}</button>
              <button
                type="button"
                class="activity-subscribers-button"
                :aria-label="t('taskEditor.unsubscribe')"
              >
                <UsersRound :size="15" aria-hidden="true" />
              </button>
            </div>
          </div>
          <div class="linear-section-body">
            <div v-if="activitiesLoading" class="activity-empty">{{ t('taskEditor.loadingActivity') }}</div>
            <div v-else class="activity-list-wrap">
              <template v-if="activityDisplayItems.length">
                <div
                  v-for="item in activityDisplayItems"
                  :key="activityDisplayRowKey(item)"
                  class="activity-item"
                >
                  <span
                    v-if="activityDisplayIcon(item)"
                    class="activity-event-icon"
                    aria-hidden="true"
                  >
                    <component :is="activityDisplayIcon(item)" :size="16" />
                  </span>
                  <span
                    v-else
                    class="activity-avatar"
                    :style="getAvatarColorByUsername(activityDisplayActor(item))"
                    aria-hidden="true"
                  >
                    {{ getActivityAvatarLabel(activityDisplayActor(item)) }}
                  </span>
                  <div class="activity-text">
                    <span>{{ formatTaskActivityDisplayItem(item) }}</span>
                    <span
                      v-for="label in activityDisplayLabels(item)"
                      :key="label"
                      class="activity-label-chip"
                      :class="`activity-label-tone-${activityLabelTone(label)}`"
                    >
                      <span class="activity-label-dot" aria-hidden="true" />
                      {{ label }}
                    </span>
                    <span class="activity-time">· {{ relativeTimeFromNow(activityDisplayRowTime(item)) }}</span>
                  </div>
                </div>
              </template>
              <div v-else-if="creatorName && createdAgoText" class="activity-item">
                <span
                  class="activity-avatar"
                  :style="getAvatarColorByUsername(creatorName)"
                  aria-hidden="true"
                >
                  {{ getActivityAvatarLabel(creatorName) }}
                </span>
                <div class="activity-text">
                  <span>{{ creatorName }} {{ t('taskEditor.createdIssueSuffix') }}</span>
                  <span class="activity-time">· {{ createdAgoText }}</span>
                </div>
              </div>
              <div v-else class="activity-empty">{{ t('taskEditor.noActivityYet') }}</div>
            </div>
          </div>
        </section>
        </div>
      </div>

      <div class="editor-props">
        <div class="props-card">
          <section class="prop-group prop-group--properties">
            <h3 class="prop-group-title">{{ t('taskEditor.properties') }}</h3>
            <div class="prop-row prop-row--inline">
              <CustomSelect
                id="task-status"
                v-model="formStatus"
                :options="statusOptions"
                :search-placeholder="t('boardView.filterByStatus')"
                search-shortcut-badge="S"
                :aria-label="t('common.status')"
                trigger-class="prop-trigger prop-trigger--linear"
              />
            </div>
            <div class="prop-row prop-row--inline">
              <CustomSelect
                id="task-priority"
                v-model="formPriority"
                :options="priorityOptions"
                :aria-label="t('common.priority')"
                trigger-class="prop-trigger prop-trigger--linear"
              />
            </div>
            <div class="prop-row prop-row--inline">
              <div class="prop-assignee-stack">
                <AssigneeSelect
                  id="task-assignee"
                  v-model="formAssigneeId"
                  :users="userList"
                  :placeholder="t('common.unassigned')"
                  :aria-label="t('common.assignee')"
                  :external-label="importedAssigneeOnlyLabel"
                  trigger-class="prop-trigger prop-trigger--linear"
                />
                <p v-if="importedAssigneeOnlyLabel" class="external-assignee-hint">
                  {{ t('taskEditor.importedAssigneeLine', { name: importedAssigneeOnlyLabel }) }}
                </p>
              </div>
            </div>
          </section>

          <section class="prop-group">
            <h3 class="prop-group-title">{{ t('taskEditor.time') }}</h3>
            <div class="prop-row prop-row--date">
              <span class="prop-row-name">{{ t('common.plannedStartDate') }}</span>
              <CustomDatePicker
                id="task-planned-start"
                v-model="formPlannedStartDate"
                :placeholder="t('common.plannedStartDate')"
                :aria-label="t('common.plannedStartDate')"
                trigger-class="prop-trigger prop-trigger--linear"
              />
            </div>
            <div class="prop-row prop-row--date">
              <span class="prop-row-name">{{ t('common.dueDate') }}</span>
              <div class="prop-row-stack">
                <CustomDatePicker
                  id="task-due"
                  v-model="formDueDate"
                  :placeholder="t('common.dueDate')"
                  :aria-label="t('common.dueDate')"
                  :trigger-class="`prop-trigger prop-trigger--linear ${taskDueDateToneClass}`"
                />
              </div>
            </div>
            <div v-if="mode === 'edit'" class="prop-row prop-row--progress">
              <span class="prop-row-name">{{ t('taskEditor.progress') }}</span>
              <div class="prop-progress-control">
                <div class="prop-progress-visual" aria-hidden="true">
                  <span
                    class="prop-progress-fill"
                    :style="{ width: `${clampTaskProgress(formProgressPercent)}%` }"
                  />
                  <span
                    class="prop-progress-thumb"
                    :style="{ left: `${clampTaskProgress(formProgressPercent)}%` }"
                  />
                </div>
                <input
                  id="task-progress"
                  v-model.number="formProgressPercent"
                  class="prop-progress-range"
                  type="range"
                  min="0"
                  max="100"
                  step="1"
                  :aria-label="t('taskEditor.progressAria')"
                />
                <span class="prop-progress-value">{{ clampTaskProgress(formProgressPercent) }}%</span>
              </div>
            </div>
            <p v-if="progressStatusHint" class="prop-row-help prop-row-help--progress" role="status">
              {{ progressStatusHint }}
            </p>
          </section>

          <section class="prop-group">
            <h3 class="prop-group-title">{{ t('common.labels') }}</h3>
            <div v-if="showPropRowLabels" class="prop-row prop-row-labels">
              <TaskLabelCombobox
                ref="taskLabelComboboxRef"
                v-model="labelInput"
                :labels="formLabels"
                :project-id="effectiveProjectId"
                :disabled="mode !== 'edit' || !task"
                :task-id="task?.id ?? null"
                :placeholder="t('taskEditor.addLabel')"
                :ariaLabel="t('taskEditor.addLabel')"
                :remove-label-aria-label="t('taskEditor.removeLabel')"
                :delete-definition-aria-label="t('taskEditor.deleteProjectLabelDefinition')"
                :no-matches-text="t('boardView.noLabelsMatch')"
                @pick="pickSuggestion"
                @create="commitLabelInput"
                @remove="removeFormLabel"
                @open-change="onLabelPickerOpenChange"
                @delete-label-definition="onDeleteLabelDefinition"
              />
            </div>
          </section>

          <section class="prop-group">
            <h3 class="prop-group-title">{{ t('common.project') }}</h3>
            <div class="prop-row prop-row--static">
              <div class="prop-static-value" :aria-label="t('common.project')">
                <Folder class="icon-14" aria-hidden="true" />
                <span>{{ taskProjectName ?? t('taskEditor.noProject') }}</span>
              </div>
            </div>
            <div v-if="mode === 'edit' && task?.completedAt" class="prop-row prop-row--date prop-row--readonly">
              <span class="prop-row-name">{{ t('taskEditor.completedAt') }}</span>
              <span class="read-only-value">{{ new Date(task.completedAt).toLocaleString() }}</span>
            </div>
          </section>
        </div>
      </div>
    </div>
  </aside>
</template>

<style scoped>
/* P6-5: Issue workspace — 工作上下文感，弱化表单 */
.editor-panel {
  width: min(1040px, calc(100vw - 320px));
  min-width: 840px;
  max-width: 1120px;
  max-height: calc(100vh - 52px);
  height: 100%;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-lg);
  display: flex;
  flex-direction: column;
  box-shadow: var(--shadow-popover);
  overflow: hidden;
}
.editor-panel--inline {
  width: 100%;
  min-width: 0;
  max-width: none;
  max-height: none;
  height: auto;
  min-height: 100%;
  border-radius: 0;
  box-shadow: none;
  border: none;
  border-left: 1px solid var(--color-border-subtle);
  /* 内联布局：纵向滚动由工作区外层承担，避免中间内容列出现独立滚动条 */
  overflow: visible;
}
.editor-panel--inline .editor-body {
  flex: 0 0 auto;
}
.editor-panel--inline .editor-content-scroll {
  flex: 0 0 auto;
  overflow: visible;
}
.editor-panel--inline .editor-props {
  overflow: visible;
}
.editor-header {
  min-height: var(--header-height);
  border-bottom: 1px solid var(--color-border-subtle);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 14px;
  flex-shrink: 0;
  background: var(--color-bg-base);
}
.editor-header-meta {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}
.editor-breadcrumb {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.editor-breadcrumb-link {
  color: inherit;
  background: transparent;
  border: none;
  padding: 0;
  cursor: pointer;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.editor-breadcrumb-link:hover {
  color: var(--color-text-primary);
}
.editor-breadcrumb-separator {
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.editor-breadcrumb-current {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
  flex-shrink: 0;
}
.header-icon-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.header-icon-btn:hover {
  color: var(--color-text-secondary);
  background: var(--color-bg-hover);
}
.header-icon-btn--active {
  color: #d4a106;
}
.header-icon-btn--active:hover {
  color: #b58900;
}
.icon-14 {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}
.icon-16 {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}
.editor-header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.save-indicator-slot {
  display: inline-flex;
  align-items: center;
  justify-content: flex-end;
  width: 96px;
  min-height: 24px;
  flex: 0 0 96px;
}
.save-indicator {
  font-size: var(--font-size-xs);
  white-space: nowrap;
}
.save-indicator--saved {
  color: var(--color-text-muted);
}
.save-indicator--saving {
  color: var(--color-text-secondary);
}
.save-indicator--failed {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--color-danger);
}
.save-indicator-retry {
  min-height: 24px;
  padding: 0;
  border: none;
  border-bottom: 1px solid currentColor;
  border-radius: 0;
  background: transparent;
  color: inherit;
  font: inherit;
  cursor: pointer;
}
.issue-id {
  font-size: var(--font-size-xs);
  font-family: ui-monospace, monospace;
  color: var(--color-text-muted);
  padding: 2px 5px;
  border-radius: var(--radius-xs);
  background: var(--color-bg-muted);
}
.issue-position {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}
.issue-source {
  font-size: var(--font-size-xs);
  color: var(--color-text-secondary);
  padding: 2px 6px;
  border-radius: var(--radius-xs);
  background: var(--color-bg-muted);
}
.nav-btn {
  width: 24px;
  height: 24px;
  padding: 0;
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  transition: background var(--transition-fast), color var(--transition-fast);
}
.nav-btn:hover:not(:disabled) {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.nav-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.editor-header h2 {
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
  margin: 0;
  color: var(--color-text-primary);
}
.close-btn {
  font-size: 18px;
  line-height: 1;
  color: var(--color-text-muted);
  background: none;
  border: none;
  cursor: pointer;
  padding: 4px 6px;
  border-radius: var(--radius-sm);
  transition: color var(--transition-fast), background var(--transition-fast);
}
.close-btn:hover {
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}

.editor-body {
  flex: 1;
  display: flex;
  gap: 0;
  min-height: 0;
  background: var(--color-bg-base);
}
.editor-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  padding: 16px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 0;
  overflow: visible;
  /* 描述区 BlockNote（chrome）继承，用于标题覆写：calc(var(--task-editor-issue-title-size) * 比例) */
  --task-editor-issue-title-size: 2rem;
}
.editor-content-scroll {
  flex: 1;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
  overflow-y: auto;
}
.content-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.meta-chip {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  background: var(--color-bg-muted);
  border-radius: var(--radius-xs);
  padding: 2px 6px;
}
.content-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
/* 标题与描述保留间距；编辑/详情用更紧凑间距 */
.content-section--title {
  margin-bottom: 0;
  padding-bottom: 2px;
  padding-inline-start: 36px;
  flex-shrink: 0;
}

.content-section--title .title-input {
  font-size: var(--task-editor-issue-title-size);
  font-weight: 700;
  line-height: 1.18;
  letter-spacing: -0.035em;
}
.content-section.description-section {
  position: relative;
  margin-top: 16px;
  padding-top: 0;
  min-height: 0;
  flex-shrink: 0;
  /* 侧栏已隐藏，描述内容与编辑器边框保持一致的水平内边距 */
  padding-inline-start: 0;
  overflow: visible;
}
.description-section__toolbar {
  position: absolute;
  top: 6px;
  right: 8px;
  z-index: 2;
  display: flex;
  align-items: center;
  justify-content: flex-end;
}
.description-section__surface {
  border: 1px solid transparent;
  border-radius: var(--radius-md);
  background: transparent;
  padding: 10px 12px;
  transition:
    border-color var(--transition-fast),
    background-color var(--transition-fast),
    box-shadow var(--transition-fast);
}
.description-section__surface:hover {
  background: color-mix(in srgb, var(--color-bg-subtle) 48%, transparent);
}
.description-section__surface:focus-within {
  border-color: var(--color-border-subtle);
  box-shadow: none;
  background: var(--color-bg-base);
}
.description-section__surface :deep(.blocknote-editor-wrap) {
  background: transparent;
  border-radius: var(--radius-sm);
  width: 100%;
  max-width: none;
}
.description-section--fullscreen,
.description-section:fullscreen {
  position: fixed;
  inset: 0;
  z-index: 1300;
  margin: 0;
  padding: 18px;
  background: var(--color-bg-base);
  overflow: hidden;
}
.description-section--fullscreen .description-section__toolbar,
.description-section:fullscreen .description-section__toolbar {
  top: 26px;
  right: 30px;
}
.description-section--fullscreen .description-section__surface,
.description-section:fullscreen .description-section__surface {
  height: 100%;
  padding: 42px 52px 24px;
  background: var(--color-bg-base);
  border-radius: var(--radius-lg);
  overflow-y: auto;
}
.description-section--fullscreen .description-section__surface :deep(.blocknote-editor-wrap),
.description-section:fullscreen .description-section__surface :deep(.blocknote-editor-wrap) {
  min-height: 100%;
}
/* 新建任务时标题与描述间距略大，更易区分 */
.editor-panel--create .content-section--title {
  margin-bottom: 16px;
  padding-bottom: 0;
}
.editor-panel--create .content-section.description-section {
  margin-top: 8px;
}
.content-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 6px;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  border-radius: var(--radius-sm);
  cursor: pointer;
  transition: color var(--transition-fast), background var(--transition-fast);
}
.content-action-btn:hover {
  color: var(--color-text-secondary);
  background: var(--color-bg-hover);
}
.linear-section {
  padding-top: 18px;
  border-top: none;
  flex-shrink: 0;
}
.linear-section + .linear-section {
  margin-top: 4px;
}
.linear-section-head-wrap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.sub-issue-toolbar {
  display: flex;
  align-items: center;
  min-height: 32px;
  gap: 8px;
  margin-bottom: 4px;
}
.sub-issue-toolbar .linear-section-head {
  min-width: 0;
}
.sub-issue-toolbar-actions {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
}
.sub-issue-icon-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  min-height: 28px;
  padding: 0;
  border: 1px solid transparent;
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  list-style: none;
  transition: color var(--transition-fast), background var(--transition-fast), border-color var(--transition-fast);
}
.sub-issue-icon-action:hover,
.sub-issue-icon-action:focus-visible {
  border-color: var(--color-border);
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.sub-issue-icon-action:focus-visible {
  outline: 2px solid var(--color-accent-muted-border);
  outline-offset: 1px;
}
.linear-section-action {
  margin-left: auto;
  padding: 4px;
}
.linear-section-head {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: none;
  background: transparent;
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  cursor: pointer;
  text-align: left;
}
.linear-section-chevron {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}
.linear-section-head--static {
  cursor: default;
}
.linear-section-title {
  flex-shrink: 0;
}
.linear-section-count {
  font-weight: var(--font-weight-normal);
  color: var(--color-text-muted);
  margin-left: 6px;
}
.linear-section-display-opt {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  cursor: pointer;
}
.linear-section-body {
  padding-left: 0;
}
.sub-issue-body {
  min-width: 0;
}
.linear-placeholder {
  margin: 0;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
}
.linear-sub-list {
  list-style: none;
  margin: 0 0 8px;
  padding: 0;
}
.linear-sub-item {
  margin: 2px 0;
}
.linear-sub-row {
  display: flex;
  align-items: center;
  gap: 6px;
}
.sub-issue-list {
  margin-bottom: 0;
}
.sub-issue-list .linear-sub-row {
  min-height: 36px;
  margin: 0;
  padding: 2px 6px;
  border-radius: var(--radius-sm);
  transition: background var(--transition-fast);
}
.sub-issue-list .linear-sub-row:hover {
  background: var(--color-bg-hover);
}
.sub-issue-list .linear-sub-link {
  min-height: 30px;
  padding: 3px 2px;
  color: var(--color-text-secondary);
}
.sub-issue-list .linear-sub-link:hover {
  color: var(--color-text-primary);
}
.sub-issue-title {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.sub-issue-assignee {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  margin-left: auto;
  border-radius: var(--radius-full);
  background: var(--color-accent-muted);
  color: var(--color-accent);
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
}
.linear-sub-link {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 0;
  border: none;
  background: transparent;
  font-size: var(--font-size-caption);
  color: var(--color-text-primary);
  cursor: pointer;
  text-align: left;
  width: 100%;
  min-width: 0;
}
.linear-sub-link:hover {
  color: var(--color-accent);
}
.linear-sub-link--btn {
  border: none;
  font: inherit;
  cursor: pointer;
  width: auto;
}
.linear-sub-link .icon-done {
  color: var(--color-status-done);
}
.linear-sub-link .icon-circle {
  color: var(--color-text-muted);
}
.linear-sub-xy {
  margin-left: 6px;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}
.linear-attachment-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.linear-attachment-row .linear-sub-link {
  flex: 1;
  min-width: 0;
  width: auto;
}
.linear-attachment-row .linear-sub-meta {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  flex-shrink: 0;
}
.linear-attachment-row .linear-attachment-delete {
  flex-shrink: 0;
  padding: 2px 6px;
}
.linear-attachment-row--uploading {
  align-items: center;
}
.linear-attachment-upload-spin {
  flex-shrink: 0;
  animation: linear-attachment-upload-spin 0.9s linear infinite;
  color: var(--color-text-muted);
}
@keyframes linear-attachment-upload-spin {
  from {
    transform: rotate(0deg);
  }
  to {
    transform: rotate(360deg);
  }
}
.linear-attachment-pending-body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.linear-attachment-pending-name {
  font-size: var(--font-size-sm);
  color: var(--color-text-secondary);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.linear-attachment-progress-track {
  height: 3px;
  border-radius: 2px;
  background: var(--color-border);
  overflow: hidden;
}
.linear-attachment-progress-fill {
  height: 100%;
  border-radius: 2px;
  background: var(--color-text-muted);
  transition: width 0.15s ease-out;
}
.linear-placeholder--error {
  color: var(--color-text-error, #c00);
}
.linear-create-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin-top: 8px;
  padding: 7px 4px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}
.linear-create-btn:hover {
  border-color: transparent;
  color: var(--color-text-primary);
  background: var(--color-bg-hover);
}
.linear-create-btn-icon {
  font-size: 1.1em;
  font-weight: 600;
  line-height: 1;
}
.linear-inline-form {
  margin: 8px 0 4px;
  padding: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-bg-base);
  box-shadow: var(--shadow-subtle);
}
.linear-inline-heading {
  display: flex;
  align-items: center;
  gap: 4px;
}
.linear-inline-title {
  flex: 1;
  min-width: 0;
  padding: 6px 4px;
  border: none;
  background: transparent;
  color: var(--color-text-primary);
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-medium);
}
.linear-inline-title:focus {
  outline: none;
}
.linear-inline-title::placeholder,
.linear-inline-description::placeholder {
  color: var(--color-text-muted);
  font-weight: var(--font-weight-normal);
}
.linear-inline-description {
  display: block;
  width: 100%;
  min-height: 42px;
  max-height: 128px;
  padding: 4px 8px 8px 36px;
  border: none;
  outline: none;
  resize: vertical;
  background: transparent;
  color: var(--color-text-secondary);
  font: inherit;
  font-size: var(--font-size-caption);
  line-height: 1.45;
}
.linear-inline-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--color-border-subtle);
}
.linear-inline-props {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  min-width: 0;
}
.linear-inline-project {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  max-width: 160px;
  min-height: 28px;
  padding: 2px 8px;
  overflow: hidden;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-full);
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
  white-space: nowrap;
}
.linear-inline-project span {
  overflow: hidden;
  text-overflow: ellipsis;
}
.linear-inline-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
}
.linear-inline-discard {
  min-height: 28px;
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: var(--radius-sm);
  background: transparent;
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  cursor: pointer;
}
.linear-inline-discard:hover,
.linear-inline-discard:focus-visible {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.linear-inline-create {
  min-height: 28px;
  padding: 3px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
}
.linear-inline-create:hover:not(:disabled),
.linear-inline-create:focus-visible:not(:disabled) {
  background: var(--color-bg-hover);
}
.linear-inline-create:disabled {
  border-color: var(--color-border-subtle);
  background: var(--color-bg-muted);
  color: var(--color-text-muted);
  cursor: not-allowed;
}
.linear-inline-form :deep(.linear-inline-trigger) {
  min-height: 28px;
  min-width: 0;
  padding: 2px 7px;
  gap: 5px;
  border-color: var(--color-border-subtle);
  border-radius: var(--radius-full);
  background: var(--color-bg-base);
  font-size: var(--font-size-xs);
}
.linear-inline-status-select :deep(.linear-inline-status-trigger) {
  width: 28px;
  min-width: 28px;
  min-height: 28px;
  padding: 0;
  justify-content: center;
  gap: 0;
  border-color: transparent;
  border-radius: var(--radius-full);
  background: transparent;
}
.linear-inline-status-select :deep(.linear-inline-status-trigger:hover) {
  background: var(--color-bg-hover);
}
.linear-inline-status-select :deep(.linear-inline-status-trigger .trigger-label),
.linear-inline-status-select :deep(.linear-inline-status-trigger .trigger-chevron) {
  display: none;
}
.linear-inline-status-select :deep(.custom-select-list) {
  min-width: 220px;
}
.linear-inline-date-select :deep(.linear-inline-date-trigger) {
  min-width: 0;
  max-width: 154px;
}
@media (max-width: 760px) {
  .linear-inline-footer {
    align-items: flex-start;
    flex-direction: column;
  }
  .linear-inline-actions {
    align-self: flex-end;
  }
}
.activity-list-wrap {
  --activity-track-x: 8px;
  --activity-marker-offset: 5px;
  --activity-marker-radius: 8px;
  --activity-marker-center: calc(var(--activity-marker-offset) + var(--activity-marker-radius));
  --activity-connector-gap: 2px;
  --activity-connector-overhang: 3px;
  display: flex;
  flex-direction: column;
  gap: 0;
}
.activity-item {
  display: flex;
  position: relative;
  align-items: flex-start;
  gap: 8px;
  min-height: 28px;
  padding: 4px 0;
}
.activity-item:not(:last-child)::before {
  position: absolute;
  z-index: 0;
  top: calc(
    var(--activity-marker-center) + var(--activity-marker-radius) + var(--activity-connector-gap)
  );
  bottom: calc(-1 * var(--activity-connector-overhang));
  left: calc(var(--activity-track-x) - 0.5px);
  width: 1px;
  content: '';
  background: var(--color-border);
}
.activity-event-icon,
.activity-avatar {
  position: relative;
  z-index: 1;
  flex: 0 0 16px;
  width: 16px;
  height: 16px;
  margin-top: 1px;
}
.activity-event-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-secondary);
}
.activity-avatar {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--radius-full);
  font-size: 8px;
  font-weight: var(--font-weight-semibold);
  line-height: 1;
}
.activity-text {
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  line-height: 18px;
  flex: 1;
  min-width: 0;
}
.activity-time {
  color: var(--color-text-muted);
}
.activity-label-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: 5px;
  color: var(--color-text-secondary);
  white-space: nowrap;
}
.activity-label-dot {
  width: 6px;
  height: 6px;
  flex: 0 0 6px;
  border-radius: var(--radius-full);
  background: var(--activity-label-tone, var(--label-color-blue));
}
.activity-label-tone-0 { --activity-label-tone: var(--label-color-red); }
.activity-label-tone-1 { --activity-label-tone: var(--label-color-violet); }
.activity-label-tone-2 { --activity-label-tone: var(--label-color-blue); }
.activity-label-tone-3 { --activity-label-tone: var(--label-color-cyan); }
.activity-label-tone-4 { --activity-label-tone: var(--label-color-green); }
.activity-label-tone-5 { --activity-label-tone: var(--label-color-amber); }
.activity-empty {
  margin: 4px 0;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
}
.linear-unsubscribe {
  padding: 0;
  border: none;
  background: transparent;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  cursor: pointer;
  transition: color var(--transition-fast);
}
.activity-section-head {
  width: 100%;
}
.activity-section-actions {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  margin-left: auto;
}
.activity-subscribers-button {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}
.activity-subscribers-button:hover,
.activity-subscribers-button:focus-visible {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}
.activity-subscribers-button:focus-visible {
  outline: 2px solid var(--color-accent-muted-border);
  outline-offset: 1px;
}
.linear-unsubscribe:hover {
  color: var(--color-text-secondary);
}
.task-comments-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 12px;
}
.task-comments-list :deep(.mermaid) {
  margin: 8px 0;
  max-width: 100%;
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
.task-comment-row--root {
  border-bottom: 0;
}
.task-comment-row--reply {
  border-top: 1px solid var(--color-border-subtle);
}
.task-comment-row--reply + .task-comment-row--reply {
  margin-top: 0;
  padding-top: 12px;
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
  border-radius: var(--radius-full);
  font-size: 8px;
  font-weight: var(--font-weight-semibold);
  line-height: 1;
}
.task-comment-content {
  flex: 1;
  min-width: 0;
}
.task-comment-meta {
  display: flex;
  flex-direction: column;
  gap: 1px;
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
.task-comment-reply-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  margin-left: 2px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}
.task-comment-reply-btn:hover,
.task-comment-reply-btn:focus-visible {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
  opacity: 1;
}
.task-comment-delete {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 28px;
  width: 28px;
  height: 28px;
  margin-left: -4px;
  padding: 0;
  border: 0;
  border-radius: var(--radius-full);
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}
.task-comment-row:hover .task-comment-reply-btn,
.task-comment-row:hover .task-comment-delete,
.task-comment-reply-btn:focus-visible,
.task-comment-delete:focus-visible {
  opacity: 1;
}
.task-comment-delete:hover,
.task-comment-delete:focus-visible {
  background: var(--color-bg-hover);
  color: var(--color-danger, #e5484d);
}
.task-comment-body {
  margin-top: 3px;
  font-size: 13px;
  color: var(--color-text-primary);
  line-height: 1.5;
  min-height: 18px;
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
.task-comment-reply-content :deep(p) {
  display: inline;
  margin: 0;
}
.task-comment-replies {
  display: flex;
  flex-direction: column;
  gap: 0;
  margin-top: 0;
  margin-left: 0;
  padding: 0;
  border-top: 0;
  background: transparent;
}
.task-comment-toggle-replies {
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: 12px;
  cursor: pointer;
  padding: 2px 0;
  text-align: left;
}
.task-comment-toggle-replies:hover {
  color: var(--color-text-primary);
}
.comment-compose {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-subtle);
  border-radius: 8px;
  background: var(--color-bg-base);
}
.comment-compose-input {
  flex: 1;
  min-width: 0;
  display: flex;
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
.task-comment-reply-compose {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 10px 14px;
  border-top: 1px solid var(--color-border-subtle);
  border-radius: 0;
  background: var(--color-bg-base);
}
.task-comment-reply-cancel {
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  font-size: 12px;
  cursor: pointer;
  height: 32px;
  align-self: end;
}
.task-comment-reply-cancel:hover {
  color: var(--color-text-primary);
}
.comment-compose-hint {
  margin-top: 8px;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
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
.section-kicker {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  letter-spacing: 0.02em;
  text-transform: none;
  color: var(--color-text-secondary);
}
.section-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: baseline;
}
.section-note {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}
.subdued {
  padding-top: 18px;
  border-top: none;
}
.title-input {
  width: 100%;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  border: none;
  padding: 0;
  line-height: 1.18;
  letter-spacing: -0.035em;
  background: transparent;
}
.title-input::placeholder {
  color: var(--color-text-muted);
}
.editor-props {
  box-sizing: border-box;
  min-width: 400px;
  width: clamp(400px, 30vw, 500px);
  flex-shrink: 0;
  border-left: none;
  padding: 76px 28px 28px;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  background: var(--color-bg-base);
}
.props-card {
  display: flex;
  flex-direction: column;
  gap: 28px;
  width: 100%;
  max-width: 360px;
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
}
.prop-group {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.prop-group-title {
  margin: 0 0 8px;
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  letter-spacing: 0;
  color: var(--color-text-secondary);
}
.prop-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.prop-row--inline {
  min-height: 32px;
}
.prop-row--date,
.prop-row--progress,
.prop-row--readonly {
  display: grid;
  grid-template-columns: 84px minmax(0, 1fr);
  align-items: center;
  min-height: 32px;
}
.prop-row--date {
  gap: 8px;
}
.prop-assignee-stack {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
}
.prop-row-stack {
  display: flex;
  flex-direction: column;
  gap: 6px;
  width: 100%;
  min-width: 0;
}
.prop-row-help {
  margin: 2px 0 0 6px;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  line-height: 1.35;
}
.prop-row-help--progress {
  margin: -2px 0 0 0;
  color: var(--color-text-secondary);
}
.external-assignee-hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  line-height: 1.35;
}
.prop-row-name {
  flex: 0 0 auto;
  min-width: 0;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
}
.prop-static-value {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  min-height: 34px;
  padding: 5px 8px;
  border: none;
  border-radius: var(--radius-sm);
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  text-align: left;
}
.editor-props :deep(.prop-trigger),
.editor-props :deep(.prop-trigger--linear) {
  width: auto;
  min-height: 32px;
  padding: 5px 8px;
  border: 1px solid transparent;
  color: var(--color-text-secondary);
  border-radius: var(--radius-sm);
  text-align: left;
  font-size: var(--font-size-caption);
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast), color var(--transition-fast);
}
.editor-props :deep(.custom-select) {
  width: 100%;
}
.editor-props :deep(.custom-select .prop-trigger) {
  width: 100%;
}
.editor-props :deep(.assignee-select),
.editor-props :deep(.assignee-select .prop-trigger) {
  width: 100%;
}
.editor-props :deep(.custom-date-picker) {
  width: 100%;
  min-width: 0;
}
.editor-props :deep(.custom-date-picker .prop-trigger) {
  width: 100%;
  min-width: 0;
}
.editor-props :deep(.prop-trigger:hover),
.editor-props :deep(.prop-trigger:focus-visible) {
  background: var(--color-bg-hover);
  border-color: var(--color-border);
  color: var(--color-text-primary);
}
.editor-props :deep(.prop-trigger--due-today) {
  color: var(--color-status-in-progress);
  font-weight: var(--font-weight-medium);
}
.editor-props :deep(.prop-trigger--due-overdue) {
  color: var(--color-status-warning);
  font-weight: var(--font-weight-medium);
}
.editor-props :deep(.prop-trigger .trigger-chevron) {
  opacity: 0;
  transition: opacity var(--transition-fast);
}
.editor-props :deep(.prop-trigger:hover .trigger-chevron),
.editor-props :deep(.prop-trigger:focus-visible .trigger-chevron) {
  opacity: 1;
}
.prop-row--readonly .read-only-value {
  margin-left: auto;
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
}

.prop-progress-control {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  flex: 1;
  min-width: 0;
  min-height: 28px;
}
.prop-progress-visual {
  flex: 1;
  min-width: 0;
  position: relative;
  height: 6px;
  margin: 0 8px;
  border-radius: var(--radius-full);
  background: var(--color-bg-muted);
  box-shadow: inset 0 0 0 1px var(--color-border-subtle);
}
.prop-progress-fill {
  position: absolute;
  inset: 0 auto 0 0;
  max-width: 100%;
  border-radius: inherit;
  background: var(--color-accent);
  transition: width var(--transition-fast);
}
.prop-progress-thumb {
  position: absolute;
  top: 50%;
  width: 14px;
  height: 14px;
  border: 2px solid var(--color-bg-base);
  border-radius: var(--radius-full);
  background: var(--color-accent);
  box-shadow: 0 0 0 1px color-mix(in srgb, var(--color-accent) 34%, transparent);
  transform: translate(-50%, -50%);
  transition: left var(--transition-fast), transform var(--transition-fast), box-shadow var(--transition-fast);
}
.prop-progress-range {
  position: absolute;
  inset: 0 54px 0 6px;
  z-index: 2;
  width: auto;
  height: 100%;
  margin: 0;
  appearance: none;
  cursor: ew-resize;
  opacity: 0;
}
.prop-progress-range:hover + .prop-progress-value,
.prop-progress-range:focus-visible + .prop-progress-value {
  color: var(--color-text-primary);
}
.prop-progress-control:hover .prop-progress-thumb,
.prop-progress-control:focus-within .prop-progress-thumb {
  transform: translate(-50%, -50%) scale(1.12);
  box-shadow: 0 0 0 3px var(--color-accent-muted);
}
.prop-progress-range:focus-visible {
  outline: none;
}
.prop-progress-value {
  min-width: 38px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  text-align: right;
  font-variant-numeric: tabular-nums;
}

@media (max-width: 1100px) {
  .editor-panel {
    min-width: 0;
    width: min(960px, calc(100vw - 32px));
    max-height: calc(100vh - 32px);
  }

  .editor-body {
    flex-direction: column;
  }

  .editor-props {
    min-width: 0;
    width: auto;
    border-left: none;
    border-top: 1px solid var(--color-border-subtle);
    padding: 20px;
  }

  .props-card {
    display: grid;
    grid-template-columns: repeat(2, minmax(0, 1fr));
    max-width: none;
    gap: 24px 32px;
  }
}

@media (max-width: 680px) {
  .props-card {
    display: flex;
    gap: 24px;
  }
}
</style>
