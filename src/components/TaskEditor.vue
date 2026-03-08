<script setup lang="ts">
import { ref, watch, onMounted, computed } from 'vue'
import type { Task, Status, Priority } from '../types/domain'
import type { User } from '../types/domain'
import { useTaskStore } from '../store/taskStore'
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

const props = defineProps<{
  mode: 'create' | 'edit'
  task?: Task | null
  /** P4-6.5: 列头 + 新建时的默认状态 */
  defaultStatus?: Status
  previousTaskId?: string | null
  nextTaskId?: string | null
  position?: number | null
  total?: number
}>()

const emit = defineEmits<{
  close: []
  navigate: [taskId: string]
}>()

const store = useTaskStore()

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
  <aside class="editor-panel" aria-label="Issue workspace">
    <div class="editor-header">
      <div class="editor-header-meta">
        <span v-if="task?.id" class="issue-id">{{ task.id }}</span>
        <h2>{{ mode === 'create' ? 'New issue' : 'Issue' }}</h2>
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
.editor-panel {
  width: min(1040px, calc(100vw - 320px));
  min-width: 840px;
  max-width: 1120px;
  max-height: calc(100vh - 52px);
  height: 100%;
  background: var(--color-bg-main);
  border: 1px solid rgba(15, 23, 42, 0.08);
  border-radius: 18px;
  display: flex;
  flex-direction: column;
  box-shadow: 0 24px 60px rgba(15, 23, 42, 0.14);
  overflow: hidden;
}
.editor-header {
  min-height: 56px;
  border-bottom: 1px solid var(--color-border);
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 16px 0 18px;
  flex-shrink: 0;
}
.editor-header-meta {
  display: flex;
  align-items: center;
  gap: 8px;
}
.editor-header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}
.issue-id {
  font-size: 11px;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  color: var(--color-text-secondary);
  padding: 2px 5px;
  border-radius: 999px;
  background: rgba(17, 24, 39, 0.04);
}
.issue-position {
  font-size: 11px;
  color: var(--color-text-secondary);
}
.nav-btn {
  width: 26px;
  height: 26px;
  padding: 0;
  border-radius: 6px;
  color: var(--color-text-secondary);
}
.nav-btn:hover:not(:disabled) {
  background: var(--color-hover);
  color: var(--color-text-primary);
}
.nav-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}
.editor-header h2 {
  font-size: 13px;
  font-weight: 600;
  margin: 0;
}
.close-btn {
  font-size: 20px;
  line-height: 1;
  color: var(--color-text-secondary);
  background: none;
  border: none;
  cursor: pointer;
  padding: 3px 5px;
}
.close-btn:hover { color: var(--color-text-primary); }

.editor-body {
  flex: 1;
  display: flex;
  gap: 0;
  min-height: 0;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.98), rgba(248, 249, 250, 0.9));
}
.editor-content {
  flex: 1;
  min-width: 0;
  padding: 22px 22px 24px;
  display: flex;
  flex-direction: column;
  gap: 22px;
  overflow-y: auto;
}
.content-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.meta-chip {
  font-size: 10px;
  color: var(--color-text-secondary);
  background: rgba(17, 24, 39, 0.04);
  border-radius: 999px;
  padding: 3px 8px;
}
.content-section {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.section-kicker {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--color-text-secondary);
}
.section-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: baseline;
}
.section-note {
  font-size: 11px;
  color: var(--color-text-secondary);
}
.subdued {
  padding-top: 10px;
  border-top: 1px solid var(--color-border);
}
.title-textarea {
  font-size: 24px;
  font-weight: 600;
  color: var(--color-text-primary);
  border: none;
  resize: none;
  padding: 0;
  line-height: 1.15;
  letter-spacing: -0.02em;
  background: transparent;
}
.title-textarea::placeholder {
  color: var(--color-text-secondary);
}
.description-input {
  font-size: 13px;
  color: var(--color-text-primary);
  resize: vertical;
  line-height: 1.55;
  border: none;
  padding: 0;
  background: transparent;
}
.description-input::placeholder {
  color: var(--color-text-secondary);
}
.context-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.context-card {
  border: 1px dashed var(--color-border);
  border-radius: 10px;
  padding: 14px;
  background: linear-gradient(180deg, rgba(250, 250, 250, 0.78), rgba(255, 255, 255, 0.9));
}
.context-card-title {
  font-size: 11px;
  font-weight: 700;
  color: var(--color-text-primary);
  margin-bottom: 6px;
}
.context-card p {
  margin: 0;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.55;
}
.editor-props {
  width: 228px;
  flex-shrink: 0;
  border-left: 1px solid var(--color-border);
  padding: 14px 14px 16px;
  display: flex;
  flex-direction: column;
  overflow-y: auto;
}
.props-card {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 12px;
  background: linear-gradient(180deg, rgba(250, 250, 250, 0.55), rgba(255, 255, 255, 0.9));
}
.props-title {
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  text-transform: uppercase;
  color: var(--color-text-secondary);
}
.prop-row {
  display: flex;
  flex-direction: column;
  gap: 5px;
}
.prop-label {
  font-size: 11px;
  color: var(--color-text-secondary);
  font-weight: 500;
}
.editor-props :deep(.prop-trigger) {
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  color: var(--color-text-primary);
  padding: 6px 8px;
  border-radius: 8px;
  text-align: left;
  font-size: 13px;
  cursor: pointer;
  transition: background 150ms ease;
}
.editor-props :deep(.prop-trigger:hover) {
  background: var(--color-hover);
}
.read-only .read-only-value {
  font-size: 13px;
  color: var(--color-text-secondary);
}
.editor-footer {
  padding: 12px 18px;
  border-top: 1px solid var(--color-border);
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.78);
}
.btn-cancel {
  padding: 6px 10px;
  border-radius: 8px;
  color: var(--color-text-secondary);
  font-size: 12px;
}
.btn-cancel:hover {
  background: var(--color-hover);
  color: var(--color-text-primary);
}
.btn-save {
  padding: 6px 10px;
  border-radius: 8px;
  background: var(--color-status-done);
  color: white;
  font-weight: 500;
  font-size: 12px;
}
.btn-save:hover:not(:disabled) { background: var(--color-accent-hover); }
.btn-save:disabled { opacity: 0.5; cursor: not-allowed; }

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
    border-top: 1px solid var(--color-border);
  }

  .context-grid {
    grid-template-columns: 1fr;
  }
}
</style>
