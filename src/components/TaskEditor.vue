<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'
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
  User as UserIcon
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
const isSaving = ref(false)
const userList = ref<User[]>([])

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

const handleSave = async () => {
  if (!formTitle.value.trim()) return

  isSaving.value = true
  const dueDateMs =
    formDueDate.value
      ? new Date(formDueDate.value + 'T00:00:00').getTime()
      : undefined

  try {
    if (props.mode === 'create') {
      await store.createTask({
        title: formTitle.value.trim(),
        description: formDescription.value.trim() || undefined,
        status: formStatus.value,
        priority: formPriority.value,
        assigneeId: formAssigneeId.value === '' ? null : Number(formAssigneeId.value),
        dueDate: dueDateMs
      })
    } else if (props.mode === 'edit' && props.task) {
      await store.updateTask(props.task.id, {
        title: formTitle.value.trim(),
        description: formDescription.value.trim() || undefined,
        status: formStatus.value,
        priority: formPriority.value,
        assigneeId: formAssigneeId.value === '' ? null : Number(formAssigneeId.value),
        dueDate: dueDateMs
      })
    }
    closeEditor()
  } catch (error) {
    console.error('Failed to save task:', error)
  } finally {
    isSaving.value = false
  }
}

const closeEditor = () => {
  emit('close')
}

const createdAtText = computed(() =>
  props.task?.createdAt ? new Date(props.task.createdAt).toLocaleString() : null
)
const updatedAtText = computed(() =>
  props.task?.updatedAt ? new Date(props.task.updatedAt).toLocaleString() : null
)
const dueDateSummary = computed(() =>
  props.task?.dueDate ? new Date(props.task.dueDate).toLocaleDateString() : 'No due date'
)

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
      </div>
      <div class="editor-header-actions">
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
        <section class="content-meta">
          <div class="meta-chip">Created {{ createdAtText ?? 'Unknown' }}</div>
          <div class="meta-chip">Updated {{ updatedAtText ?? 'Unknown' }}</div>
          <div class="meta-chip">Due {{ dueDateSummary }}</div>
        </section>

        <section class="content-section">
          <textarea
            v-model="formTitle"
            class="title-textarea"
            placeholder="Issue title"
            rows="2"
            autofocus
            @keydown.enter.exact.prevent="handleSave"
          />
        </section>

        <section class="content-section">
          <textarea
            v-model="formDescription"
            class="description-input"
            placeholder="Add description"
            rows="8"
          />
        </section>

        <section class="content-section subdued">
          <div class="section-head">
            <span class="section-kicker">Context</span>
            <span class="section-note">Resources and activity structure for this issue.</span>
          </div>
          <div class="context-grid">
            <div class="context-card">
              <div class="context-card-title">Resources</div>
              <p>Links, PRs, and attachments will surface here without leaving the issue.</p>
            </div>
            <div class="context-card">
              <div class="context-card-title">Activity</div>
              <p>Status changes and assignment history will become a readable timeline here.</p>
            </div>
          </div>
        </section>
      </div>

      <div class="editor-props">
        <div class="props-card">
          <div class="props-title">Details</div>
          <div class="prop-row">
            <span class="prop-label">Status</span>
            <CustomSelect
              id="task-status"
              v-model="formStatus"
              :options="statusOptions"
              aria-label="Status"
              trigger-class="prop-trigger"
            />
          </div>
          <div class="prop-row">
            <span class="prop-label">Priority</span>
            <CustomSelect
              id="task-priority"
              v-model="formPriority"
              :options="priorityOptions"
              aria-label="Priority"
              trigger-class="prop-trigger"
            />
          </div>
          <div class="prop-row">
            <span class="prop-label">Assignee</span>
            <CustomSelect
              id="task-assignee"
              v-model="formAssigneeId"
              :options="assigneeOptions"
              placeholder="Unassigned"
              aria-label="Assignee"
              trigger-class="prop-trigger"
            />
          </div>
          <div class="prop-row">
            <span class="prop-label">Due Date</span>
            <CustomDatePicker
              id="task-due"
              v-model="formDueDate"
              placeholder="Select date"
              aria-label="Due date"
              trigger-class="prop-trigger"
            />
          </div>
          <div v-if="mode === 'edit' && task?.completedAt" class="prop-row read-only">
            <span class="prop-label">Completed at</span>
            <span class="read-only-value">{{ new Date(task.completedAt).toLocaleString() }}</span>
          </div>
        </div>
      </div>
    </div>

    <div class="editor-footer">
      <button class="btn-cancel" @click="closeEditor">Back</button>
      <button
        class="btn-save"
        :disabled="!formTitle.trim() || isSaving"
        @click="handleSave"
      >
        {{ isSaving ? 'Saving...' : 'Save' }}
      </button>
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
.editor-header-actions {
  display: flex;
  align-items: center;
  gap: 4px;
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
.context-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
}
.context-card {
  border: 1px dashed var(--color-border);
  border-radius: var(--radius-md);
  padding: 12px;
  background: var(--color-bg-base);
}
.context-card-title {
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
  color: var(--color-text-primary);
  margin-bottom: 4px;
}
.context-card p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  line-height: 1.45;
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
.editor-props :deep(.prop-trigger) {
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
.editor-footer {
  padding: 8px 14px;
  border-top: 1px solid var(--color-border-subtle);
  display: flex;
  justify-content: flex-end;
  gap: 6px;
  flex-shrink: 0;
  background: var(--color-bg-base);
}
.btn-cancel {
  padding: var(--control-padding-y) var(--control-padding-x);
  border-radius: var(--radius-sm);
  color: var(--color-text-muted);
  font-size: var(--font-size-caption);
  transition: background var(--transition-fast), color var(--transition-fast);
}
.btn-cancel:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-primary);
}
.btn-save {
  padding: var(--control-padding-y) var(--control-padding-x);
  border-radius: var(--radius-sm);
  background: var(--color-accent);
  color: white;
  font-weight: var(--font-weight-medium);
  font-size: var(--font-size-caption);
  transition: background var(--transition-fast);
}
.btn-save:hover:not(:disabled) {
  background: var(--color-accent-hover);
}
.btn-save:disabled {
  opacity: 0.5;
  cursor: not-allowed;
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

  .context-grid {
    grid-template-columns: 1fr;
  }
}
</style>
