<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import type { Priority, Status, User } from '../types/domain'
import { projectApi } from '../services/api/project'
import { parseDateInputValue, todayDateInputValue } from '../utils/taskDate'
import { getPriorityLabel, getStatusLabel } from '../utils/enumLabels'
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
  CheckCircle,
  Circle,
  CircleDashed,
  CircleX,
  Copy,
  Eye,
  Loader2,
  Paperclip,
  User as UserIcon
} from 'lucide-vue-next'

const props = defineProps<{
  open: boolean
  defaultStatus?: Status
  /** Phase 7: 创建子任务时传入父任务数据库 id */
  parentNumericId?: number | null
}>()

const emit = defineEmits<{
  close: []
  created: [taskId: string]
}>()

const store = useTaskStore()
const projectStore = useProjectStore()
const { t } = useI18n()

const title = ref('')
const description = ref('')
const descriptionUploadState = ref({ hasPending: false, hasFailed: false })
const status = ref<Status>('todo')
const priority = ref<Priority>('medium')
const assigneeId = ref<string | number>('')
const plannedStartDate = ref('')
const dueDate = ref('')
const createMore = ref(false)
const isSaving = ref(false)
const userList = ref<User[]>([])
const descriptionEditorRef = ref<InstanceType<typeof TiptapEditor> | null>(null)

function focusDescription() {
  nextTick(() => descriptionEditorRef.value?.focus())
}

function onDescriptionUploadStateChange(state: { hasPending: boolean; hasFailed: boolean }) {
  descriptionUploadState.value = state
}

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

const assigneeOptions = computed<CustomSelectOption[]>(() => {
  const list: CustomSelectOption[] = [{ value: '', label: t('common.unassigned'), icon: UserIcon }]
  for (const user of userList.value) {
    const id = user?.id
    if (typeof id !== 'number' || !Number.isFinite(id)) continue
    list.push({ value: id, label: user.username ?? '', icon: UserIcon })
  }
  return list
})

function descriptionForSave(desc: string | undefined): string {
  const s = (desc ?? '').trim()
  if (!s) return ''
  const emptyListLine = /^\s*[-*+]\s*$|^\s*\d+\.\s*$/
  const onlyEmptyLists = s.split(/\n/).every((line) => !line.trim() || emptyListLine.test(line.trim()))
  return onlyEmptyLists ? '' : s
}

function resetForm() {
  title.value = ''
  description.value = ''
  status.value = props.defaultStatus ?? 'todo'
  priority.value = 'medium'
  assigneeId.value = ''
  plannedStartDate.value = todayDateInputValue()
  dueDate.value = ''
}

watch(
  () => props.open,
  (open) => {
    if (open) resetForm()
  },
  { immediate: true }
)

watch(
  () => props.defaultStatus,
  (value) => {
    if (props.open) status.value = value ?? 'todo'
  }
)

async function loadProjectMembers() {
  const projectId = projectStore.activeProjectId
  if (projectId == null) {
    userList.value = []
    return
  }
  try {
    userList.value = await projectApi.listMembers(projectId)
  } catch (error) {
    console.error('Failed to load project members:', error)
    userList.value = []
  }
}

onMounted(async () => {
  await loadProjectMembers()
})

watch(
  () => projectStore.activeProjectId,
  () => {
    loadProjectMembers()
  }
)

async function handleCreate() {
  if (!title.value.trim() || isSaving.value) return
  if (descriptionUploadState.value.hasPending || descriptionUploadState.value.hasFailed) return

  isSaving.value = true
  const plannedStartMs = parseDateInputValue(plannedStartDate.value)
  const dueDateMs = parseDateInputValue(dueDate.value)

  try {
    const rawAssignee = assigneeId.value
    const assigneeIdForApi =
      rawAssignee === '' || rawAssignee == null
        ? null
        : (() => {
            const n = Number(rawAssignee)
            return Number.isFinite(n) ? n : null
          })()
    const task = await store.createTask({
      title: title.value.trim(),
      description: descriptionForSave(description.value) || undefined,
      status: status.value,
      priority: priority.value,
      assigneeId: assigneeIdForApi,
      plannedStartDate: plannedStartMs,
      dueDate: dueDateMs,
      parentId: props.parentNumericId ?? undefined
    })

    if (createMore.value) {
      resetForm()
    } else {
      emit('created', task.id)
      emit('close')
    }
  } catch (error) {
    console.error('Failed to create task:', error)
  } finally {
    isSaving.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="composer-overlay"
      role="dialog"
      aria-modal="true"
      :aria-label="t('issueComposer.dialogLabel')"
      @click.self="emit('close')"
    >
      <div class="composer-panel">
        <div class="composer-header">
          <div class="composer-title">{{ t('issueComposer.title') }}</div>
          <button class="composer-close" type="button" :aria-label="t('common.close')" @click="emit('close')">
            ×
          </button>
        </div>

        <div class="composer-body">
          <section class="content-section content-section--title">
            <input
              v-model="title"
              type="text"
              class="composer-title-input"
              :placeholder="t('issueComposer.issueTitlePlaceholder')"
              autofocus
              @keydown.enter.exact.prevent="focusDescription"
            />
          </section>
          <section class="content-section description-section">
            <TiptapEditor
              ref="descriptionEditorRef"
              v-model="description"
              @upload-state-change="onDescriptionUploadStateChange"
              :placeholder="t('issueComposer.descriptionPlaceholder')"
              :min-height="64"
            />
          </section>
          <div class="content-actions">
            <button type="button" class="content-action-btn" :aria-label="t('common.attach')">
              <Paperclip class="icon-14" />
            </button>
          </div>

          <div class="composer-props">
            <CustomSelect
              id="composer-status"
              v-model="status"
              :options="statusOptions"
              :search-placeholder="t('boardView.filterByStatus')"
              search-shortcut-badge="S"
              :aria-label="t('common.status')"
              trigger-class="composer-trigger"
            />
            <CustomSelect
              id="composer-priority"
              v-model="priority"
              :options="priorityOptions"
              :aria-label="t('common.priority')"
              trigger-class="composer-trigger"
            />
            <CustomSelect
              id="composer-assignee"
              v-model="assigneeId"
              :options="assigneeOptions"
              :placeholder="t('common.assignee')"
              :aria-label="t('common.assignee')"
              trigger-class="composer-trigger"
            />
            <CustomDatePicker
              id="composer-planned-start"
              v-model="plannedStartDate"
              :placeholder="t('common.plannedStartDate')"
              :aria-label="t('common.plannedStartDate')"
              trigger-class="composer-trigger"
            />
            <CustomDatePicker
              id="composer-due-date"
              v-model="dueDate"
              :placeholder="t('common.dueDate')"
              :aria-label="t('common.dueDate')"
              trigger-class="composer-trigger"
            />
          </div>
        </div>

        <div class="composer-footer">
          <label class="composer-more">
            <input v-model="createMore" type="checkbox" />
            <span>{{ t('issueComposer.createMore') }}</span>
          </label>
          <button
            class="composer-submit"
            type="button"
            :disabled="!title.trim() || isSaving"
            @click="handleCreate"
          >
            {{ isSaving ? t('issueComposer.creatingIssue') : t('issueComposer.createIssue') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.composer-overlay {
  position: fixed;
  inset: 0;
  z-index: 220;
  background: rgba(17, 24, 39, 0.08);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding: 8vh 24px 24px;
}

.composer-panel {
  width: min(720px, 100%);
  background: var(--color-bg-main);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  box-shadow: var(--shadow-popover);
}

.composer-header,
.composer-footer {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 14px 18px;
}

.composer-header {
  border-bottom: 1px solid var(--color-border);
}

.composer-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-secondary);
}

.composer-close {
  width: 28px;
  height: 28px;
  padding: 0;
  border-radius: 6px;
  color: var(--color-text-secondary);
}

.composer-close:hover {
  background: var(--color-hover);
  color: var(--color-text-primary);
}

.composer-body {
  display: flex;
  flex-direction: column;
  gap: 0;
  padding: 18px;
}

/* 复刻详情页：标题 + 描述区 + 操作按钮 */
.content-section {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.content-section--title {
  margin-bottom: 0;
  padding-bottom: 2px;
}
.content-section--title .composer-title-input {
  font-size: 2rem;
  font-weight: 700;
  line-height: 1.18;
  letter-spacing: -0.035em;
}
.content-section.description-section {
  margin-top: 6px;
  padding-top: 0;
  min-height: 0;
}

.composer-title-input {
  width: 100%;
  border: none;
  padding: 0;
  background: transparent;
  font-size: 2rem;
  font-weight: 700;
  color: var(--color-text-primary);
  line-height: 1.18;
  letter-spacing: -0.035em;
}
.composer-title-input::placeholder {
  color: var(--color-text-muted);
}
.composer-title-input:focus {
  outline: none;
}

.description-section {
  position: relative;
}

.content-actions {
  display: flex;
  align-items: center;
  gap: 4px;
  margin-top: -4px;
  margin-bottom: 14px;
}
.content-action-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 6px;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  border-radius: var(--radius-sm, 6px);
  cursor: pointer;
  transition: color 0.15s, background 0.15s;
}
.content-action-btn:hover {
  color: var(--color-text-secondary);
  background: var(--color-bg-hover);
}
.content-action-btn .icon-14 {
  width: 14px;
  height: 14px;
  flex-shrink: 0;
}

.composer-props {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

:deep(.composer-trigger) {
  min-width: 122px;
  background: var(--color-hover);
  border: 1px solid var(--color-border);
  border-radius: 999px;
  min-height: 34px;
}

.composer-footer {
  border-top: 1px solid var(--color-border);
}

.composer-more {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--color-text-secondary);
  font-size: 13px;
}

.composer-submit {
  background: var(--color-accent);
  color: #fff;
  border-radius: 999px;
  padding: 8px 14px;
  font-size: 13px;
  font-weight: 600;
}

.composer-submit:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

@media (max-width: 720px) {
  .composer-overlay {
    padding: 16px;
    align-items: stretch;
  }

  .composer-panel {
    width: 100%;
  }

  .content-section--title .composer-title-input {
    font-size: 1.25rem;
  }
}
</style>
