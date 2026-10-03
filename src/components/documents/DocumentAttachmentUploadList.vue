<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Paperclip, X, RotateCcw, LoaderCircle } from 'lucide-vue-next'
import type { DocumentAttachmentUploadJob } from './useDocumentAttachmentUploads'

defineProps<{ jobs: DocumentAttachmentUploadJob[] }>()
const emit = defineEmits<{ retry: [job: DocumentAttachmentUploadJob]; cancel: [job: DocumentAttachmentUploadJob] }>()
const { t } = useI18n()
const megabytes = (bytes: number) => `${(bytes / 1024 / 1024).toFixed(1)} MB`
const percent = (job: DocumentAttachmentUploadJob) => Math.min(99, Math.floor(job.loaded / job.file.size * 100))
</script>

<template>
  <div v-if="jobs.length" class="document-upload-list" aria-live="polite">
    <div v-for="job in jobs" :key="job.id" class="document-upload" :class="{ 'document-upload--failed': job.state === 'failed' }">
      <LoaderCircle v-if="job.state === 'processing'" class="document-upload__icon document-upload__spinner" aria-hidden="true" />
      <Paperclip v-else class="document-upload__icon" aria-hidden="true" />
      <div class="document-upload__body">
        <div class="document-upload__name" :title="job.file.name">{{ job.file.name }}</div>
        <div class="document-upload__status">
          <template v-if="job.state === 'failed'">{{ t('documents.uploadFailed') }} · {{ job.error }}</template>
          <template v-else-if="job.state === 'processing'">{{ t('documents.uploadProcessing') }}</template>
          <template v-else-if="job.state === 'preparing'">{{ t('documents.uploadPreparing') }}</template>
          <template v-else>{{ job.state === 'retrying' ? t('documents.uploadRetrying') : `${percent(job)}%` }} · {{ megabytes(job.loaded) }} / {{ megabytes(job.file.size) }}</template>
        </div>
        <progress v-if="job.state !== 'failed'" class="document-upload__progress" :value="job.state === 'processing' ? undefined : percent(job)" max="100" :aria-label="t('documents.uploadProgress')" />
      </div>
      <button v-if="job.state === 'failed'" type="button" class="document-upload__action" :aria-label="t('documents.uploadRetry')" :title="t('documents.uploadRetry')" @click="emit('retry', job)"><RotateCcw aria-hidden="true" /></button>
      <button v-if="job.state !== 'processing'" type="button" class="document-upload__action" :aria-label="t('documents.uploadCancel')" :title="t('documents.uploadCancel')" @click="emit('cancel', job)"><X aria-hidden="true" /></button>
    </div>
  </div>
</template>

<style scoped>
.document-upload-list {
  --upload-card-gap: 10px;
  --upload-card-padding: 12px;
  --upload-icon-size: 18px;
  --upload-action-size: 36px;
  --upload-progress-height: 4px;
  display: grid;
  gap: var(--upload-card-gap);
  margin-block: var(--upload-card-gap);
}
.document-upload { display: flex; align-items: center; gap: var(--upload-card-gap); padding: var(--upload-card-padding); border: 1px solid var(--color-border-subtle); border-radius: var(--radius-md); background: var(--color-bg-base); }
.document-upload__icon { flex: none; width: var(--upload-icon-size); height: var(--upload-icon-size); color: var(--color-text-muted); }
.document-upload__body { flex: 1; min-width: 0; }
.document-upload__name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: var(--font-size-sm); color: var(--color-text-primary); }
.document-upload__status { margin-block: calc(var(--upload-card-gap) / 2); font-size: var(--font-size-xs); color: var(--color-text-secondary); overflow-wrap: anywhere; }
.document-upload__progress { display: block; width: 100%; height: var(--upload-progress-height); border: 0; border-radius: var(--radius-sm); accent-color: var(--color-accent); }
.document-upload__progress::-webkit-progress-bar { background: var(--color-bg-muted); border-radius: var(--radius-sm); }
.document-upload__progress::-webkit-progress-value { background: var(--color-accent); border-radius: var(--radius-sm); }
.document-upload__action { flex: none; display: grid; place-items: center; width: var(--upload-action-size); height: var(--upload-action-size); padding: 0; border: 0; border-radius: var(--radius-sm); color: var(--color-text-muted); background: transparent; cursor: pointer; }
.document-upload__action svg { width: var(--upload-icon-size); height: var(--upload-icon-size); }
.document-upload__action:hover { color: var(--color-text-primary); background: var(--color-bg-hover); }
.document-upload__action:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
.document-upload--failed { border-color: var(--color-danger); }
.document-upload--failed .document-upload__status { color: var(--color-danger); }
.document-upload__spinner { animation: upload-spin 1s linear infinite; }
@keyframes upload-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .document-upload__spinner { animation: none; } }
@media (max-width: 600px) { .document-upload-list { --upload-action-size: 44px; --upload-card-padding: 8px; } .document-upload__icon:not(.document-upload__spinner) { display: none; } }
</style>
