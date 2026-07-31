<script setup lang="ts">
import { computed } from 'vue'
import { FileText, Loader2, SearchX, TriangleAlert } from 'lucide-vue-next'
import { useI18n } from 'vue-i18n'
import type { ProjectDocumentTreeNode } from '../../types/document'
import type { ProjectContentSearchResult } from '../../types/search'

const props = defineProps<{
  query: string
  results: ProjectContentSearchResult[]
  treeNodes: ProjectDocumentTreeNode[]
  loading: boolean
  error: boolean
}>()

const emit = defineEmits<{
  select: [result: ProjectContentSearchResult]
  retry: []
}>()

const { t } = useI18n()

interface HighlightPart {
  text: string
  highlighted: boolean
}

function highlightParts(text: string): HighlightPart[] {
  const query = props.query.trim()
  if (!query) return [{ text, highlighted: false }]
  const escaped = query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const matcher = new RegExp(`(${escaped})`, 'gi')
  return text.split(matcher).filter(Boolean).map((part) => ({
    text: part,
    highlighted: part.toLocaleLowerCase() === query.toLocaleLowerCase()
  }))
}

const displayResults = computed(() => {
  const nodesById = new Map(props.treeNodes.map((node) => [node.id, node]))
  return props.results.map((result) => {
    const path: string[] = [t('documents.title')]
    let node = nodesById.get(Number(result.resourceId))
    const ancestors: string[] = []
    // 路径只读取当前文档树，确保搜索结果与页面正在展示的项目层级一致。
    while (node?.parentDocumentId != null) {
      node = nodesById.get(node.parentDocumentId)
      if (node) ancestors.unshift(node.title)
    }
    return { result, path: [...path, ...ancestors].join(' / ') }
  })
})
</script>

<template>
  <div class="document-search-results" aria-live="polite">
    <div v-if="loading" class="document-search-results__state">
      <Loader2 class="spin" aria-hidden="true" />
      <span>{{ t('documents.searchLoading') }}</span>
    </div>
    <div v-else-if="error" class="document-search-results__state" role="alert">
      <TriangleAlert aria-hidden="true" />
      <span>{{ t('documents.searchFailed') }}</span>
      <button type="button" @click="emit('retry')">{{ t('common.retry') }}</button>
    </div>
    <div v-else-if="displayResults.length === 0" class="document-search-results__state">
      <SearchX aria-hidden="true" />
      <span>{{ t('documents.noSearchResults') }}</span>
    </div>
    <ul v-else :aria-label="t('documents.searchResultsLabel')">
      <li v-for="item in displayResults" :key="item.result.resourceId">
        <button type="button" @click="emit('select', item.result)">
          <FileText class="document-search-results__icon" aria-hidden="true" />
          <span class="document-search-results__content">
            <span class="document-search-results__path">{{ item.path }}</span>
            <strong class="document-search-results__title">
              <template v-for="(part, index) in highlightParts(item.result.title)" :key="index">
                <mark v-if="part.highlighted">{{ part.text }}</mark><template v-else>{{ part.text }}</template>
              </template>
            </strong>
            <span class="document-search-results__excerpt">
              <template v-for="(part, index) in highlightParts(item.result.excerpt)" :key="index">
                <mark v-if="part.highlighted">{{ part.text }}</mark><template v-else>{{ part.text }}</template>
              </template>
            </span>
          </span>
        </button>
      </li>
    </ul>
  </div>
</template>

<style scoped>
.document-search-results { min-height: 0; }
.document-search-results ul { margin: 0; padding: 0; list-style: none; }
.document-search-results li + li { margin-top: 2px; }
.document-search-results li button {
  display: grid;
  width: 100%;
  grid-template-columns: 20px minmax(0, 1fr);
  gap: 8px;
  padding: 9px 10px;
  border-radius: var(--radius-sm);
  color: var(--color-text-primary);
  text-align: left;
}
.document-search-results li button:hover { background: var(--color-bg-hover); }
.document-search-results li button:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: -2px; }
.document-search-results__icon { width: 15px; height: 15px; margin-top: 18px; color: var(--color-text-muted); }
.document-search-results__content { display: grid; min-width: 0; gap: 3px; }
.document-search-results__path {
  overflow: hidden;
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.document-search-results__title {
  overflow: hidden;
  font-size: var(--font-size-body);
  font-weight: var(--font-weight-semibold);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.document-search-results__excerpt {
  display: -webkit-box;
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  line-height: 1.45;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}
.document-search-results mark { border-radius: 2px; color: inherit; background: var(--color-accent-muted); }
.document-search-results__state {
  display: flex;
  min-height: 150px;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 18px;
  color: var(--color-text-muted);
  text-align: center;
}
.document-search-results__state > svg { width: 18px; height: 18px; }
.document-search-results__state button { border: 1px solid var(--color-border); border-radius: var(--radius-sm); background: var(--color-bg-base); }
.spin { animation: document-search-spin 800ms linear infinite; }
@keyframes document-search-spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
