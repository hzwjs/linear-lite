<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { documentApi } from '../../services/api/documents'

const props = defineProps<{ projectId: number; content: string; title: string }>()
const { t } = useI18n()
const source = ref('')
const loading = ref(false)
const error = ref(false)
let sequence = 0

async function render() {
  const request = ++sequence
  source.value = ''
  loading.value = true
  error.value = false
  try {
    const result = await documentApi.renderHtml(props.projectId, props.content)
    if (request === sequence) source.value = result
  } catch {
    if (request === sequence) error.value = true
  } finally {
    if (request === sequence) loading.value = false
  }
}

watch(() => [props.projectId, props.content], render, { immediate: true })
onBeforeUnmount(() => { sequence += 1 })
</script>

<template>
  <div class="html-preview" :aria-busy="loading">
    <div v-if="loading" class="html-preview__state" role="status">{{ t('documents.html.loadingPreview') }}</div>
    <div v-else-if="error" class="html-preview__state" role="alert">
      <p>{{ t('documents.html.previewFailed') }}</p>
      <button type="button" @click="render">{{ t('common.retry') }}</button>
    </div>
    <!-- 不授予 allow-same-origin：文档脚本与主站登录状态、DOM、存储隔离。 -->
    <iframe v-else :srcdoc="source" :title="title" sandbox="allow-scripts" referrerpolicy="no-referrer" />
  </div>
</template>

<style scoped>
.html-preview { display: flex; min-height: 0; width: 100%; flex: 1; }
.html-preview iframe { width: 100%; height: 100%; border: 0; background: var(--color-bg-base); }
.html-preview__state { display: flex; flex: 1; flex-direction: column; align-items: center; justify-content: center; color: var(--color-text-secondary); }
.html-preview__state button { border: 1px solid var(--color-border); border-radius: var(--radius-sm); }
</style>
