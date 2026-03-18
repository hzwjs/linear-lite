<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { NodeViewWrapper } from '@tiptap/vue-3'
import type { NodeViewProps } from '@tiptap/core'
import { useI18n } from 'vue-i18n'
import { Loader2, RefreshCcw, Trash2, AlertCircle } from 'lucide-vue-next'

const props = defineProps<NodeViewProps>()
const { t } = useI18n()

const imageLoaded = ref(false)
const src = computed(() => String(props.node.attrs.src ?? ''))

watch(src, () => {
  imageLoaded.value = false
}, { immediate: true })

const uploadState = computed(() => props.node.attrs.uploadState as string | undefined)
const isUploading = computed(() => uploadState.value === 'uploading')
const isFailed = computed(() => uploadState.value === 'failed')
const errorMessage = computed(
  () => (props.node.attrs.errorMessage as string | undefined) ?? t('attachments.uploadFailed')
)

const showPlaceholder = computed(
  () => !!src.value && !imageLoaded.value && !isUploading.value && !isFailed.value
)

function onImageLoad() {
  imageLoaded.value = true
}

function retryUpload() {
  props.extension.options.onRetry?.(props.node.attrs.localId)
}

function removeImage() {
  props.extension.options.onRemove?.(props.node.attrs.localId)
}
</script>

<template>
  <NodeViewWrapper
    as="span"
    class="task-image-node"
    :class="{ 'task-image-node--loading': showPlaceholder }"
  >
    <span
      class="task-image-node__frame"
      :class="{
        'has-status': isUploading || isFailed,
        'is-loading': showPlaceholder,
        failed: isFailed
      }"
    >
      <img
        class="task-image-node__image"
        :src="src"
        :alt="String(props.node.attrs.alt ?? t('taskImage.altFallback'))"
        @load="onImageLoad"
        @error="onImageLoad"
      />
      <div v-if="isUploading || isFailed" class="task-image-node__status" :class="{ failed: isFailed }">
        <div class="task-image-node__status-main">
          <Loader2 v-if="isUploading" class="task-image-node__icon spin" />
          <AlertCircle v-else class="task-image-node__icon" />
          <span>{{ isUploading ? t('attachments.uploading') : errorMessage }}</span>
        </div>
        <div v-if="isFailed" class="task-image-node__actions">
          <button type="button" class="task-image-node__action" @click="retryUpload">
            <RefreshCcw class="task-image-node__action-icon" />
            {{ t('common.retry') }}
          </button>
          <button type="button" class="task-image-node__action" @click="removeImage">
            <Trash2 class="task-image-node__action-icon" />
            {{ t('common.remove') }}
          </button>
        </div>
      </div>
    </span>
  </NodeViewWrapper>
</template>

<style scoped>
.task-image-node {
  display: block;
  margin: 0.75rem 0;
}

/* 占位写在根上，首帧即有尺寸和背景，减少挂载前的空白 */
.task-image-node.task-image-node--loading {
  min-width: 200px;
  min-height: 120px;
  background: var(--color-bg-subtle, rgba(15, 23, 42, 0.06));
  animation: task-image-placeholder-pulse 1.5s ease-in-out infinite;
}

.task-image-node__frame {
  position: relative;
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  border-radius: 12px;
}

.task-image-node__frame.is-loading {
  display: block;
  width: 100%;
  min-width: 200px;
  min-height: 120px;
  background: var(--color-bg-subtle, rgba(15, 23, 42, 0.06));
  animation: task-image-placeholder-pulse 1.5s ease-in-out infinite;
}

@keyframes task-image-placeholder-pulse {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.7; }
}

.task-image-node__frame::after {
  content: '';
  position: absolute;
  inset: 0;
  opacity: 0;
  pointer-events: none;
  background: linear-gradient(
    to top,
    rgba(15, 23, 42, 0.22) 0%,
    rgba(15, 23, 42, 0.12) 18%,
    rgba(15, 23, 42, 0) 42%
  );
  transition: opacity 160ms ease;
}

.task-image-node__frame.has-status::after {
  opacity: 1;
}

.task-image-node__image {
  display: block;
  max-width: 100%;
  height: auto;
}

.task-image-node__status {
  position: absolute;
  left: 6px;
  bottom: 10px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  gap: 6px;
  padding: 5px 7px;
  width: fit-content;
  max-width: calc(100% - 12px);
  border-radius: 999px;
  background: color-mix(in srgb, rgba(15, 23, 42, 0.82) 72%, var(--color-bg-base));
  backdrop-filter: blur(8px);
  border: 1px solid color-mix(in srgb, var(--color-border-subtle) 80%, transparent);
  box-shadow: 0 6px 18px rgba(15, 23, 42, 0.18);
  color: var(--color-text-primary);
  font-size: 11px;
  line-height: 1.2;
  z-index: 1;
}

.task-image-node__status.failed {
  color: var(--color-danger, #b42318);
  border-color: color-mix(in srgb, var(--color-danger, #b42318) 20%, transparent);
  background: color-mix(in srgb, var(--color-danger, #b42318) 10%, var(--color-bg-base));
}

.task-image-node__status-main {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
  overflow: hidden;
}

.task-image-node__status-main span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-image-node__icon,
.task-image-node__action-icon {
  width: 12px;
  height: 12px;
  flex-shrink: 0;
}

.task-image-node__actions {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.task-image-node__action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 0;
  border: none;
  background: transparent;
  color: inherit;
  cursor: pointer;
  font-size: 11px;
}

.task-image-node__action:hover {
  text-decoration: underline;
}

.spin {
  animation: task-image-node-spin 1s linear infinite;
}

@keyframes task-image-node-spin {
  from { transform: rotate(0deg); }
  to { transform: rotate(360deg); }
}
</style>
