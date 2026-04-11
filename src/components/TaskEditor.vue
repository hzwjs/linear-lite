<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, computed, nextTick } from 'vue'
import { useI18n } from 'vue-i18n'

const AUTO_SAVE_DEBOUNCE_MS = 600
const SAVED_INDICATOR_MS = 2000

import type { Task, Status, Priority, TaskActivity, User } from '../types/domain'
import { useAuthStore } from '../store/authStore'
import { useNotificationStore } from '../store/notificationStore'
import { useTaskStore } from '../store/taskStore'
import { useFavoriteStore } from '../store/favoriteStore'
import { useProjectStore } from '../store/projectStore'
import { useViewModeStore } from '../store/viewModeStore'
import { useIssuePanelStore } from '../store/issuePanelStore'
import { useRouter } from 'vue-router'
import { toApiError } from '../services/api/index'
import { projectApi } from '../services/api/project'
import { activityApi } from '../services/api/activity'
import { taskCommentsApi, type TaskCommentDto } from '../services/api/taskComments'
import { attachmentsApi } from '../services/api/attachments'
import type { TaskLabelWriteItem } from '../services/api/types'
import type { TaskAttachment } from '../services/api/types'
import { getActivityAvatarLabel } from '../utils/taskActivity'
import {
  type TaskActivityDisplayItem,
  formatTaskActivityDisplayItem,
  groupTaskActivitiesForDisplay
} from '../utils/taskActivityGroup'
import { renderMarkdown } from '../utils/markdown'
import { buildCommentThreads } from '../utils/commentThread'
import { formatDateInputValue, parseDateInputValue, todayDateInputValue } from '../utils/taskDate'
import { saveTaskEditDraft, clearTaskEditDraft, readTaskEditDraft } from '../utils/taskEditDraft'
import { getPriorityLabel, getStatusLabel } from '../utils/enumLabels'
import { getTaskDueState } from '../utils/taskDueState'
import { captureTaskLoadContext, isTaskLoadStale } from '../utils/taskLoadContext'
import TiptapEditor from './TiptapEditor.vue'
import CustomSelect from './ui/CustomSelect.vue'
import CustomDatePicker from './ui/CustomDatePicker.vue'
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
  User as UserIcon,
  Star,
  Paperclip,
  Folder,
  Send,
  Trash2
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
const viewModeStore = useViewModeStore()
const issuePanelStore = useIssuePanelStore()
const router = useRouter()
const { t } = useI18n()

const formTitle = ref('')
const formDescription = ref('')
const descriptionUploadState = ref({ hasPending: false, hasFailed: false })
const descriptionEditorRef = ref<InstanceType<typeof TiptapEditor> | null>(null)
const descriptionEditorReady = ref(false)

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
const labelInput = ref('')
const taskLabelComboboxRef = ref<{
  removeFromSuggestions: (labelId: number) => void
} | null>(null)
const editorPanelRef = ref<HTMLElement | null>(null)

const userList = ref<User[]>([])
const saveStatus = ref<'idle' | 'saving' | 'saved'>('idle')
const activities = ref<TaskActivity[]>([])
const activitiesLoading = ref(false)
const activityDisplayItems = computed(() => groupTaskActivitiesForDisplay(activities.value))

function activityDisplayRowKey(item: TaskActivityDisplayItem): string {
  if (item.kind === 'single') return `activity-${item.activity.id}`
  const first = item.activities[0]
  return `activity-group-${first?.id ?? 'unknown'}-${item.activities.length}`
}

function activityDisplayRowTime(item: TaskActivityDisplayItem): number {
  if (item.kind === 'single') return item.activity.createdAt
  return item.activities[0]?.createdAt ?? 0
}

const comments = ref<TaskCommentDto[]>([])
const commentsLoading = ref(false)
const commentBody = ref('')
const commentSubmitting = ref(false)
const commentMentionIds = ref<Set<number>>(new Set())
const inlineReplyRootId = ref<number | null>(null)
const replyBodyByRootId = ref<Record<number, string>>({})
const replySubmittingRootIds = ref<Set<number>>(new Set())
const expandedReplyRootIds = ref<Set<number>>(new Set())
const attachmentInputRef = ref<HTMLInputElement | null>(null)
const attachments = ref<TaskAttachment[]>([])
const attachmentsLoading = ref(false)
const attachmentUploadError = ref('')
const attachmentsCollapsed = ref(false)
const dueStateNow = ref(Date.now())
let dueStateNowTimer: ReturnType<typeof setInterval> | null = null
const MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024 // 10MB
/** 刚由本端保存的任务 id，避免 save 后 loadForm 用接口返回值覆盖编辑器内容 */
const justSavedTaskId = ref<string | null>(null)
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null

const statusOptions = computed<CustomSelectOption[]>(() => [
  { value: 'backlog', label: getStatusLabel('backlog'), icon: CircleDashed, shortcut: '1' },
  { value: 'todo', label: getStatusLabel('todo'), icon: Circle, shortcut: '2' },
  { value: 'in_progress', label: getStatusLabel('in_progress'), icon: Loader2, shortcut: '3' },
  { value: 'in_review', label: getStatusLabel('in_review'), icon: Eye, shortcut: '4' },
  { value: 'done', label: getStatusLabel('done'), icon: CheckCircle, shortcut: '5' },
  { value: 'canceled', label: getStatusLabel('canceled'), icon: CircleX, shortcut: '6' },
  { value: 'duplicate', label: getStatusLabel('duplicate'), icon: Copy, shortcut: '7' }
])
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
  mentionCandidates.value.map((u) => ({
    id: u.id,
    label: (u.username ?? '').trim() || `user-${u.id}`,
  }))
)

const commentEditorRef = ref<InstanceType<typeof TiptapEditor> | null>(null)
const commentThreads = computed(() => buildCommentThreads(comments.value))

const assigneeOptions = computed<CustomSelectOption[]>(() => {
  const list: CustomSelectOption[] = [{ value: '', label: t('common.unassigned'), icon: UserIcon }]
  for (const u of userList.value) {
    const id = u?.id
    if (typeof id !== 'number' || !Number.isFinite(id)) continue
    list.push({ value: id, label: u.username ?? '', icon: UserIcon })
  }
  return list
})

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
const taskDueStateText = computed(() => {
  const state = taskDueState.value
  if (!state.hasDueDate) return ''
  if (state.kind === 'today') return t('taskEditor.dueToday')
  if (state.kind === 'overdue') return t('taskEditor.dueOverdueDays', { count: state.dayCount })
  return t('taskEditor.dueInDays', { count: state.dayCount })
})
const showAttachmentBody = computed(
  () => attachmentsLoading.value || attachmentUploadError.value.length > 0 || attachments.value.length > 0
)
const isFavorited = computed(() => {
  if (!props.task?.id) return false
  return favoriteStore.isFavorite(props.task.id) || props.task.favorited === true
})

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
const subIssueFormDueDate = ref('')
const subIssueSaving = ref(false)

const subIssueCountDisplay = computed(() => {
  const rows = subIssueRows.value
  const total = rows.length
  const done = rows.filter((r) => r.task.status === 'done').length
  return { done, total }
})

async function loadSubIssues() {
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
  subIssuesLoading.value = true
  try {
    const direct = await store.fetchSubIssues(rootNumericId)
    if (isTaskLoadStale(ctx, props.task)) return
    const nested = viewModeStore.viewConfig.nestedSubIssues
    if (!nested) {
      subIssueRows.value = direct.map((t) => ({ task: t, depth: 0 }))
      return
    }
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

async function loadActivities(options?: { silent?: boolean }) {
  if (props.mode !== 'edit' || !props.task?.id) {
    activities.value = []
    return
  }
  const ctx = captureTaskLoadContext(props.task)
  if (ctx == null) {
    activities.value = []
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

async function loadComments(options?: { silent?: boolean }) {
  if (props.mode !== 'edit' || !props.task?.id) {
    comments.value = []
    return
  }
  const ctx = captureTaskLoadContext(props.task)
  if (ctx == null) {
    comments.value = []
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

function toggleMentionUser(userId: number) {
  const next = new Set(commentMentionIds.value)
  if (next.has(userId)) next.delete(userId)
  else next.add(userId)
  commentMentionIds.value = next
}

function onCommentEditorKeydown(e: KeyboardEvent) {
  if (e.isComposing) return
  if (!(e.metaKey || e.ctrlKey) || e.key !== 'Enter') return
  e.preventDefault()
  void submitComment()
}

function openInlineReply(rootId: number) {
  inlineReplyRootId.value = rootId
  if (replyBodyByRootId.value[rootId] != null) return
  replyBodyByRootId.value = { ...replyBodyByRootId.value, [rootId]: '' }
}

function closeInlineReply(rootId: number) {
  if (inlineReplyRootId.value !== rootId) return
  inlineReplyRootId.value = null
}

function updateInlineReplyBody(rootId: number, value: string) {
  replyBodyByRootId.value = { ...replyBodyByRootId.value, [rootId]: value }
}

function toggleRepliesExpanded(rootId: number) {
  const next = new Set(expandedReplyRootIds.value)
  if (next.has(rootId)) next.delete(rootId)
  else next.add(rootId)
  expandedReplyRootIds.value = next
}

function isRepliesExpanded(rootId: number): boolean {
  return expandedReplyRootIds.value.has(rootId)
}

function visibleRepliesForThread(thread: ReturnType<typeof buildCommentThreads>[number]) {
  return isRepliesExpanded(thread.root.id) ? thread.replies : thread.visibleReplies
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
    const fromChips = [...commentMentionIds.value]
    const ids = [...new Set([...fromDoc, ...fromChips])]
    await taskCommentsApi.create(props.task.id, {
      body,
      mentionedUserIds: ids,
      parentId: null
    })
    commentBody.value = ''
    commentMentionIds.value = new Set()
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
    await taskCommentsApi.create(props.task.id, {
      body,
      mentionedUserIds: [],
      parentId: rootId
    })
    replyBodyByRootId.value = { ...replyBodyByRootId.value, [rootId]: '' }
    inlineReplyRootId.value = null
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
  try {
    await taskCommentsApi.delete(props.task.id, c.id)
    await loadComments({ silent: true })
  } catch (e) {
    console.error(e)
    alert(toApiError(e).message || t('taskEditor.commentSendFailed'))
  }
}

function openSubIssueForm() {
  showSubIssueForm.value = true
  subIssueFormTitle.value = ''
  subIssueFormDescription.value = ''
  subIssueFormStatus.value = props.task?.status ?? 'backlog'
  subIssueFormPriority.value = props.task?.priority ?? 'medium'
  subIssueFormAssigneeId.value = ''
  subIssueFormDueDate.value = ''
}

function closeSubIssueForm() {
  showSubIssueForm.value = false
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

async function loadAttachments() {
  if (props.mode !== 'edit' || !props.task?.id) {
    attachments.value = []
    return
  }
  const ctx = captureTaskLoadContext(props.task)
  if (ctx == null) {
    attachments.value = []
    return
  }
  attachmentsLoading.value = true
  attachmentUploadError.value = ''
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
    if (!isTaskLoadStale(ctx, props.task)) {
      attachmentsLoading.value = false
    }
  }
}

function openAttachmentInput() {
  if (props.mode === 'edit' && props.task?.id) attachmentInputRef.value?.click()
}

function onAttachmentInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  if (!files?.length || !props.task?.id) return
  attachmentUploadError.value = ''
  const taskKey = props.task.id
  ;(async () => {
    for (let i = 0; i < files.length; i++) {
      const file = files[i]
      if (!file) continue
      if (file.size > MAX_ATTACHMENT_SIZE) {
        attachmentUploadError.value = `"${file.name}" ${t('attachments.fileTooLargeSkipped', { size: '10MB' })}`
        continue
      }
      try {
        await attachmentsApi.upload(taskKey, file)
        await loadAttachments()
      } catch (e) {
        attachmentUploadError.value = e instanceof Error ? e.message : t('attachments.uploadFailed')
      }
    }
    input.value = ''
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
    loadSubIssues()
    loadActivities()
    loadComments()
    loadAttachments()
  },
  { immediate: true }
)
watch(
  () => viewModeStore.viewConfig.nestedSubIssues,
  () => loadSubIssues()
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

onMounted(async () => {
  await loadProjectMembers(effectiveProjectId.value)
})

onMounted(() => {
  dueStateNowTimer = setInterval(() => {
    dueStateNow.value = Date.now()
  }, 60_000)
})

watch(effectiveProjectId, (id) => {
  loadProjectMembers(id)
})

onBeforeUnmount(() => {
  if (dueStateNowTimer != null) {
    clearInterval(dueStateNowTimer)
    dueStateNowTimer = null
  }
})

function toDateInputValue(ms: number | undefined | null): string {
  return formatDateInputValue(ms)
}

function formLabelStableKey(rows: { id?: number; name: string }[]): string {
  return [...rows]
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
  return [...(labels ?? [])]
    .map((l) => `i:${l.id}`)
    .sort()
    .join('|')
}

function toLabelWriteItems(rows: { id?: number; name: string }[]): TaskLabelWriteItem[] {
  const out: TaskLabelWriteItem[] = []
  for (const r of rows) {
    const n = r.name.trim()
    if (!n) continue
    if (r.id != null) out.push({ id: r.id })
    else out.push({ name: n })
  }
  return out
}

function labelNameTaken(name: string): boolean {
  const n = name.trim().toLowerCase()
  if (!n) return true
  return formLabels.value.some((c) => c.name.trim().toLowerCase() === n)
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
    return
  }
  if (p === 100 && OPEN_STATUSES_FOR_PROGRESS.includes(st)) {
    formStatus.value = 'done'
  }
}

function descriptionForSave(desc: string | undefined): string {
  const s = (desc ?? '').trim()
  if (!s) return ''
  const emptyListLine = /^\s*[-*+]\s*$|^\s*\d+\.\s*$/
  const onlyEmptyLists = s.split(/\n/).every((line) => !line.trim() || emptyListLine.test(line.trim()))
  return onlyEmptyLists ? '' : s
}

const loadForm = () => {
  /** currentTask 短暂为 null（列表刷新、项目切换等）时不要走下方 else 整表重置 */
  if (props.mode === 'edit' && !props.task) {
    return
  }
  if (props.mode === 'edit' && props.task) {
    formTitle.value = props.task.title
    formDescription.value = props.task.description || ''
    formStatus.value = props.task.status
    formPriority.value = props.task.priority
    formAssigneeId.value = props.task.assigneeId ?? ''
    formPlannedStartDate.value = toDateInputValue(props.task.plannedStartDate ?? undefined)
    formDueDate.value = toDateInputValue(props.task.dueDate ?? undefined)
    formProgressPercent.value = clampTaskProgress(props.task.progressPercent ?? 0)
    formLabels.value = (props.task.labels ?? []).map((l) => ({ id: l.id, name: l.name }))
    labelInput.value = ''

    const draft = readTaskEditDraft(props.task.id)
    if (draft && draft.savedAt > props.task.updatedAt) {
      formTitle.value = draft.title
      formDescription.value = draft.description
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
  () => {
    if (justSavedTaskId.value !== null && props.task?.id === justSavedTaskId.value) {
      justSavedTaskId.value = null
      return
    }
    loadForm()
  },
  { immediate: true }
)
watch(() => props.mode, loadForm)
watch(() => props.defaultStatus, () => {
  if (props.mode === 'create') formStatus.value = props.defaultStatus ?? 'todo'
})

watch(formProgressPercent, () => syncFormStatusFromProgress())

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

async function performAutoSave() {
  if (props.mode !== 'edit' || !props.task) return
  const payload = getPayload()
  if (!payload.title) return
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
  if (
    isPayloadEqual(payload, current) &&
    formLabelStableKey(formLabels.value) === taskLabelsStableKey(props.task.labels)
  ) {
    return
  }
  if (descriptionUploadState.value.hasPending || descriptionUploadState.value.hasFailed) return

  const clearPlannedStart =
    !formPlannedStartDate.value && props.task.plannedStartDate != null ? true : undefined
  const clearDueDate =
    !formDueDate.value && props.task.dueDate != null ? true : undefined

  persistFormDraftIfNeeded()

  saveStatus.value = 'saving'
  justSavedTaskId.value = props.task.id
  try {
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
      labels: toLabelWriteItems(formLabels.value)
    })
    // 保存后故意跳过 loadForm，避免覆盖正文；服务端进度↔状态联动需从合并结果写回
    formStatus.value = merged.status
    formProgressPercent.value = clampTaskProgress(merged.progressPercent ?? 0)
    formLabels.value = (merged.labels ?? []).map((l) => ({ id: l.id, name: l.name }))
    clearTaskEditDraft(props.task.id)
    await loadActivities({ silent: true })
    saveStatus.value = 'saved'
    setTimeout(() => {
      saveStatus.value = 'idle'
    }, SAVED_INDICATOR_MS)
  } catch (error) {
    console.error('Auto-save failed:', error)
    saveStatus.value = 'idle'
    justSavedTaskId.value = null
  }
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

/** 关抽屉 / 切换任务前调用：取消防抖并立刻走一遍保存（先本地合并与 localStorage，再 PUT） */
async function flushPendingSave() {
  if (props.mode !== 'edit' || !props.task) return
  if (autoSaveTimer) {
    clearTimeout(autoSaveTimer)
    autoSaveTimer = null
  }
  await performAutoSave()
}

onBeforeUnmount(() => {
  void flushPendingSave()
})

defineExpose({ flushPendingSave })

/** 仅描述与当前任务不同且其它字段相同：不触发防抖保存，等描述失焦时再保存，避免一次编辑产生多条活动。 */
function isOnlyDescriptionDirty(
  payload: {
    title: string
    description?: string
    status: Status
    priority: Priority
    assigneeId: number | null
    plannedStartDate?: number
    dueDate?: number
    progressPercent: number
  },
  current: {
    title: string
    description?: string
    status: Status
    priority: Priority
    assigneeId: number | null
    plannedStartDate?: number | null
    dueDate?: number | null
    progressPercent?: number
  }
): boolean {
  if (formLabelStableKey(formLabels.value) !== taskLabelsStableKey(props.task?.labels)) {
    return false
  }
  return (
    descriptionForSave(payload.description) !== descriptionForSave(current.description) &&
    payload.title === current.title &&
    payload.status === current.status &&
    payload.priority === current.priority &&
    (payload.assigneeId ?? null) === (current.assigneeId ?? null) &&
    dueDateKey(payload.plannedStartDate) === dueDateKey(current.plannedStartDate ?? undefined) &&
    (payload.dueDate ?? null) === (current.dueDate ?? null) &&
    payload.progressPercent === clampTaskProgress(current.progressPercent ?? 0)
  )
}

function onDescriptionBlur() {
  if (props.mode !== 'edit' || !props.task) return
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
    if (
      isPayloadEqual(payload, current) &&
      formLabelStableKey(formLabels.value) === taskLabelsStableKey(props.task.labels)
    ) {
      clearTaskEditDraft(props.task.id)
      return
    }
    persistFormDraftIfNeeded()
    if (isOnlyDescriptionDirty(payload, current)) return
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
</script>

<template>
  <aside ref="editorPanelRef" class="editor-panel" :class="{ 'editor-panel--inline': props.variant === 'inline', 'editor-panel--create': props.mode === 'create' }" :aria-label="t('taskEditor.workspaceAria')">
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
        <span v-if="saveStatus === 'saved'" class="save-indicator save-indicator--saved">{{ t('taskEditor.saved') }}</span>
        <span v-else-if="saveStatus === 'saving'" class="save-indicator save-indicator--saving">{{ t('taskEditor.saving') }}</span>
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
        <section class="content-section content-section--title">
          <div v-show="!descriptionEditorReady" class="title-skeleton" aria-hidden="true" />
          <input
            v-show="descriptionEditorReady"
            v-model="formTitle"
            type="text"
            class="title-input"
            :placeholder="t('taskEditor.issueTitlePlaceholder')"
            autofocus
            @keydown.enter.exact.prevent="focusDescription"
          />
        </section>

          <section class="content-section description-section">
            <TiptapEditor
              ref="descriptionEditorRef"
              v-model="formDescription"
              @ready="descriptionEditorReady = true"
              @upload-state-change="onDescriptionUploadStateChange"
              @blur="onDescriptionBlur"
              :placeholder="t('taskEditor.descriptionPlaceholder')"
              :min-height="64"
            />
          </section>

        <input
          ref="attachmentInputRef"
          type="file"
          multiple
          style="display: none"
          @change="onAttachmentInputChange"
        />
        <div class="content-actions">
          <button
            type="button"
            class="content-action-btn"
            :aria-label="t('common.attach')"
            :disabled="mode !== 'edit' || !task"
            @click="openAttachmentInput"
          >
            <Paperclip class="icon-14" />
          </button>
        </div>

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
              <span class="linear-section-count">{{ attachments.length }}</span>
            </button>
            <div v-else class="linear-section-head linear-section-head--static">
              <span class="linear-section-title">{{ t('taskEditor.attachments') }}</span>
              <span class="linear-section-count">{{ attachments.length }}</span>
            </div>
          </div>
          <div v-if="showAttachmentBody" v-show="!attachmentsCollapsed" class="linear-section-body">
            <p v-if="attachmentsLoading" class="linear-placeholder">{{ t('common.loading') }}</p>
            <template v-else>
              <p v-if="attachmentUploadError" class="linear-placeholder linear-placeholder--error">{{ attachmentUploadError }}</p>
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
              <p v-else class="linear-placeholder">{{ t('taskEditor.noAttachments') }}</p>
            </template>
          </div>
        </section>

        <section v-if="mode === 'edit' && task" class="content-section subdued linear-section">
          <div class="linear-section-head-wrap">
            <button
              type="button"
              class="linear-section-head"
              :aria-expanded="!subIssuesCollapsed"
              @click="subIssuesCollapsed = !subIssuesCollapsed"
            >
              <span class="linear-section-chevron">{{ subIssuesCollapsed ? '▸' : '▾' }}</span>
              <span class="linear-section-title">{{ t('taskEditor.subIssues') }}</span>
              <span class="linear-section-count">{{ subIssueCountDisplay.done }}/{{ subIssueCountDisplay.total }}</span>
            </button>
            <label v-if="!subIssuesCollapsed" class="linear-section-display-opt">
              <input
                type="checkbox"
                :checked="viewModeStore.viewConfig.nestedSubIssues"
                @change="viewModeStore.setNestedSubIssues(!viewModeStore.viewConfig.nestedSubIssues)"
              />
              <span>{{ t('taskEditor.nestedSubIssues') }}</span>
            </label>
          </div>
          <div v-show="!subIssuesCollapsed" class="linear-section-body">
            <p v-if="subIssuesLoading" class="linear-placeholder">{{ t('common.loading') }}</p>
            <template v-else>
              <ul v-if="subIssueRows.length" class="linear-sub-list">
                <li
                  v-for="row in subIssueRows"
                  :key="row.task.id"
                  class="linear-sub-item linear-sub-row"
                  :style="{ paddingLeft: row.depth > 0 ? `${row.depth * 16}px` : undefined }"
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
                    <span>{{ row.task.title }}</span>
                    <span v-if="(row.task.subIssueCount ?? 0) > 0" class="linear-sub-xy">{{ row.task.completedSubIssueCount ?? 0 }}/{{ row.task.subIssueCount }}</span>
                  </button>
                </li>
              </ul>
              <p v-else class="linear-placeholder">{{ t('taskEditor.noSubIssues') }}</p>
              <button
                v-if="!showSubIssueForm"
                type="button"
                class="linear-create-btn"
                @click="openSubIssueForm"
              >
                <span class="linear-create-btn-icon">+</span>
                {{ t('taskEditor.createNewSubIssue') }}
              </button>
              <div v-else class="linear-inline-form">
                <input
                  v-model="subIssueFormTitle"
                  type="text"
                  class="linear-inline-title"
                  :placeholder="t('taskEditor.issueTitlePlaceholder')"
                  @keydown.enter.exact.prevent="submitSubIssue"
                />
                <div class="linear-inline-props">
                  <CustomSelect
                    v-model="subIssueFormStatus"
                    :options="statusOptions"
                    :search-placeholder="t('boardView.filterByStatus')"
                    search-shortcut-badge="S"
                    :aria-label="t('common.status')"
                    trigger-class="linear-inline-trigger"
                  />
                  <CustomSelect
                    v-model="subIssueFormPriority"
                    :options="priorityOptions"
                    :aria-label="t('common.priority')"
                    trigger-class="linear-inline-trigger"
                  />
                  <CustomSelect
                    v-model="subIssueFormAssigneeId"
                    :options="assigneeOptions"
                    :placeholder="t('common.assignee')"
                    :aria-label="t('common.assignee')"
                    trigger-class="linear-inline-trigger"
                  />
                  <CustomDatePicker
                    v-model="subIssueFormDueDate"
                    placeholder="Due date"
                    aria-label="Due date"
                    trigger-class="linear-inline-trigger"
                  />
                </div>
                <div class="linear-inline-actions">
                  <button type="button" class="linear-inline-discard" @click="closeSubIssueForm">{{ t('taskEditor.discard') }}</button>
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
            </template>
          </div>
        </section>

        <section v-if="mode === 'edit' && task" class="content-section subdued linear-section">
          <div class="linear-section-head linear-section-head--static">
            <span class="linear-section-title">{{ t('taskEditor.comments') }}</span>
          </div>
          <div class="linear-section-body">
            <div v-if="commentsLoading" class="activity-empty">{{ t('taskEditor.commentsLoading') }}</div>
            <div v-else-if="commentThreads.length" class="task-comments-list">
              <div v-for="thread in commentThreads" :key="thread.root.id" class="task-comment-thread">
                <div class="task-comment-row">
                  <div class="task-comment-meta">
                    <strong>{{ thread.root.authorName }}</strong>
                    <span> · {{ commentTimeFromIso(thread.root.createdAt) }}</span>
                    <button
                      type="button"
                      class="task-comment-reply-btn"
                      @click="openInlineReply(thread.root.id)"
                    >
                      {{ t('taskEditor.reply') }}
                    </button>
                    <button
                      v-if="thread.root.deletable"
                      type="button"
                      class="task-comment-delete"
                      :aria-label="t('taskEditor.deleteCommentAria')"
                      @click="deleteCommentRow(thread.root)"
                    >
                      <Trash2 class="icon-14" />
                    </button>
                  </div>
                  <div class="task-comment-body markdown-body" v-html="renderMarkdown(thread.root.body)" />
                </div>
                <div v-if="thread.replies.length" class="task-comment-replies">
                  <div v-for="reply in visibleRepliesForThread(thread)" :key="reply.id" class="task-comment-row task-comment-row--reply">
                    <div class="task-comment-meta">
                      <strong>{{ reply.authorName }}</strong>
                      <span> · {{ commentTimeFromIso(reply.createdAt) }}</span>
                      <button
                        v-if="reply.deletable"
                        type="button"
                        class="task-comment-delete"
                        :aria-label="t('taskEditor.deleteCommentAria')"
                        @click="deleteCommentRow(reply)"
                      >
                        <Trash2 class="icon-14" />
                      </button>
                    </div>
                    <div class="task-comment-body markdown-body" v-html="renderMarkdown(reply.body)" />
                  </div>
                  <button
                    v-if="thread.hiddenReplyCount > 0"
                    type="button"
                    class="task-comment-toggle-replies"
                    @click="toggleRepliesExpanded(thread.root.id)"
                  >
                    {{
                      isRepliesExpanded(thread.root.id)
                        ? t('taskEditor.hideReplies')
                        : t('taskEditor.viewMoreReplies', { count: thread.hiddenReplyCount })
                    }}
                  </button>
                </div>
                <div v-if="inlineReplyRootId === thread.root.id" class="task-comment-reply-compose" @keydown.capture="(e) => onInlineReplyEditorKeydown(e, thread.root.id)">
                  <TiptapEditor
                    :model-value="replyBodyByRootId[thread.root.id] ?? ''"
                    :mention-members="mentionMembersForCommentEditor"
                    :placeholder="t('taskEditor.replyPlaceholder')"
                    :min-height="56"
                    @update:model-value="(value) => updateInlineReplyBody(thread.root.id, value)"
                  />
                  <button
                    type="button"
                    class="comment-send-btn"
                    :disabled="replySubmittingRootIds.has(thread.root.id) || !(replyBodyByRootId[thread.root.id] ?? '').trim()"
                    :aria-label="t('taskEditor.sendAria')"
                    @click="submitReply(thread.root.id)"
                  >
                    <Send class="icon-14" />
                  </button>
                  <button type="button" class="task-comment-reply-cancel" @click="closeInlineReply(thread.root.id)">
                    {{ t('common.cancel') }}
                  </button>
                </div>
              </div>
            </div>
            <div v-else class="activity-empty">{{ t('taskEditor.noComments') }}</div>
            <div v-if="mentionCandidates.length" class="comment-mention-chips">
              <span class="comment-mention-label">{{ t('taskEditor.notifyMembers') }}</span>
              <button
                v-for="u in mentionCandidates"
                :key="u.id"
                type="button"
                class="comment-mention-chip"
                :class="{ 'comment-mention-chip--on': commentMentionIds.has(u.id) }"
                @click="toggleMentionUser(u.id)"
              >
                @{{ u.username }}
              </button>
            </div>
            <div class="comment-compose" @keydown.capture="onCommentEditorKeydown">
              <TiptapEditor
                ref="commentEditorRef"
                v-model="commentBody"
                :mention-members="mentionMembersForCommentEditor"
                :placeholder="t('taskEditor.leaveComment')"
                :min-height="56"
              />
              <button
                type="button"
                class="comment-send-btn"
                :disabled="commentSubmitting || !commentBody.trim()"
                :aria-label="t('taskEditor.sendAria')"
                @click="submitComment"
              >
                <Send class="icon-14" />
              </button>
            </div>
            <div class="comment-compose-hint">{{ t('taskEditor.commentShortcutHint') }}</div>
          </div>
        </section>

        <section class="content-section subdued linear-section">
          <div class="linear-section-head linear-section-head--static">
            <span class="linear-section-title">{{ t('taskEditor.activity') }}</span>
            <button type="button" class="linear-unsubscribe">{{ t('taskEditor.unsubscribe') }}</button>
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
                  <div class="activity-avatar">{{ getActivityAvatarLabel(item.actorName) }}</div>
                  <div class="activity-text">
                    {{ formatTaskActivityDisplayItem(item) }} ·
                    {{ relativeTimeFromNow(activityDisplayRowTime(item)) }}
                  </div>
                </div>
              </template>
              <div v-else-if="creatorName && createdAgoText" class="activity-item">
                <div class="activity-avatar">{{ getActivityAvatarLabel(creatorName) }}</div>
                <div class="activity-text">
                  <strong>{{ creatorName }}</strong> {{ t('taskEditor.createdIssueSuffix') }} · {{ createdAgoText }}
                </div>
              </div>
              <div v-else class="activity-empty">{{ t('taskEditor.noActivityYet') }}</div>
            </div>
          </div>
        </section>
      </div>

      <div class="editor-props">
        <div class="props-card">
          <section class="prop-group">
            <h3 class="prop-group-title">{{ t('taskEditor.execution') }}</h3>
            <div class="prop-row">
              <span class="prop-label">{{ t('common.status') }}</span>
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
            <div class="prop-row">
              <span class="prop-label">{{ t('taskEditor.setPriority') }}</span>
              <CustomSelect
                id="task-priority"
                v-model="formPriority"
                :options="priorityOptions"
                :aria-label="t('common.priority')"
                trigger-class="prop-trigger prop-trigger--linear"
              />
            </div>
            <div class="prop-row">
              <span class="prop-label">{{ t('common.assignee') }}</span>
              <div class="prop-assignee-stack">
                <CustomSelect
                  id="task-assignee"
                  v-model="formAssigneeId"
                  :options="assigneeOptions"
                  :placeholder="t('common.assignee')"
                  :aria-label="t('common.assignee')"
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
            <div class="prop-row">
              <span class="prop-label">{{ t('common.plannedStartDate') }}</span>
              <CustomDatePicker
                id="task-planned-start"
                v-model="formPlannedStartDate"
                :placeholder="t('common.plannedStartDate')"
                :aria-label="t('common.plannedStartDate')"
                trigger-class="prop-trigger prop-trigger--linear"
              />
            </div>
            <div class="prop-row prop-row--with-helper">
              <span class="prop-label">{{ t('common.dueDate') }}</span>
              <div class="prop-row-stack">
                <CustomDatePicker
                  id="task-due"
                  v-model="formDueDate"
                  :placeholder="t('common.dueDate')"
                  :aria-label="t('common.dueDate')"
                  trigger-class="prop-trigger prop-trigger--linear"
                />
                <p v-if="taskDueStateText" class="prop-row-help">{{ taskDueStateText }}</p>
              </div>
            </div>
            <div v-if="mode === 'edit'" class="prop-row">
              <span class="prop-label">{{ t('taskEditor.progress') }}</span>
              <div class="prop-progress-control">
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
          </section>

          <section class="prop-group">
            <h3 class="prop-group-title">{{ t('taskEditor.archive') }}</h3>
            <div v-if="showPropRowLabels" class="prop-row prop-row-labels">
              <span class="prop-label">{{ t('common.labels') }}</span>
              <TaskLabelCombobox
                ref="taskLabelComboboxRef"
                v-model="labelInput"
                :labels="formLabels"
                :project-id="effectiveProjectId"
                :disabled="mode !== 'edit' || !task"
                :sidebar-root="editorPanelRef"
                :task-id="task?.id ?? null"
                :placeholder="t('taskEditor.addLabel')"
                :ariaLabel="t('taskEditor.addLabel')"
                :remove-label-aria-label="t('taskEditor.removeLabel')"
                :delete-definition-aria-label="t('taskEditor.deleteProjectLabelDefinition')"
                @pick="pickSuggestion"
                @create="commitLabelInput"
                @remove="removeFormLabel"
                @delete-label-definition="onDeleteLabelDefinition"
              />
            </div>
            <div class="prop-row prop-row--linear-action">
              <span class="prop-label">{{ t('common.project') }}</span>
              <button type="button" class="prop-action-trigger" :aria-label="t('taskEditor.addToProject')">
                <Folder class="icon-14" />
                <span>{{ taskProjectName ?? t('taskEditor.addToProject') }}</span>
              </button>
            </div>
            <div v-if="mode === 'edit' && task?.completedAt" class="prop-row read-only">
              <span class="prop-label">{{ t('taskEditor.completedAt') }}</span>
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
  border-radius: 0;
  box-shadow: none;
  border: none;
  border-left: 1px solid var(--color-border-subtle);
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
.save-indicator {
  font-size: var(--font-size-xs);
}
.save-indicator--saved {
  color: var(--color-text-muted);
}
.save-indicator--saving {
  color: var(--color-text-secondary);
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
  background: var(--color-bg-subtle);
}
.editor-content {
  flex: 1;
  min-width: 0;
  min-height: 0;
  padding: 16px 20px 20px;
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
  flex-shrink: 0;
}
.title-skeleton {
  height: 2rem;
  border-radius: 4px;
  background: var(--color-border);
  opacity: 0.5;
  max-width: 80%;
  animation: title-skeleton-pulse 1.2s ease-in-out infinite;
}

@keyframes title-skeleton-pulse {
  0%, 100% { opacity: 0.4; }
  50% { opacity: 0.65; }
}

.content-section--title .title-input {
  font-size: 2rem;
  font-weight: 700;
  line-height: 1.18;
  letter-spacing: -0.035em;
}
.content-section.description-section {
  margin-top: 10px;
  padding-top: 0;
  min-height: 0;
  flex-shrink: 0;
}
/* 新建任务时标题与描述间距略大，更易区分 */
.editor-panel--create .content-section--title {
  margin-bottom: 16px;
  padding-bottom: 0;
}
.editor-panel--create .content-section.description-section {
  margin-top: 8px;
}
.content-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: -4px;
  flex-shrink: 0;
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
  padding-top: 12px;
  border-top: 1px solid var(--color-border-subtle);
  flex-shrink: 0;
}
.linear-section-head-wrap {
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
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
.linear-placeholder--error {
  color: var(--color-text-error, #c00);
}
.linear-create-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  margin-top: 8px;
  padding: 8px 10px;
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-base);
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: border-color var(--transition-fast), color var(--transition-fast), background var(--transition-fast);
}
.linear-create-btn:hover {
  border-color: var(--color-accent);
  color: var(--color-accent);
  background: var(--color-bg-hover, rgba(99, 102, 241, 0.06));
}
.linear-create-btn-icon {
  font-size: 1.1em;
  font-weight: 600;
  line-height: 1;
}
.linear-inline-form {
  margin-top: 8px;
  padding: 10px 0;
  border-top: 1px solid var(--color-border-subtle);
}
.linear-inline-title {
  width: 100%;
  padding: 6px 8px;
  margin-bottom: 8px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  font-size: var(--font-size-caption);
  background: var(--color-bg-base);
}
.linear-inline-title:focus {
  outline: none;
  border-color: var(--color-accent);
}
.linear-inline-props {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
}
.linear-inline-actions {
  display: flex;
  gap: 8px;
}
.linear-inline-discard {
  padding: 4px 10px;
  border: none;
  background: transparent;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
  cursor: pointer;
}
.linear-inline-discard:hover {
  color: var(--color-text-secondary);
}
.linear-inline-create {
  padding: 4px 12px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-accent);
  color: #fff;
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  cursor: pointer;
}
.linear-inline-create:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}
.editor-props :deep(.linear-inline-trigger) {
  min-height: 28px;
  padding: 2px 8px;
  font-size: var(--font-size-xs);
}
/* 子任务表单内 Due date 未选时与 Status/Priority/Assignee 文字颜色一致 */
.linear-inline-form :deep(.trigger-label.placeholder) {
  color: var(--color-text-primary);
}
.activity-list-wrap {
  max-height: 220px;
  overflow-y: auto;
  overflow-x: hidden;
}
.activity-item {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
  min-height: 24px;
}
.activity-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--color-bg-muted);
  flex-shrink: 0;
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  line-height: 1;
}
.activity-text {
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  line-height: 1.4;
  flex: 1;
  min-width: 0;
}
.activity-empty {
  margin-bottom: 12px;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
}
.activity-text strong {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-medium);
}
.linear-unsubscribe {
  padding: 0 4px;
  border: none;
  background: transparent;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  cursor: pointer;
  transition: color var(--transition-fast);
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
.task-comment-thread {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.task-comment-row {
  padding: 10px 12px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: var(--color-bg-base);
}
.task-comment-row--reply {
  margin-left: 16px;
}
.task-comment-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
  margin-bottom: 6px;
}
.task-comment-reply-btn {
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  cursor: pointer;
  padding: 0;
}
.task-comment-reply-btn:hover {
  color: var(--color-text-primary);
}
.task-comment-delete {
  margin-left: auto;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 4px;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  border-radius: var(--radius-sm);
  cursor: pointer;
}
.task-comment-delete:hover {
  color: var(--color-danger, #e5484d);
  background: var(--color-bg-hover);
}
.task-comment-body {
  font-size: var(--font-size-caption);
  color: var(--color-text-primary);
  line-height: 1.45;
}
.task-comment-body :deep(p) {
  margin: 0 0 0.5em;
}
.task-comment-body :deep(p:last-child) {
  margin-bottom: 0;
}
.task-comment-replies {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.task-comment-toggle-replies {
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  cursor: pointer;
  padding: 0;
  margin-left: 16px;
  text-align: left;
}
.task-comment-toggle-replies:hover {
  color: var(--color-text-primary);
}
.comment-mention-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}
.comment-mention-label {
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
}
.comment-mention-chip {
  padding: 4px 8px;
  border: 1px solid var(--color-border-subtle);
  border-radius: 999px;
  background: var(--color-bg-base);
  font-size: var(--font-size-caption);
  cursor: pointer;
  color: var(--color-text-secondary);
}
.comment-mention-chip--on {
  border-color: var(--color-accent, #5e6ad2);
  background: rgba(94, 106, 210, 0.12);
  color: var(--color-accent, #5e6ad2);
}
.comment-compose {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: var(--color-bg-base);
}
.comment-compose :deep(.tiptap-editor-wrap) {
  flex: 1;
  min-width: 0;
}
.task-comment-reply-compose {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  margin-left: 16px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: var(--color-bg-base);
}
.task-comment-reply-compose :deep(.tiptap-editor-wrap) {
  flex: 1;
  min-width: 0;
}
.task-comment-reply-cancel {
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  cursor: pointer;
}
.task-comment-reply-cancel:hover {
  color: var(--color-text-primary);
}
.comment-compose-hint {
  margin-top: 6px;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
}
.comment-send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 8px 10px;
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-accent, #5e6ad2);
  color: #fff;
  cursor: pointer;
  flex-shrink: 0;
}
.comment-send-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
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
  padding-top: 12px;
  border-top: 1px solid var(--color-border-subtle);
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
.description-section {
  position: relative;
}
.editor-props {
  min-width: 260px;
  width: 260px;
  flex-shrink: 0;
  border-left: 1px solid var(--color-border-subtle);
  padding: 12px 12px 14px;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
  background: var(--color-bg-base);
}
.props-card {
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
}
.prop-group {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--color-border-subtle);
}
.prop-group:last-child {
  padding-bottom: 0;
  border-bottom: none;
}
.prop-group-title {
  margin: 0;
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-semibold);
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--color-text-muted);
}
.props-title {
  font-size: var(--font-size-xs);
  font-weight: var(--font-weight-medium);
  letter-spacing: 0.02em;
  text-transform: none;
  color: var(--color-text-muted);
  margin-bottom: 2px;
}
.prop-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.prop-row--with-helper {
  gap: 6px;
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
}
.prop-row-help {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  line-height: 1.35;
}
.external-assignee-hint {
  margin: 0;
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  line-height: 1.35;
}
.prop-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  font-weight: var(--font-weight-normal);
}
.prop-row--linear-action .prop-label {
  margin-bottom: 2px;
}
.prop-row-labels .prop-label {
  margin-bottom: 2px;
}
.prop-action-trigger {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  padding: var(--control-padding-y) var(--control-padding-x);
  border: none;
  border-radius: var(--radius-sm);
  background: var(--color-bg-muted);
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  text-align: left;
  cursor: pointer;
  transition: background var(--transition-fast), color var(--transition-fast);
}
.prop-action-trigger:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.editor-props :deep(.prop-trigger),
.editor-props :deep(.prop-trigger--linear) {
  background: var(--color-bg-muted);
  border: 1px solid var(--color-border-subtle);
  color: var(--color-text-primary);
  padding: var(--control-padding-y) var(--control-padding-x);
  min-height: var(--input-min-height);
  border-radius: var(--radius-sm);
  text-align: left;
  font-size: var(--font-size-caption);
  cursor: pointer;
  transition: background var(--transition-fast), border-color var(--transition-fast);
}
.editor-props :deep(.prop-trigger:hover) {
  background: var(--color-bg-hover);
  border-color: var(--color-border);
}
.read-only .read-only-value {
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
}

.prop-progress-control {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: var(--control-padding-y) var(--control-padding-x);
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-sm);
  background: var(--color-bg-muted);
}
.prop-progress-range {
  flex: 1;
  min-width: 0;
  accent-color: var(--color-accent, #6366f1);
}
.prop-progress-value {
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  min-width: 2.75rem;
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
    width: auto;
    border-left: none;
    border-top: 1px solid var(--color-border-subtle);
  }
}
</style>
