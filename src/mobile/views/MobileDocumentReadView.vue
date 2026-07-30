<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ChevronLeft, FileText, Loader2, Star } from 'lucide-vue-next'
import StructuredDocumentEditor from '../../components/StructuredDocumentEditor.vue'
import { documentApi } from '../../services/api/documents'
import { useProjectStore } from '../../store/projectStore'
import type { ProjectDocument } from '../../types/document'
import MobileEmptyState from '../components/MobileEmptyState.vue'

const props = defineProps<{ projectId: number; documentId: number }>()
const emit = defineEmits<{ back: [] }>()
const { t } = useI18n()
const projectStore = useProjectStore()
const document = ref<ProjectDocument | null>(null)
const loading = ref(true)
const loadError = ref('')
const favoritePending = ref(false)
let loadSequence = 0

const projectName = computed(() => (
  projectStore.projects.find((project) => project.id === props.projectId)?.name
))

async function loadDocument() {
  const requestId = ++loadSequence
  const expectedProjectId = props.projectId
  const expectedDocumentId = props.documentId
  loading.value = true
  loadError.value = ''
  document.value = null
  try {
    const loaded = await documentApi.get(expectedDocumentId)
    // 路由快速切换时，过期请求不得覆盖新文档。
    if (requestId !== loadSequence) return
    // 路由中的项目是权限与导航上下文，禁止展示其他项目返回的文档。
    if (loaded.projectId !== expectedProjectId) {
      loadError.value = t('documents.mobile.projectMismatch')
      return
    }
    document.value = loaded
  } catch (cause) {
    if (requestId !== loadSequence) return
    loadError.value = cause instanceof Error ? cause.message : t('documents.mobile.loadFailed')
  } finally {
    if (requestId === loadSequence) loading.value = false
  }
}

async function toggleFavorite() {
  if (document.value == null || favoritePending.value) return
  const current = document.value
  favoritePending.value = true
  try {
    document.value = current.favorited
      ? await documentApi.removeFavorite(current.id)
      : await documentApi.addFavorite(current.id)
  } finally {
    favoritePending.value = false
  }
}

watch(() => [props.projectId, props.documentId], loadDocument, { immediate: true })
</script>

<template>
  <main class="mobile-fullscreen mobile-document-view" :aria-busy="loading">
    <header class="mobile-navigation-bar">
      <button type="button" class="mobile-nav-back" :aria-label="t('documents.mobile.back')" @click="emit('back')">
        <ChevronLeft :size="24" />
      </button>
      <span class="mobile-document-nav-title">{{ t('documents.mobile.title') }}</span>
      <button
        v-if="document"
        type="button"
        class="mobile-document-favorite"
        :class="{ 'is-active': document.favorited }"
        :disabled="favoritePending"
        :aria-label="document.favorited ? t('documents.removeFavorite') : t('documents.addFavorite')"
        :aria-pressed="document.favorited"
        @click="toggleFavorite"
      >
        <Loader2 v-if="favoritePending" :size="19" class="mobile-spinner" aria-hidden="true" />
        <Star v-else :size="20" aria-hidden="true" />
      </button>
      <span v-else class="mobile-document-readonly-label">{{ t('documents.mobile.readonly') }}</span>
    </header>

    <div v-if="loading" class="mobile-detail-loading" role="status" aria-live="polite">
      <Loader2 :size="24" class="mobile-spinner" aria-hidden="true" />
      {{ t('documents.mobile.loading') }}
    </div>
    <div v-else-if="loadError" role="alert">
      <MobileEmptyState
        :title="t('documents.mobile.loadFailed')"
        :description="loadError"
        :action="t('common.retry')"
        @action="loadDocument"
      />
    </div>

    <article v-else-if="document" class="mobile-document-content">
      <div class="mobile-document-heading">
        <div class="mobile-document-context">
          <FileText :size="15" aria-hidden="true" />
          <span v-if="projectName">{{ projectName }}</span>
          <span v-if="projectName" aria-hidden="true">/</span>
          <span>{{ t('documents.title') }}</span>
        </div>
        <h1>{{ document.title }}</h1>
      </div>
      <section class="mobile-document-body" :aria-label="t('documents.mobile.bodyLabel')">
        <StructuredDocumentEditor :model-value="document.content" readonly />
      </section>
    </article>
  </main>
</template>
