<script setup lang="ts">
import { computed, nextTick, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTaskStore } from '../store/taskStore'
import { useProjectStore } from '../store/projectStore'
import type { Priority, Status, User } from '../types/domain'
import { projectApi } from '../services/api/project'
import { parseDateInputValue, todayDateInputValue } from '../utils/taskDate'
import { randomClientId } from '../utils/clientId'
import { attachmentsApi } from '../services/api/attachments'
import { toApiError } from '../services/api/index'
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
const composerAttachmentInputRef = ref<HTMLInputElement | null>(null)
const MAX_ATTACHMENT_SIZE = 10 * 1024 * 1024

type ComposerAttachmentItem = {
  localId: string
  file: File
  progress: number
  phase: 'queued' | 'uploading' | 'done' | 'error'
  errorMessage?: string
}
const composerAttachmentQueue = ref<ComposerAttachmentItem[]>([])
const composerAttachmentPickError = ref('')

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
  composerAttachmentQueue.value = []
  composerAttachmentPickError.value = ''
}

function openComposerAttachmentInput() {
  if (isSaving.value) return
  composerAttachmentInputRef.value?.click()
}

function onComposerAttachmentInputChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = input.files
  if (!files?.length) return
  composerAttachmentPickError.value = ''
  for (let i = 0; i < files.length; i++) {
    const file = files[i]
    if (!file) continue
    if (file.size > MAX_ATTACHMENT_SIZE) {
      composerAttachmentPickError.value = `"${file.name}" ${t('attachments.fileTooLargeSkipped', { size: '10MB' })}`
      continue
    }
    composerAttachmentQueue.value = [
      ...composerAttachmentQueue.value,
      { localId: randomClientId(), file, progress: 0, phase: 'queued' }
    ]
  }
  input.value = ''
}

function removeComposerQueuedAttachment(localId: string) {
  composerAttachmentQueue.value = composerAttachmentQueue.value.filter(
    (x) => !(x.localId === localId && x.phase === 'queued')
  )
}

function formatComposerAttachmentSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

async function uploadQueuedAttachments(taskKey: string): Promise<void> {
  const queued = composerAttachmentQueue.value.filter((x) => x.phase === 'queued')
  for (const item of queued) {
    const { localId, file } = item
    composerAttachmentQueue.value = composerAttachmentQueue.value.map((x) =>
      x.localId === localId ? { ...x, phase: 'uploading', progress: 0 } : x
    )
    try {
      await attachmentsApi.upload(taskKey, file, (pct) => {
        composerAttachmentQueue.value = composerAttachmentQueue.value.map((x) =>
          x.localId === localId ? { ...x, progress: pct } : x
        )
      })
      composerAttachmentQueue.value = composerAttachmentQueue.value.map((x) =>
        x.localId === localId ? { ...x, phase: 'done', progress: 100 } : x
      )
    } catch (e) {
      const msg = toApiError(e).message || t('attachments.uploadFailed')
      composerAttachmentQueue.value = composerAttachmentQueue.value.map((x) =>
        x.localId === localId ? { ...x, phase: 'error', errorMessage: msg } : x
      )
      throw e
    }
  }
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

    let attachmentErr: string | null = null
    try {
      if (composerAttachmentQueue.value.some((x) => x.phase === 'queued')) {
        await uploadQueuedAttachments(task.id)
      }
    } catch (e) {
      attachmentErr = toApiError(e).message || t('attachments.uploadFailed')
    } finally {
      composerAttachmentQueue.value = []
    }
    if (attachmentErr) {
      alert(attachmentErr)
    }

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
          <input
            ref="composerAttachmentInputRef"
            type="file"
            class="composer-attachment-input"
            multiple
            tabindex="-1"
            @change="onComposerAttachmentInputChange"
          />
          <div class="content-actions">
            <button
              type="button"
              class="content-action-btn"
              :disabled="isSaving"
              :aria-label="t('common.attach')"
              @click="openComposerAttachmentInput"
            >
              <Paperclip class="icon-14" />
            </button>
          </div>
          <p v-if="composerAttachmentPickError" class="composer-attachment-banner composer-attachment-banner--error">
            {{ composerAttachmentPickError }}
          </p>
          <ul v-if="composerAttachmentQueue.length" class="composer-attachment-list" :aria-label="t('taskEditor.attachments')">
            <li
              v-for="item in composerAttachmentQueue"
              :key="item.localId"
              class="composer-attachment-row"
            >
              <span class="composer-attachment-name" :title="item.file.name">{{ item.file.name }}</span>
              <span class="composer-attachment-meta">{{ formatComposerAttachmentSize(item.file.size) }}</span>
              <template v-if="item.phase === 'queued'">
                <button
                  type="button"
                  class="composer-attachment-remove"
                  :aria-label="t('common.close')"
                  @click="removeComposerQueuedAttachment(item.localId)"
                >
                  ×
                </button>
              </template>
              <span v-else-if="item.phase === 'uploading'" class="composer-attachment-status">
                {{ t('attachments.uploading') }} {{ item.progress }}%
              </span>
              <span v-else-if="item.phase === 'error'" class="composer-attachment-status composer-attachment-status--error">
                {{ item.errorMessage }}
              </span>
            </li>
          </ul>

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

.content-action-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.composer-attachment-input {
  position: absolute;
  width: 0;
  height: 0;
  opacity: 0;
  pointer-events: none;
}

.composer-attachment-banner {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.4;
}
.composer-attachment-banner--error {
  color: var(--color-danger, #c53030);
}

.composer-attachment-list {
  list-style: none;
  margin: 0 0 14px;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.composer-attachment-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.composer-attachment-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.composer-attachment-meta {
  flex-shrink: 0;
  color: var(--color-text-muted);
}
.composer-attachment-remove {
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  border-radius: 4px;
  cursor: pointer;
  font-size: 16px;
  line-height: 1;
}
.composer-attachment-remove:hover {
  background: var(--color-bg-hover);
  color: var(--color-text-secondary);
}
.composer-attachment-status {
  flex-shrink: 0;
  font-size: 11px;
  color: var(--color-text-muted);
}
.composer-attachment-status--error {
  color: var(--color-danger, #c53030);
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
