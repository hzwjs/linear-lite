<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ArrowUpRight, FileText, ListTodo, LoaderCircle, Search, X } from 'lucide-vue-next'
import { searchApi } from '../services/api/search'
import type { ProjectContentSearchResult } from '../types/search'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; select: [result: ProjectContentSearchResult] }>()

const { t } = useI18n()
const input = ref<HTMLInputElement | null>(null)
const dialog = ref<HTMLElement | null>(null)
const query = ref('')
const submittedQuery = ref('')
const results = ref<ProjectContentSearchResult[]>([])
const loading = ref(false)
const error = ref(false)
const activeIndex = ref(-1)
let requestId = 0
let previousActiveElement: HTMLElement | null = null

const activeOptionId = computed(() => {
  const result = results.value[activeIndex.value]
  return result == null ? undefined : `global-search-${result.contentType}-${result.resourceId}`
})

function resetDraftResults() {
  requestId += 1
  submittedQuery.value = ''
  results.value = []
  activeIndex.value = -1
  error.value = false
  loading.value = false
}

async function search(value: string) {
  const normalized = value.trim()
  results.value = []
  activeIndex.value = -1
  error.value = false
  submittedQuery.value = normalized
  if (!normalized) { loading.value = false; return }
  const currentRequest = ++requestId
  loading.value = true
  try {
    const nextResults = await searchApi.search(normalized)
    if (currentRequest === requestId) {
      results.value = nextResults
      activeIndex.value = nextResults.length > 0 ? 0 : -1
    }
  } catch {
    if (currentRequest === requestId) error.value = true
  } finally {
    if (currentRequest === requestId) loading.value = false
  }
}

watch(() => props.open, (open) => {
  if (open) {
    previousActiveElement = document.activeElement instanceof HTMLElement ? document.activeElement : null
    nextTick(() => input.value?.focus())
    return
  }
  nextTick(() => {
    previousActiveElement?.focus()
    previousActiveElement = null
  })
})
watch(query, resetDraftResults)

function onFocusIn(event: FocusEvent) {
  if (!props.open || dialog.value?.contains(event.target as Node)) return
  nextTick(() => input.value?.focus())
}

onMounted(() => document.addEventListener('focusin', onFocusIn))
onBeforeUnmount(() => {
  document.removeEventListener('focusin', onFocusIn)
})

function close() {
  requestId += 1
  query.value = ''
  submittedQuery.value = ''
  results.value = []
  error.value = false
  activeIndex.value = -1
  emit('close')
}

function selectActive() {
  const result = results.value[activeIndex.value]
  if (result) emit('select', result)
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    activeIndex.value = results.value.length ? (activeIndex.value + 1) % results.value.length : -1
  } else if (event.key === 'ArrowUp') {
    event.preventDefault()
    activeIndex.value = results.value.length ? (activeIndex.value - 1 + results.value.length) % results.value.length : -1
  } else if (event.key === 'Enter') {
    event.preventDefault()
    if (loading.value) return
    // 首次 Enter 提交输入；已有结果时 Enter 打开当前选中项。
    if (activeIndex.value >= 0) selectActive()
    else void search(query.value)
  }
}
</script>

<template>
  <div v-if="open" class="global-search-overlay" @click.self="close">
    <section ref="dialog" class="global-search-dialog" role="dialog" aria-modal="true" aria-labelledby="global-search-title" @keydown.esc.stop.prevent="close" @keydown="onKeydown">
      <header class="global-search-header">
        <h2 id="global-search-title" class="sr-only">{{ t('globalSearch.title') }}</h2>
        <div class="global-search-input-wrap">
          <Search :size="20" stroke-width="2" aria-hidden="true" />
          <input
            ref="input"
            v-model="query"
            maxlength="200"
            autocomplete="off"
            :placeholder="t('globalSearch.placeholder')"
            :aria-label="t('globalSearch.searchAria')"
            aria-controls="global-search-results"
            :aria-activedescendant="activeOptionId"
          />
        </div>
        <button class="icon-button" type="button" :aria-label="t('globalSearch.close')" @click="close"><X :size="20" /></button>
      </header>

      <div v-if="loading" class="global-search-state" aria-live="polite">
        <LoaderCircle class="state-icon spin" :size="22" aria-hidden="true" />
        <span>{{ t('globalSearch.loading') }}</span>
      </div>
      <div v-else-if="error" class="global-search-state" aria-live="polite">
        <span class="state-title">{{ t('globalSearch.unavailableTitle') }}</span>
        <span class="state-description">{{ t('globalSearch.unavailableDescription') }}</span>
        <button class="retry-button" type="button" @click="search(query)">{{ t('common.retry') }}</button>
      </div>
      <div v-else-if="!query.trim()" class="global-search-state global-search-empty" aria-live="polite">
        <Search class="state-icon" :size="24" aria-hidden="true" />
        <span class="state-title">{{ t('globalSearch.emptyTitle') }}</span>
        <span class="state-description">{{ t('globalSearch.emptyDescription') }}</span>
      </div>
      <div v-else-if="submittedQuery !== query.trim()" class="global-search-state global-search-empty" aria-live="polite">
        <Search class="state-icon" :size="24" aria-hidden="true" />
        <span class="state-title">{{ t('globalSearch.readyTitle') }}</span>
        <span class="state-description">{{ t('globalSearch.readyDescription') }}</span>
      </div>
      <div v-else-if="!results.length" class="global-search-state" aria-live="polite">
        <span class="state-title">{{ t('globalSearch.noResultsTitle') }}</span>
        <span class="state-description">{{ t('globalSearch.noResultsDescription') }}</span>
      </div>
      <div v-else id="global-search-results" class="global-search-results" role="listbox" :aria-label="t('globalSearch.resultsAria')">
        <div class="results-heading">{{ t('globalSearch.resultsCount', { count: results.length }) }}</div>
        <button
          v-for="(result, index) in results"
          :key="`${result.contentType}:${result.resourceId}`"
          :id="`global-search-${result.contentType}-${result.resourceId}`"
          class="global-search-result"
          :class="{ 'is-active': activeIndex === index }"
          type="button"
          role="option"
          :aria-selected="activeIndex === index"
          @mouseenter="activeIndex = index"
          @click="emit('select', result)"
        >
          <span class="result-icon">
            <ListTodo v-if="result.contentType === 'task'" :size="17" />
            <FileText v-else :size="17" />
          </span>
          <span class="result-content">
            <span class="result-meta">
              <span class="project-label">{{ result.projectIdentifier }} · {{ result.projectName }}</span>
              <span class="content-type">{{ t(`globalSearch.type.${result.contentType}`) }}</span>
              <span v-if="result.contentType === 'task'" class="resource-id">{{ result.resourceId }}</span>
            </span>
            <strong class="result-title">{{ result.title }}</strong>
            <span class="result-description">{{ result.excerpt }}</span>
          </span>
          <ArrowUpRight class="result-arrow" :size="17" aria-hidden="true" />
        </button>
      </div>

      <footer v-if="query.trim() || results.length" class="global-search-footer">
        <span v-if="results.length"><kbd>↑</kbd><kbd>↓</kbd> {{ t('globalSearch.selectHint') }}</span><span><kbd>↵</kbd> {{ results.length ? t('globalSearch.openHint') : t('globalSearch.searchHint') }}</span><span><kbd>Esc</kbd> {{ t('globalSearch.closeHint') }}</span>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.global-search-overlay { position: fixed; inset: 0; z-index: 1000; display: flex; align-items: flex-start; justify-content: center; padding: 10vh 16px 24px; background: rgba(15, 23, 42, 0.42); backdrop-filter: blur(3px); }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
.global-search-dialog { width: min(720px, 100%); max-height: min(680px, 80vh); overflow: hidden; display: flex; flex-direction: column; background: var(--color-bg-main); border: 1px solid var(--color-border); border-radius: var(--radius-lg); box-shadow: var(--shadow-popover); }
.global-search-header { display: flex; align-items: center; gap: 12px; padding: 10px 12px 10px 18px; border-bottom: 1px solid var(--color-border); }
.global-search-input-wrap { display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1; color: var(--color-text-secondary); }
.global-search-input-wrap input { min-width: 0; flex: 1; height: 42px; border: 0; outline: 0; color: var(--color-text-primary); background: transparent; font: inherit; font-size: var(--font-size-subhead); }
.global-search-input-wrap input::placeholder { color: var(--color-text-muted); }
.icon-button { width: 40px; height: 40px; display: grid; place-items: center; flex: none; border: 0; border-radius: var(--radius-sm); color: var(--color-text-secondary); background: transparent; cursor: pointer; }
.icon-button:hover { color: var(--color-text-primary); background: var(--color-bg-hover); }
.icon-button:focus-visible { color: var(--color-text-primary); background: var(--color-bg-hover); outline: 2px solid var(--color-border-strong); outline-offset: 1px; }
.global-search-results { overflow-y: auto; padding: 8px; }
.results-heading { padding: 8px 12px 7px; color: var(--color-text-muted); font-size: var(--font-size-caption); font-weight: var(--font-weight-semibold); }
.global-search-result { width: 100%; display: grid; grid-template-columns: 32px minmax(0, 1fr) 20px; gap: 10px; align-items: start; padding: 12px; text-align: left; border: 1px solid transparent; border-radius: var(--radius-md); color: inherit; background: transparent; cursor: pointer; }
.global-search-result:hover, .global-search-result.is-active { border-color: var(--color-border); background: var(--color-bg-hover); }
.global-search-result:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: -2px; }
.result-icon { width: 30px; height: 30px; display: grid; place-items: center; border-radius: var(--radius-sm); color: var(--color-text-secondary); background: var(--color-bg-muted); }
.result-content { min-width: 0; display: grid; gap: 4px; }
.result-meta { display: flex; align-items: center; gap: 8px; min-width: 0; color: var(--color-text-muted); font-size: var(--font-size-caption); }
.project-label, .resource-id { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-label { color: var(--color-text-secondary); font-weight: var(--font-weight-semibold); }
.content-type { flex: none; padding: 1px 5px; border-radius: var(--radius-xs); color: var(--color-text-secondary); background: var(--color-bg-muted); }
.resource-id { color: var(--color-text-muted); }
.result-title { overflow: hidden; color: var(--color-text-primary); font-size: var(--font-size-body); font-weight: var(--font-weight-semibold); line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.result-description { display: -webkit-box; overflow: hidden; color: var(--color-text-secondary); font-size: var(--font-size-caption); line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.result-arrow { margin-top: 5px; color: var(--color-text-muted); opacity: 0; transition: opacity var(--transition-fast), transform var(--transition-fast); }
.global-search-result:hover .result-arrow, .global-search-result.is-active .result-arrow { opacity: 1; transform: translate(1px, -1px); }
.global-search-state { min-height: 170px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 7px; padding: 28px; color: var(--color-text-secondary); text-align: center; }
.state-icon { margin-bottom: 4px; color: var(--color-text-muted); }
.state-title { color: var(--color-text-primary); font-size: var(--font-size-body); font-weight: var(--font-weight-semibold); }
.state-description { color: var(--color-text-secondary); font-size: var(--font-size-caption); }
.retry-button { margin-top: 8px; padding: 6px 12px; border: 1px solid var(--color-border); border-radius: var(--radius-sm); color: var(--color-text-secondary); background: var(--color-bg-base); font: inherit; font-size: var(--font-size-caption); cursor: pointer; }
.retry-button:hover { background: var(--color-bg-hover); }
.retry-button:focus-visible { outline: 2px solid var(--color-border-strong); outline-offset: 1px; }
.spin { animation: search-spin 900ms linear infinite; }
.global-search-footer { display: flex; gap: 16px; align-items: center; padding: 9px 16px; color: var(--color-text-muted); border-top: 1px solid var(--color-border); font-size: var(--font-size-xs); }
kbd { display: inline-flex; min-width: 20px; height: 20px; align-items: center; justify-content: center; margin-right: 3px; padding: 0 5px; border: 1px solid var(--color-border); border-bottom-width: 2px; border-radius: var(--radius-xs); color: var(--color-text-secondary); background: var(--color-bg-muted); font: inherit; font-size: var(--font-size-xs); }
@keyframes search-spin { to { transform: rotate(360deg); } }
@media (max-width: 560px) { .global-search-overlay { padding: 12px; align-items: flex-start; } .global-search-dialog { max-height: calc(100dvh - 24px); } .global-search-footer { gap: 8px; } .global-search-footer span:last-child { margin-left: auto; } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } .result-arrow { transition: none; } }
</style>
