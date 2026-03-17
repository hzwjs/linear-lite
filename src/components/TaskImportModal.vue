<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { AlertCircle, CheckCircle2, FileSpreadsheet, Upload } from 'lucide-vue-next'
import { taskApi } from '../services/api/task'
import type { TaskImportResponse } from '../services/api/types'
import type { User } from '../types/domain'
import { useI18n } from 'vue-i18n'
import {
  autoMapTaskImportColumns,
  buildTaskImportPreview,
  getTaskImportTemplateCsv,
  parseTaskImportFile,
  TASK_IMPORT_FIELD_LABELS,
  TASK_IMPORT_OPTIONAL_FIELDS,
  TASK_IMPORT_REQUIRED_FIELDS,
  type ParsedTaskImportFile,
  type TaskImportColumnMapping,
  type TaskImportField
} from '../utils/taskImport'

const props = defineProps<{
  open: boolean
  projectId?: number | null
  users: User[]
}>()

const emit = defineEmits<{
  close: []
  imported: []
}>()

const { t } = useI18n()

type Step = 'upload' | 'mapping' | 'preview' | 'result'

const step = ref<Step>('upload')
const selectedFileName = ref('')
const parsedFile = ref<ParsedTaskImportFile | null>(null)
const mapping = ref<TaskImportColumnMapping>({})
const parseError = ref<string | null>(null)
const submitError = ref<string | null>(null)
const result = ref<TaskImportResponse | null>(null)
const isSubmitting = ref(false)
const fileInputRef = ref<HTMLInputElement | null>(null)

const fieldOrder: TaskImportField[] = [...TASK_IMPORT_REQUIRED_FIELDS, ...TASK_IMPORT_OPTIONAL_FIELDS]
const fieldLabels = computed<Record<TaskImportField, string>>(() => ({
  title: t('common.title'),
  description: t('fieldLabel.description'),
  status: t('common.status'),
  priority: t('common.priority'),
  assignee: t('common.assignee'),
  dueDate: t('common.dueDate'),
  importId: t('taskImportModal.fields.importId'),
  parentImportId: t('taskImportModal.fields.parentImportId')
}))

const preview = computed(() => {
  if (!parsedFile.value) return null
  return buildTaskImportPreview(parsedFile.value.rows, {
    mapping: mapping.value,
    users: props.users
  })
})

const canContinue = computed(() =>
  TASK_IMPORT_REQUIRED_FIELDS.every((field) => Boolean(mapping.value[field]))
)

const canSubmit = computed(() => {
  if (!preview.value) return false
  return (
    preview.value.fileErrors.length === 0 &&
    preview.value.rowErrors.length === 0 &&
    preview.value.rows.length > 0 &&
    props.projectId != null
  )
})

watch(
  () => props.open,
  (open) => {
    if (!open) resetState()
  }
)

function resetState() {
  step.value = 'upload'
  selectedFileName.value = ''
  parsedFile.value = null
  mapping.value = {}
  parseError.value = null
  submitError.value = null
  result.value = null
  isSubmitting.value = false
  if (fileInputRef.value) fileInputRef.value.value = ''
}

async function handleFileChange(event: Event) {
  const input = event.target as HTMLInputElement | null
  const file = input?.files?.[0]
  if (!file) return

  parseError.value = null
  submitError.value = null
  result.value = null

  try {
    parsedFile.value = await parseTaskImportFile(file)
    mapping.value = autoMapTaskImportColumns(parsedFile.value.headers)
    selectedFileName.value = file.name
    step.value = 'mapping'
  } catch (error) {
    parseError.value = error instanceof Error ? error.message : t('taskImportModal.errors.parseFailed')
  }
}

function setMapping(field: TaskImportField, value: string) {
  mapping.value = {
    ...mapping.value,
    [field]: value || undefined
  }
}

function getFieldLabel(field: TaskImportField) {
  return fieldLabels.value[field] ?? TASK_IMPORT_FIELD_LABELS[field]
}

function goBack() {
  if (step.value === 'preview') {
    step.value = 'mapping'
    return
  }
  if (step.value === 'mapping') {
    step.value = 'upload'
    return
  }
  emit('close')
}

function downloadTemplate() {
  const blob = new Blob([getTaskImportTemplateCsv()], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'linear-lite-task-import-template.csv'
  anchor.click()
  URL.revokeObjectURL(url)
}

async function submitImport() {
  if (!props.projectId || !preview.value || !canSubmit.value || isSubmitting.value) return

  isSubmitting.value = true
  submitError.value = null
  try {
    result.value = await taskApi.import({
      projectId: props.projectId,
      rows: preview.value.rows
    })
    step.value = 'result'
    emit('imported')
  } catch (error) {
    submitError.value = error instanceof Error ? error.message : t('taskImportModal.errors.importFailed')
  } finally {
    isSubmitting.value = false
  }
}
</script>

<template>
  <Teleport to="body">
    <div
      v-if="open"
      class="import-overlay"
      role="dialog"
      aria-modal="true"
      :aria-label="t('taskImportModal.ariaLabel')"
      @click.self="emit('close')"
    >
      <div class="import-panel">
        <div class="import-header">
          <div>
          <div class="import-eyebrow">{{ t('taskImportModal.title') }}</div>
          <h2>{{ t('taskImportModal.subtitle') }}</h2>
          </div>
          <button
            type="button"
            class="import-close"
            :aria-label="t('common.close')"
            @click="emit('close')"
          >
            ×
          </button>
        </div>

        <div class="import-steps">
          <span class="import-step" :class="{ active: step === 'upload' }">
            1. {{ t('taskImportModal.steps.upload') }}
          </span>
          <span class="import-step" :class="{ active: step === 'mapping' }">
            2. {{ t('taskImportModal.steps.mapping') }}
          </span>
          <span class="import-step" :class="{ active: step === 'preview' }">
            3. {{ t('taskImportModal.steps.preview') }}
          </span>
          <span class="import-step" :class="{ active: step === 'result' }">
            4. {{ t('taskImportModal.steps.result') }}
          </span>
        </div>

        <div v-if="step === 'upload'" class="import-body">
          <div class="import-upload-toolbar">
            <button type="button" class="import-link-btn" @click="downloadTemplate">
              {{ t('taskImportModal.downloadTemplate') }}
            </button>
          </div>
          <label class="import-dropzone">
            <input
              ref="fileInputRef"
              class="sr-only"
              type="file"
              accept=".csv,.xlsx"
              @change="handleFileChange"
            />
            <Upload class="icon-18" />
            <div class="import-dropzone-title">{{ t('taskImportModal.dropzone.title') }}</div>
            <div class="import-dropzone-copy">
              {{ t('taskImportModal.dropzone.copy') }}
            </div>
          </label>
          <div v-if="selectedFileName" class="import-file-pill">
            <FileSpreadsheet class="icon-14" />
            <span>{{ selectedFileName }}</span>
          </div>
          <p v-if="parseError" class="import-message import-message--error">
            <AlertCircle class="icon-14" />
            <span>{{ parseError }}</span>
          </p>
        </div>

        <div v-else-if="step === 'mapping' && parsedFile" class="import-body">
          <div class="import-meta">
            <div><strong>{{ t('taskImportModal.fileMeta.file') }}</strong> {{ selectedFileName }}</div>
            <div><strong>{{ t('taskImportModal.fileMeta.rows') }}</strong> {{ parsedFile.rows.length }}</div>
            <div>
              <strong>{{ t('taskImportModal.fileMeta.project') }}</strong>
              {{ projectId ?? t('taskImportModal.fileMeta.noProject') }}
            </div>
          </div>
          <div class="mapping-grid">
            <label v-for="field in fieldOrder" :key="field" class="mapping-row">
              <span class="mapping-label">
                {{ getFieldLabel(field) }}
                <small v-if="TASK_IMPORT_REQUIRED_FIELDS.includes(field)">{{ t('taskImportModal.mapping.required') }}</small>
              </span>
              <select
                class="mapping-select"
                :value="mapping[field] ?? ''"
                @change="setMapping(field, ($event.target as HTMLSelectElement).value)"
              >
                <option value="">{{ t('taskImportModal.mapping.unmapped') }}</option>
                <option v-for="header in parsedFile.headers.filter(Boolean)" :key="header" :value="header">
                  {{ header }}
                </option>
              </select>
            </label>
          </div>
        </div>

        <div v-else-if="step === 'preview' && preview" class="import-body">
          <div class="import-summary">
            <div class="summary-card">
              <span>{{ t('taskImportModal.preview.summary.total') }}</span>
              <strong>{{ preview.summary.totalCount }}</strong>
            </div>
            <div class="summary-card">
              <span>{{ t('taskImportModal.preview.summary.parents') }}</span>
              <strong>{{ preview.summary.parentCount }}</strong>
            </div>
            <div class="summary-card">
              <span>{{ t('taskImportModal.preview.summary.subtasks') }}</span>
              <strong>{{ preview.summary.subtaskCount }}</strong>
            </div>
          </div>

          <div v-if="preview.fileErrors.length || preview.rowErrors.length" class="preview-errors">
            <div v-for="error in preview.fileErrors" :key="error" class="import-message import-message--error">
              <AlertCircle class="icon-14" />
              <span>{{ error }}</span>
            </div>
            <div
              v-for="error in preview.rowErrors"
              :key="`${error.lineNumber}-${error.field}-${error.message}`"
              class="import-message import-message--error"
            >
              <AlertCircle class="icon-14" />
              <span>
                {{
                  t('taskImportModal.preview.errors.lineMessage', {
                    lineNumber: error.lineNumber,
                    field: getFieldLabel(error.field as TaskImportField),
                    message: error.message
                  })
                }}
              </span>
            </div>
          </div>

          <div v-else class="preview-table-wrap">
            <table class="preview-table">
              <thead>
                <tr>
                  <th>{{ t('common.title') }}</th>
                  <th>{{ t('common.status') }}</th>
                  <th>{{ t('common.priority') }}</th>
                  <th>{{ t('common.assignee') }}</th>
                  <th>{{ t('taskImportModal.preview.table.parent') }}</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="row in preview.rows.slice(0, 12)" :key="row.importId">
                  <td>{{ row.title }}</td>
                  <td>{{ row.status }}</td>
                  <td>{{ row.priority }}</td>
                  <td>{{ row.assigneeId ?? t('common.unassigned') }}</td>
                  <td>{{ row.parentImportId ?? t('taskImportModal.preview.table.topLevel') }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <p v-if="submitError" class="import-message import-message--error">
            <AlertCircle class="icon-14" />
            <span>{{ submitError }}</span>
          </p>
        </div>

        <div v-else-if="step === 'result' && result" class="import-body">
          <p class="import-message import-message--success">
            <CheckCircle2 class="icon-14" />
            <span>{{ t('taskImportModal.result.success', { count: result.createdCount }) }}</span>
          </p>
          <div class="import-summary">
            <div class="summary-card">
              <span>{{ t('taskImportModal.result.summary.parents') }}</span>
              <strong>{{ result.parentCount }}</strong>
            </div>
            <div class="summary-card">
              <span>{{ t('taskImportModal.result.summary.subtasks') }}</span>
              <strong>{{ result.subtaskCount }}</strong>
            </div>
            <div class="summary-card">
              <span>{{ t('taskImportModal.result.summary.created') }}</span>
              <strong>{{ result.createdCount }}</strong>
            </div>
          </div>
          <div class="result-keys">
            <span v-for="taskKey in result.taskKeys.slice(0, 12)" :key="taskKey" class="result-key">
              {{ taskKey }}
            </span>
          </div>
        </div>

        <div class="import-footer">
          <button
            v-if="step !== 'upload' && step !== 'result'"
            type="button"
            class="import-btn import-btn--ghost"
            @click="goBack"
          >
            {{ t('taskImportModal.footer.back') }}
          </button>
          <button
            v-if="step === 'mapping'"
            type="button"
            class="import-btn"
            :disabled="!canContinue"
            @click="step = 'preview'"
          >
            {{ t('taskImportModal.footer.review') }}
          </button>
          <button
            v-else-if="step === 'preview'"
            type="button"
            class="import-btn"
            :disabled="!canSubmit || isSubmitting"
            @click="submitImport"
          >
            {{ isSubmitting ? t('taskImportModal.footer.importing') : t('taskImportModal.footer.importIssues') }}
          </button>
          <button
            v-else-if="step === 'result'"
            type="button"
            class="import-btn"
            @click="emit('close')"
          >
            {{ t('taskImportModal.footer.done') }}
          </button>
        </div>
      </div>
    </div>
  </Teleport>
</template>

<style scoped>
.sr-only {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.import-overlay {
  position: fixed;
  inset: 0;
  z-index: 1400;
  background: rgba(15, 23, 42, 0.36);
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
}
.import-panel {
  width: min(920px, 100%);
  max-height: 88vh;
  overflow: auto;
  background: var(--color-bg-base);
  border: 1px solid var(--color-border-subtle);
  border-radius: 18px;
  box-shadow: var(--shadow-popover);
}
.import-header {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 24px 12px;
}
.import-header h2 {
  margin: 4px 0 0;
  font-size: 24px;
}
.import-eyebrow {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  text-transform: uppercase;
  letter-spacing: 0.08em;
}
.import-close {
  width: 32px;
  height: 32px;
  border-radius: 999px;
  background: var(--color-bg-muted);
}
.import-steps {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
  padding: 0 24px 16px;
}
.import-step {
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--color-bg-muted);
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}
.import-step.active {
  background: var(--color-accent-muted);
  color: var(--color-accent);
}
.import-body {
  padding: 0 24px 20px;
}
.import-upload-toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12px;
}
.import-link-btn {
  padding: 0;
  background: transparent;
  color: var(--color-accent);
  font-size: var(--font-size-caption);
  font-weight: var(--font-weight-medium);
}
.import-link-btn:hover {
  text-decoration: underline;
}
.import-dropzone {
  min-height: 220px;
  border-radius: 16px;
  border: 1px dashed var(--color-border);
  background: linear-gradient(135deg, var(--color-bg-subtle), var(--color-bg-base));
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 24px;
  text-align: center;
  cursor: pointer;
}
.import-dropzone-title {
  font-size: 18px;
  font-weight: var(--font-weight-semibold);
}
.import-dropzone-copy {
  max-width: 560px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}
.import-file-pill,
.import-message {
  margin-top: 14px;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border-radius: 10px;
  font-size: var(--font-size-caption);
}
.import-file-pill {
  background: var(--color-bg-muted);
}
.import-message--error {
  background: rgba(220, 38, 38, 0.08);
  color: #b91c1c;
}
.import-message--success {
  background: rgba(22, 163, 74, 0.12);
  color: #15803d;
}
.import-meta {
  display: flex;
  gap: 20px;
  margin-bottom: 16px;
  flex-wrap: wrap;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}
.mapping-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(260px, 1fr));
  gap: 14px;
}
.mapping-row {
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.mapping-label {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
}
.mapping-label small {
  color: var(--color-accent);
}
.mapping-select {
  min-height: 38px;
  border-radius: 10px;
  border: 1px solid var(--color-border);
  background: var(--color-bg-base);
  color: var(--color-text-primary);
  padding: 0 12px;
}
.import-summary {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}
.summary-card {
  padding: 14px;
  border-radius: 14px;
  background: var(--color-bg-subtle);
  border: 1px solid var(--color-border-subtle);
}
.summary-card span {
  display: block;
  margin-bottom: 6px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-xs);
}
.summary-card strong {
  font-size: 22px;
}
.preview-errors {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.preview-table-wrap {
  overflow: auto;
  border: 1px solid var(--color-border-subtle);
  border-radius: 14px;
}
.preview-table {
  width: 100%;
  border-collapse: collapse;
}
.preview-table th,
.preview-table td {
  padding: 10px 12px;
  text-align: left;
  border-bottom: 1px solid var(--color-border-subtle);
  font-size: var(--font-size-caption);
}
.result-keys {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.result-key {
  padding: 6px 10px;
  border-radius: 999px;
  background: var(--color-bg-muted);
  font-size: var(--font-size-caption);
}
.import-footer {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 16px 24px 24px;
}
.import-btn {
  min-width: 128px;
  min-height: 38px;
  border-radius: 10px;
  background: var(--color-accent);
  color: white;
  padding: 0 14px;
}
.import-btn--ghost {
  background: var(--color-bg-muted);
  color: var(--color-text-primary);
}
.import-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
.icon-14 {
  width: 14px;
  height: 14px;
}
.icon-18 {
  width: 18px;
  height: 18px;
}
@media (max-width: 720px) {
  .import-overlay {
    padding: 12px;
  }
  .import-summary {
    grid-template-columns: 1fr;
  }
}
</style>
