<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'

const AUTO_SAVE_DEBOUNCE_MS = 600
const SAVED_INDICATOR_MS = 2000
import type { Task, Status, Priority } from '../types/domain'
import type { User } from '../types/domain'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import { userApi } from '../services/api/user'
import CustomSelect from './ui/CustomSelect.vue'
import CustomDatePicker from './ui/CustomDatePicker.vue'
import type { CustomSelectOption } from './ui/CustomSelect.vue'
import {
  Circle,
  Loader2,
  CheckCircle,
  ArrowDown,
  Minus,
  ArrowUp,
  Flame,
  User as UserIcon,
  Star,
  Paperclip,
  Link2,
  Eye,
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
const projectStore = useProjectStore()

const formTitle = ref('')
const formDescription = ref('')
const formStatus = ref<Status>('todo')
const formPriority = ref<Priority>('medium')
const formAssigneeId = ref<string | number>('')
const formDueDate = ref('') // YYYY-MM-DD for input[type=date]
const userList = ref<User[]>([])
const saveStatus = ref<'idle' | 'saving' | 'saved'>('idle')
let autoSaveTimer: ReturnType<typeof setTimeout> | null = null

const statusOptions: CustomSelectOption[] = [
  { value: 'todo', label: 'Todo', icon: Circle },
  { value: 'in_progress', label: 'In Progress', icon: Loader2 },
  { value: 'done', label: 'Done', icon: CheckCircle }
]
const priorityOptions: CustomSelectOption[] = [
  { value: 'low', label: 'Low', icon: ArrowDown },
  { value: 'medium', label: 'Medium', icon: Minus },
  { value: 'high', label: 'High', icon: ArrowUp },
  { value: 'urgent', label: 'Urgent', icon: Flame }
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

const breadcrumbText = computed(() => {
  if (props.mode !== 'edit' || !props.task) return ''
  return `${breadcrumbScopeName.value} > ${props.task.id} ${props.task.title}`
})

const creatorName = computed(() => {
  if (props.mode !== 'edit' || !props.task?.creatorId) return null
  const u = userList.value.find((x) => x.id === props.task!.creatorId)
  return u?.username ?? 'Someone'
})

const createdAgoText = computed(() => {
  if (!props.task?.createdAt) return ''
  const sec = Math.floor((Date.now() - props.task.createdAt) / 1000)
  if (sec < 60) return 'just now'
  const min = Math.floor(sec / 60)
  if (min < 60) return `${min}m ago`
  const h = Math.floor(min / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.floor(h / 24)
  if (d < 30) return `${d}d ago`
  const mo = Math.floor(d / 30)
  return `${mo}mo ago`
})

const taskProjectName = computed(() => {
  if (!props.task?.projectId) return null
  const p = projectStore.projects.find((x) => x.id === props.task!.projectId)
  return p?.name ?? null
})

onMounted(async () => {
  try {
    userList.value = await userApi.list()
  } catch (e) {
    console.error('Failed to load users:', e)
  }
})

function toDateInputValue(ms: number | undefined | null): string {
  if (ms == null) return ''
  const d = new Date(ms)
  return d.toISOString().slice(0, 10)
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
    formStatus.value = props.defaultStatus ?? 'todo'
    formPriority.value = 'medium'
    formAssigneeId.value = ''
    formDueDate.value = ''
  }
}

watch(() => props.task, loadForm, { immediate: true })
watch(() => props.mode, loadForm)
watch(() => props.defaultStatus, () => {
  if (props.mode === 'create') formStatus.value = props.defaultStatus ?? 'todo'
})

function getPayload() {
  const dueDateMs =
    formDueDate.value
      ? new Date(formDueDate.value + 'T00:00:00').getTime()
      : undefined
  return {
    title: formTitle.value.trim(),
    description: formDescription.value.trim() || undefined,
    status: formStatus.value,
    priority: formPriority.value,
    assigneeId: formAssigneeId.value === '' ? null : Number(formAssigneeId.value),
    dueDate: dueDateMs
  }
}

function isPayloadEqual(
  a: { title: string; description?: string; status: Status; priority: Priority; assigneeId: number | null; dueDate?: number },
  b: { title: string; description?: string; status: Status; priority: Priority; assigneeId?: number | null; dueDate?: number | null }
) {
  return (
    a.title === (b.title ?? '') &&
    (a.description ?? '') === (b.description ?? '') &&
    a.status === b.status &&
    a.priority === b.priority &&
    (a.assigneeId ?? null) === (b.assigneeId ?? null) &&
    (a.dueDate ?? null) === (b.dueDate ?? null)
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
  try {
    await store.updateTask(props.task.id, {
      title: payload.title,
      description: payload.description,
      status: payload.status,
      priority: payload.priority,
      assigneeId: payload.assigneeId,
      dueDate: payload.dueDate
    })
    saveStatus.value = 'saved'
    setTimeout(() => {
      saveStatus.value = 'idle'
    }, SAVED_INDICATOR_MS)
  } catch (error) {
    console.error('Auto-save failed:', error)
    saveStatus.value = 'idle'
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
    if (props.mode === 'edit' && props.task) scheduleAutoSave()
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
</script>

<template>
  <aside class="editor-panel" :class="{ 'editor-panel--inline': props.variant === 'inline' }" aria-label="Issue workspace">
    <div class="editor-header">
      <div class="editor-header-meta">
        <nav v-if="breadcrumbText" class="editor-breadcrumb" aria-label="Breadcrumb">
          {{ breadcrumbText }}
        </nav>
        <template v-else>
          <span v-if="task?.id" class="issue-id">{{ task.id }}</span>
          <h2>{{ mode === 'create' ? 'New issue' : 'Issue' }}</h2>
        </template>
        <button
          v-if="breadcrumbText"
          type="button"
          class="header-icon-btn"
          aria-label="Add to favorites"
        >
          <Star class="icon-16" />
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
            @keydown.enter.exact.prevent
          />
        </section>

        <section class="content-section">
          <textarea
            v-model="formDescription"
            class="description-input"
            placeholder="Add description"
            rows="6"
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

        <section class="content-section subdued linear-section">
          <button type="button" class="linear-section-head" aria-expanded="true">
            <span class="linear-section-title">Sub-issues</span>
            <span class="linear-section-count">0/0</span>
          </button>
          <div class="linear-section-body">
            <p class="linear-placeholder">No sub-issues. Add one to break down this task.</p>
          </div>
        </section>

        <section class="content-section subdued linear-section">
          <div class="linear-section-head linear-section-head--static">
            <span class="linear-section-title">Activity</span>
            <button type="button" class="linear-unsubscribe">Unsubscribe</button>
          </div>
          <div class="linear-section-body">
            <div v-if="creatorName && createdAgoText" class="activity-item">
              <div class="activity-avatar" />
              <div class="activity-text">
                <strong>{{ creatorName }}</strong> created the issue · {{ createdAgoText }}
              </div>
            </div>
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
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
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
  padding: 16px 20px 20px;
  display: flex;
  flex-direction: column;
  gap: 18px;
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
.content-section--title .title-textarea {
  font-size: 1.25rem;
  font-weight: 600;
}
.content-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: -8px;
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
}
.linear-section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0;
  margin-bottom: 8px;
  border: none;
  background: transparent;
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  cursor: pointer;
  text-align: left;
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
.linear-section-body {
  padding-left: 0;
}
.linear-placeholder {
  margin: 0;
  font-size: var(--font-size-caption);
  color: var(--color-text-muted);
}
.activity-item {
  display: flex;
  align-items: flex-start;
  gap: 10px;
  margin-bottom: 12px;
}
.activity-avatar {
  width: 24px;
  height: 24px;
  border-radius: 50%;
  background: var(--color-bg-muted);
  flex-shrink: 0;
}
.activity-text {
  font-size: var(--font-size-caption);
  color: var(--color-text-secondary);
  line-height: 1.4;
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
  line-height: 1.25;
  letter-spacing: var(--letter-spacing);
  background: transparent;
}
.title-textarea::placeholder {
  color: var(--color-text-muted);
}
.description-input {
  font-size: var(--font-size-body);
  color: var(--color-text-primary);
  resize: vertical;
  line-height: 1.5;
  border: none;
  padding: 0;
  background: transparent;
}
.description-input::placeholder {
  color: var(--color-text-muted);
}
.editor-props {
  width: 220px;
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
