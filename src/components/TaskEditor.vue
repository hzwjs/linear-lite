<script setup lang="ts">
import { ref, watch, onMounted, computed, nextTick } from 'vue'

const AUTO_SAVE_DEBOUNCE_MS = 600
const SAVED_INDICATOR_MS = 2000
import type { Task, Status, Priority, TaskActivity, User } from '../types/domain'
import { useTaskStore } from '../store/taskStore'
import { useFavoriteStore } from '../store/favoriteStore'
import { useProjectStore } from '../store/projectStore'
import { useViewModeStore } from '../store/viewModeStore'
import { useRouter } from 'vue-router'
import { userApi } from '../services/api/user'
import { activityApi } from '../services/api/activity'
import { formatTaskActivity, getActivityAvatarLabel } from '../utils/taskActivity'
import { formatDateInputValue, parseDateInputValue } from '../utils/taskDate'
import TiptapEditor from './TiptapEditor.vue'
import CustomSelect from './ui/CustomSelect.vue'
import CustomDatePicker from './ui/CustomDatePicker.vue'
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
  Link2,
  Tag,
  Folder,
  Send
} from 'lucide-vue-next'

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

const store = useTaskStore()
const favoriteStore = useFavoriteStore()
const projectStore = useProjectStore()
const viewModeStore = useViewModeStore()
const router = useRouter()

const formTitle = ref('')
const formDescription = ref('')
const descriptionEditorRef = ref<InstanceType<typeof TiptapEditor> | null>(null)

function focusDescription() {
  nextTick(() => descriptionEditorRef.value?.focus())
}
const formStatus = ref<Status>('backlog')
const formPriority = ref<Priority>('medium')
const formAssigneeId = ref<string | number>('')
const formDueDate = ref('') // YYYY-MM-DD for input[type=date]
const userList = ref<User[]>([])
const saveStatus = ref<'idle' | 'saving' | 'saved'>('idle')
const activities = ref<TaskActivity[]>([])
const activitiesLoading = ref(false)
/** 刚由本端保存的任务 id，避免 save 后 loadForm 用接口返回值覆盖编辑器内容 */
const justSavedTaskId = ref<string | null>(null)
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null

const statusOptions: CustomSelectOption[] = [
  { value: 'backlog', label: 'Backlog', icon: CircleDashed, shortcut: '1' },
  { value: 'todo', label: 'Todo', icon: Circle, shortcut: '2' },
  { value: 'in_progress', label: 'In Progress', icon: Loader2, shortcut: '3' },
  { value: 'in_review', label: 'In Review', icon: Eye, shortcut: '4' },
  { value: 'done', label: 'Done', icon: CheckCircle, shortcut: '5' },
  { value: 'canceled', label: 'Canceled', icon: CircleX, shortcut: '6' },
  { value: 'duplicate', label: 'Duplicate', icon: Copy, shortcut: '7' }
]
const priorityOptions: CustomSelectOption[] = [
  { value: 'low', label: 'Low', icon: PriorityLowIcon },
  { value: 'medium', label: 'Medium', icon: PriorityMediumIcon },
  { value: 'high', label: 'High', icon: PriorityHighIcon },
  { value: 'urgent', label: 'Urgent', icon: PriorityUrgentIcon }
]
const assigneeOptions = computed<CustomSelectOption[]>(() => {
  const list: CustomSelectOption[] = [{ value: '', label: 'Unassigned', icon: UserIcon }]
  for (const u of userList.value) {
    list.push({ value: u.id, label: u.username, icon: UserIcon })
  }
  return list
})

const breadcrumbScopeName = computed(() => {
  if (props.mode !== 'edit' || !props.task?.projectId) {
    const active = projectStore.projects.find((p) => p.id === projectStore.activeProjectId)
    return active?.name ?? 'Workspace'
  }
  const project = projectStore.projects.find((p) => p.id === props.task!.projectId)
  return project?.name ?? 'Workspace'
})

const showBreadcrumb = computed(() => props.mode === 'edit' && !!props.task)
const isFavorited = computed(() => {
  if (!props.task?.id) return false
  return favoriteStore.isFavorite(props.task.id) || props.task.favorited === true
})

const creatorName = computed(() => {
  if (props.mode !== 'edit' || !props.task?.creatorId) return null
  const u = userList.value.find((x) => x.id === props.task!.creatorId)
  return u?.username ?? 'Someone'
})

function relativeTimeFromNow(timestamp: number) {
  const sec = Math.floor((Date.now() - timestamp) / 1000)
  if (sec < 60) return 'just now'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}m ago`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d}d ago`
  const mo = Math.floor(d / 30)
  return `${mo}mo ago`
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

/** Phase 7: 父任务（用于 Sub-issue of XXX 链接） */
const parentTask = computed(() => {
  if (!props.task?.parentId) return null
  return store.tasks.find((t) => t.id === props.task!.parentId) ?? null
})

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
  subIssuesLoading.value = true
  try {
    const direct = await store.fetchSubIssues(props.task.numericId)
    const nested = viewModeStore.viewConfig.nestedSubIssues
    if (!nested) {
      subIssueRows.value = direct.map((t) => ({ task: t, depth: 0 }))
      return
    }
    const rows: { task: Task; depth: number }[] = []
    async function appendChildren(parentNumericId: number, depth: number) {
      const children = await store.fetchSubIssues(parentNumericId)
      for (const t of children) {
        rows.push({ task: t, depth })
        if (t.numericId != null) await appendChildren(t.numericId, depth + 1)
      }
    }
    for (const t of direct) {
      rows.push({ task: t, depth: 0 })
      if (t.numericId != null) await appendChildren(t.numericId, 1)
    }
    subIssueRows.value = rows
  } finally {
    subIssuesLoading.value = false
  }
}

async function loadActivities(options?: { silent?: boolean }) {
  if (props.mode !== 'edit' || !props.task?.id) {
    activities.value = []
    return
  }
  const silent = options?.silent === true && activities.value.length > 0
  if (!silent) activitiesLoading.value = true
  try {
    activities.value = await activityApi.list(props.task.id)
  } finally {
    if (!silent) activitiesLoading.value = false
  }
}

function openSubIssueForm() {
  showSubIssueForm.value = true
  subIssueFormTitle.value = ''
  subIssueFormDescription.value = ''
  subIssueFormStatus.value = props.task?.status ?? 'backlog'
  subIssueFormPriority.value = props.task?.priority ?? 'medium'
  subIssueFormAssigneeId.value = ''
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

watch(
  [() => props.task?.id, () => props.mode],
  () => {
    loadSubIssues()
    loadActivities()
  },
  { immediate: true }
)
watch(
  () => viewModeStore.viewConfig.nestedSubIssues,
  () => loadSubIssues()
)

onMounted(async () => {
  try {
    userList.value = await userApi.list()
  } catch (e) {
    console.error('Failed to load users:', e)
  }
})

function toDateInputValue(ms: number | undefined | null): string {
  return formatDateInputValue(ms)
}

/** 全选删除列表后可能留下仅空列表项（如 "- \n- "）。仅在保存时视为空，不往编辑器回写，避免可见的覆盖过程 */
function descriptionForSave(desc: string | undefined): string {
  const s = (desc ?? '').trim()
  if (!s) return ''
  const emptyListLine = /^\s*[-*+]\s*$|^\s*\d+\.\s*$/
  const onlyEmptyLists = s.split(/\n/).every((line) => !line.trim() || emptyListLine.test(line.trim()))
  return onlyEmptyLists ? '' : s
}

const loadForm = () => {
  if (props.mode === 'edit' && props.task) {
    formTitle.value = props.task.title
    formDescription.value = props.task.description || ''
    formStatus.value = props.task.status
    formPriority.value = props.task.priority
    formAssigneeId.value = props.task.assigneeId ?? ''
    formDueDate.value = toDateInputValue(props.task.dueDate ?? undefined)
  } else {
    formTitle.value = ''
    formDescription.value = ''
    formStatus.value = props.defaultStatus ?? 'backlog'
    formPriority.value = 'medium'
    formAssigneeId.value = ''
    formDueDate.value = ''
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
  if (props.mode === 'create') formStatus.value = props.defaultStatus ?? 'backlog'
})

function getPayload() {
  const dueDateMs = parseDateInputValue(formDueDate.value)
  return {
    title: formTitle.value.trim(),
    description: descriptionForSave(formDescription.value),
    status: formStatus.value,
    priority: formPriority.value,
    assigneeId: formAssigneeId.value === '' ? null : Number(formAssigneeId.value),
    dueDate: dueDateMs
  }
}

function dueDateKey(ms: number | undefined | null): string {
  if (ms == null) return ''
  const d = new Date(ms)
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

function isPayloadEqual(
  a: { title: string; description?: string; status: Status; priority: Priority; assigneeId: number | null; dueDate?: number },
  b: { title: string; description?: string; status: Status; priority: Priority; assigneeId?: number | null; dueDate?: number | null }
) {
  return (
    a.title === (b.title ?? '') &&
    descriptionForSave(a.description) === descriptionForSave(b.description) &&
    a.status === b.status &&
    a.priority === b.priority &&
    (a.assigneeId ?? null) === (b.assigneeId ?? null) &&
    dueDateKey(a.dueDate) === dueDateKey(b.dueDate ?? undefined)
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
    dueDate: props.task.dueDate ?? null
  }
  if (isPayloadEqual(payload, current)) return

  saveStatus.value = 'saving'
  justSavedTaskId.value = props.task.id
  try {
    await store.updateTask(props.task.id, {
      title: payload.title,
      description: payload.description,
      status: payload.status,
      priority: payload.priority,
      assigneeId: payload.assigneeId,
      dueDate: payload.dueDate
    })
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

function scheduleAutoSave() {
  if (autoSaveTimer) clearTimeout(autoSaveTimer)
  autoSaveTimer = setTimeout(() => {
    autoSaveTimer = null
    performAutoSave()
  }, AUTO_SAVE_DEBOUNCE_MS)
}

watch(
  () => [
    formTitle.value,
    formDescription.value,
    formStatus.value,
    formPriority.value,
    formAssigneeId.value,
    formDueDate.value
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
      dueDate: props.task.dueDate ?? null
    }
    if (isPayloadEqual(payload, current)) return
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
  <aside class="editor-panel" :class="{ 'editor-panel--inline': props.variant === 'inline' }" aria-label="Issue workspace">
    <div class="editor-header">
      <div class="editor-header-meta">
        <nav v-if="showBreadcrumb" class="editor-breadcrumb" aria-label="Breadcrumb">
          <button type="button" class="editor-breadcrumb-link" @click="navigateToProject">
            {{ breadcrumbScopeName }}
          </button>
          <span class="editor-breadcrumb-separator">/</span>
          <span class="editor-breadcrumb-current">{{ task?.id }}</span>
        </nav>
        <template v-else>
          <span v-if="task?.id" class="issue-id">{{ task.id }}</span>
          <h2>{{ mode === 'create' ? 'New issue' : 'Issue' }}</h2>
        </template>
        <a
          v-if="mode === 'edit' && parentTask"
          href="#"
          class="editor-parent-link"
          @click.prevent="navigateTo(parentTask.id)"
        >
          Sub-issue of {{ parentTask.id }} {{ parentTask.title }}
          <span v-if="(parentTask.subIssueCount ?? 0) > 0" class="editor-parent-count">{{ parentTask.completedSubIssueCount ?? 0 }}/{{ parentTask.subIssueCount }}</span>
        </a>
        <button
          v-if="showBreadcrumb"
          type="button"
          class="header-icon-btn"
          :class="{ 'header-icon-btn--active': isFavorited }"
          :aria-label="isFavorited ? 'Remove from favorites' : 'Add to favorites'"
          @click="toggleFavorite"
        >
          <Star class="icon-16" :fill="isFavorited ? 'currentColor' : 'none'" />
        </button>
      </div>
      <div class="editor-header-actions">
        <span v-if="saveStatus === 'saved'" class="save-indicator save-indicator--saved">Saved</span>
        <span v-else-if="saveStatus === 'saving'" class="save-indicator save-indicator--saving">Saving...</span>
        <div v-if="position && total" class="issue-position">{{ position }} / {{ total }}</div>
        <button
          class="nav-btn"
          :disabled="!previousTaskId"
          aria-label="Previous issue"
          @click="navigateTo(previousTaskId)"
        >
          ←
        </button>
        <button
          class="nav-btn"
          :disabled="!nextTaskId"
          aria-label="Next issue"
          @click="navigateTo(nextTaskId)"
        >
          →
        </button>
        <button class="close-btn" @click="closeEditor" aria-label="Close">×</button>
      </div>
    </div>

    <div class="editor-body">
      <div class="editor-content">
        <section class="content-section content-section--title">
          <textarea
            v-model="formTitle"
            class="title-textarea"
            placeholder="Issue title"
            rows="2"
            autofocus
            @keydown.enter.exact.prevent="focusDescription"
          />
        </section>

        <section class="content-section description-section">
          <TiptapEditor
            ref="descriptionEditorRef"
            v-model="formDescription"
            placeholder="Add description… Type / for formatting"
            :min-height="64"
          />
        </section>

        <div class="content-actions">
          <button type="button" class="content-action-btn" aria-label="Attach">
            <Paperclip class="icon-14" />
          </button>
          <button type="button" class="content-action-btn" aria-label="Link">
            <Link2 class="icon-14" />
          </button>
          <button type="button" class="content-action-btn" aria-label="Watchers">
            <Eye class="icon-14" />
          </button>
        </div>

        <section v-if="mode === 'edit' && task" class="content-section subdued linear-section">
          <div class="linear-section-head-wrap">
            <button
              type="button"
              class="linear-section-head"
              :aria-expanded="!subIssuesCollapsed"
              @click="subIssuesCollapsed = !subIssuesCollapsed"
            >
              <span class="linear-section-chevron">{{ subIssuesCollapsed ? '▸' : '▾' }}</span>
              <span class="linear-section-title">Sub-issues</span>
              <span class="linear-section-count">{{ subIssueCountDisplay.done }}/{{ subIssueCountDisplay.total }}</span>
            </button>
            <label v-if="!subIssuesCollapsed" class="linear-section-display-opt">
              <input
                type="checkbox"
                :checked="viewModeStore.viewConfig.nestedSubIssues"
                @change="viewModeStore.setNestedSubIssues(!viewModeStore.viewConfig.nestedSubIssues)"
              />
              <span>Nested sub-issues</span>
            </label>
          </div>
          <div v-show="!subIssuesCollapsed" class="linear-section-body">
            <p v-if="subIssuesLoading" class="linear-placeholder">Loading…</p>
            <template v-else>
              <ul v-if="subIssueRows.length" class="linear-sub-list">
                <li
                  v-for="row in subIssueRows"
                  :key="row.task.id"
                  class="linear-sub-item"
                  :style="{ paddingLeft: row.depth > 0 ? `${row.depth * 16}px` : undefined }"
                >
                  <button
                    type="button"
                    class="linear-sub-link"
                    @click="navigateTo(row.task.id)"
                  >
                    <CheckCircle v-if="row.task.status === 'done'" class="icon-14 icon-done" />
                    <Circle v-else class="icon-14 icon-circle" />
                    <span>{{ row.task.title }}</span>
                    <span v-if="(row.task.subIssueCount ?? 0) > 0" class="linear-sub-xy">{{ row.task.completedSubIssueCount ?? 0 }}/{{ row.task.subIssueCount }}</span>
                  </button>
                </li>
              </ul>
              <p v-else class="linear-placeholder">No sub-issues. Add one to break down this task.</p>
              <button
                v-if="!showSubIssueForm"
                type="button"
                class="linear-create-btn"
                @click="openSubIssueForm"
              >
                Create new sub-issue
              </button>
              <div v-else class="linear-inline-form">
                <input
                  v-model="subIssueFormTitle"
                  type="text"
                  class="linear-inline-title"
                  placeholder="Issue title"
                  @keydown.enter.exact.prevent="submitSubIssue"
                />
                <div class="linear-inline-props">
                  <CustomSelect
                    v-model="subIssueFormStatus"
                    :options="statusOptions"
                    search-placeholder="Change status..."
                    search-shortcut-badge="S"
                    aria-label="Status"
                    trigger-class="linear-inline-trigger"
                  />
                  <CustomSelect
                    v-model="subIssueFormPriority"
                    :options="priorityOptions"
                    aria-label="Priority"
                    trigger-class="linear-inline-trigger"
                  />
                  <CustomSelect
                    v-model="subIssueFormAssigneeId"
                    :options="assigneeOptions"
                    placeholder="Assignee"
                    aria-label="Assignee"
                    trigger-class="linear-inline-trigger"
                  />
                </div>
                <div class="linear-inline-actions">
                  <button type="button" class="linear-inline-discard" @click="closeSubIssueForm">Discard</button>
                  <button
                    type="button"
                    class="linear-inline-create"
                    :disabled="!subIssueFormTitle.trim() || subIssueSaving"
                    @click="submitSubIssue"
                  >
                    {{ subIssueSaving ? 'Creating…' : 'Create' }}
                  </button>
                </div>
              </div>
            </template>
          </div>
        </section>

        <section class="content-section subdued linear-section">
          <div class="linear-section-head linear-section-head--static">
            <span class="linear-section-title">Activity</span>
            <button type="button" class="linear-unsubscribe">Unsubscribe</button>
          </div>
          <div class="linear-section-body">
            <div v-if="activitiesLoading" class="activity-empty">Loading activity…</div>
            <template v-else-if="activities.length">
              <div v-for="activity in activities" :key="activity.id" class="activity-item">
                <div class="activity-avatar">{{ getActivityAvatarLabel(activity.actorName) }}</div>
                <div class="activity-text">
                  {{ formatTaskActivity(activity) }} · {{ relativeTimeFromNow(activity.createdAt) }}
                </div>
              </div>
            </template>
            <div v-else-if="creatorName && createdAgoText" class="activity-item">
              <div class="activity-avatar">{{ getActivityAvatarLabel(creatorName) }}</div>
              <div class="activity-text">
                <strong>{{ creatorName }}</strong> created the issue · {{ createdAgoText }}
              </div>
            </div>
            <div v-else class="activity-empty">No activity yet.</div>
            <div class="comment-input-wrap">
              <input
                type="text"
                class="comment-input"
                placeholder="Leave a comment..."
                readonly
                aria-label="Comment"
              />
              <div class="comment-input-actions">
                <button type="button" class="comment-action-btn" aria-label="Attach">
                  <Paperclip class="icon-14" />
                </button>
                <button type="button" class="comment-action-btn" aria-label="Send">
                  <Send class="icon-14" />
                </button>
              </div>
            </div>
          </div>
        </section>
      </div>

      <div class="editor-props">
        <div class="props-card">
          <div class="prop-row">
            <span class="prop-label">Status</span>
            <CustomSelect
              id="task-status"
              v-model="formStatus"
              :options="statusOptions"
              search-placeholder="Change status..."
              search-shortcut-badge="S"
              aria-label="Status"
              trigger-class="prop-trigger prop-trigger--linear"
            />
          </div>
          <div class="prop-row">
            <span class="prop-label">Set priority</span>
            <CustomSelect
              id="task-priority"
              v-model="formPriority"
              :options="priorityOptions"
              aria-label="Priority"
              trigger-class="prop-trigger prop-trigger--linear"
            />
          </div>
          <div class="prop-row">
            <span class="prop-label">Assignee</span>
            <CustomSelect
              id="task-assignee"
              v-model="formAssigneeId"
              :options="assigneeOptions"
              placeholder="Assign"
              aria-label="Assignee"
              trigger-class="prop-trigger prop-trigger--linear"
            />
          </div>
          <div class="prop-row prop-row--linear-action">
            <span class="prop-label">Labels</span>
            <button type="button" class="prop-action-trigger" aria-label="Add label">
              <Tag class="icon-14" />
              <span>Add label</span>
            </button>
          </div>
          <div class="prop-row prop-row--linear-action">
            <span class="prop-label">Project</span>
            <button type="button" class="prop-action-trigger" aria-label="Add to project">
              <Folder class="icon-14" />
              <span>{{ taskProjectName ?? 'Add to project' }}</span>
            </button>
          </div>
          <div class="prop-row">
            <span class="prop-label">Due Date</span>
            <CustomDatePicker
              id="task-due"
              v-model="formDueDate"
              placeholder="Select date"
              aria-label="Due date"
              trigger-class="prop-trigger prop-trigger--linear"
            />
          </div>
          <div v-if="mode === 'edit' && task?.completedAt" class="prop-row read-only">
            <span class="prop-label">Completed at</span>
            <span class="read-only-value">{{ new Date(task.completedAt).toLocaleString() }}</span>
          </div>
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
.editor-parent-link {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--color-accent);
  margin-top: 4px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.editor-parent-link:hover {
  text-decoration: underline;
}
.editor-parent-count {
  margin-left: 6px;
  color: var(--color-text-muted);
  font-weight: var(--font-weight-normal);
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
/* 标题与描述保留间距，便于区分与点击 */
.content-section--title {
  margin-bottom: 0;
  padding-bottom: 2px;
  flex-shrink: 0;
}
.content-section--title .title-textarea {
  font-size: 1.5rem;
  font-weight: 600;
  line-height: 1.35;
  letter-spacing: -0.02em;
}
.content-section.description-section {
  margin-top: 14px;
  padding-top: 0;
  min-height: 0;
  flex-shrink: 0;
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
}
.linear-sub-link:hover {
  color: var(--color-accent);
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
.linear-create-btn {
  padding: 6px 0;
  border: none;
  background: transparent;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
  cursor: pointer;
}
.linear-create-btn:hover {
  color: var(--color-accent);
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
.activity-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 12px;
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
.comment-input-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border: 1px solid var(--color-border-subtle);
  border-radius: var(--radius-md);
  background: var(--color-bg-base);
}
.comment-input {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: none;
  background: transparent;
  font-size: var(--font-size-caption);
  color: var(--color-text-primary);
}
.comment-input::placeholder {
  color: var(--color-text-muted);
}
.comment-input:focus {
  outline: none;
}
.comment-input-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}
.comment-action-btn {
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
.comment-action-btn:hover {
  color: var(--color-text-secondary);
  background: var(--color-bg-hover);
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
.title-textarea {
  font-size: var(--font-size-subhead);
  font-weight: var(--font-weight-semibold);
  color: var(--color-text-primary);
  border: none;
  resize: none;
  padding: 0;
  line-height: 1.3;
  letter-spacing: var(--letter-spacing);
  background: transparent;
}
.title-textarea::placeholder {
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
  gap: 10px;
  padding: 0;
  border: none;
  border-radius: 0;
  background: transparent;
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
.prop-label {
  font-size: var(--font-size-xs);
  color: var(--color-text-muted);
  font-weight: var(--font-weight-normal);
}
.prop-row--linear-action .prop-label {
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
