<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ArrowUpRight, FileText, LoaderCircle, Search, X } from 'lucide-vue-next'
import { taskApi } from '../services/api/task'
import { useProjectStore } from '../store/projectStore'
import type { Task } from '../types/domain'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ close: []; select: [task: Task] }>()

const projectStore = useProjectStore()
const input = ref<HTMLInputElement | null>(null)
const query = ref('')
const results = ref<Task[]>([])
const loading = ref(false)
const error = ref(false)
const activeIndex = ref(-1)
let timer: ReturnType<typeof setTimeout> | null = null
let requestId = 0

function extractText(value: unknown): string {
  if (typeof value === 'string') return value
  if (Array.isArray(value)) return value.map(extractText).join(' ')
  if (!value || typeof value !== 'object') return ''
  const node = value as Record<string, unknown>
  return [node.text, node.content, node.children].map(extractText).filter(Boolean).join(' ')
}

function readableDescription(value?: string): string {
  if (!value?.trim()) return '暂无描述'
  let text = value.trim()
  if (text.startsWith('[') || text.startsWith('{')) {
    try { text = extractText(JSON.parse(text)) || text } catch { /* keep plain text */ }
  }
  return text
    .replace(/<[^>]*>/g, ' ')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/\s+/g, ' ')
    .trim()
    .slice(0, 180) || '暂无描述'
}

function projectLabel(task: Task): string {
  const project = task.projectId == null ? undefined : projectStore.projects.find((item) => item.id === task.projectId)
  return project ? `${project.identifier} · ${project.name}` : `项目 ${task.projectId ?? '未知'}`
}

function search(value: string) {
  if (timer) clearTimeout(timer)
  const normalized = value.trim()
  results.value = []
  activeIndex.value = -1
  error.value = false
  if (!normalized) { loading.value = false; return }
  const currentRequest = ++requestId
  timer = setTimeout(async () => {
    loading.value = true
    try {
      const nextResults = await taskApi.search(normalized)
      if (currentRequest === requestId) results.value = nextResults
    } catch {
      if (currentRequest === requestId) error.value = true
    } finally {
      if (currentRequest === requestId) loading.value = false
    }
  }, 250)
}

watch(() => props.open, (open) => {
  if (open) nextTick(() => input.value?.focus())
})
watch(query, search)
onBeforeUnmount(() => { if (timer) clearTimeout(timer) })

function close() {
  query.value = ''
  results.value = []
  error.value = false
  activeIndex.value = -1
  emit('close')
}

function selectActive() {
  const task = results.value[activeIndex.value]
  if (task) emit('select', task)
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
    selectActive()
  }
}
</script>

<template>
  <div v-if="open" class="global-search-overlay" @click.self="close">
    <section class="global-search-dialog" role="dialog" aria-modal="true" aria-labelledby="global-search-title" @keydown.esc="close" @keydown="onKeydown">
      <header class="global-search-header">
        <h2 id="global-search-title" class="sr-only">全局语义搜索</h2>
        <div class="global-search-input-wrap">
          <Search :size="20" stroke-width="2" aria-hidden="true" />
          <input ref="input" v-model="query" maxlength="200" autocomplete="off" placeholder="搜索所有项目的标题和描述…" aria-label="搜索所有项目的标题和描述" />
        </div>
        <button class="icon-button" type="button" aria-label="关闭搜索" @click="close"><X :size="20" /></button>
      </header>

      <div v-if="loading" class="global-search-state" aria-live="polite">
        <LoaderCircle class="state-icon spin" :size="22" aria-hidden="true" />
        <span>正在搜索相关任务…</span>
      </div>
      <div v-else-if="error" class="global-search-state" aria-live="polite">
        <span class="state-title">搜索暂时不可用</span>
        <span class="state-description">请稍后重试，或检查网络连接。</span>
        <button class="retry-button" type="button" @click="search(query)">重新搜索</button>
      </div>
      <div v-else-if="!query.trim()" class="global-search-state global-search-empty" aria-live="polite">
        <Search class="state-icon" :size="24" aria-hidden="true" />
        <span class="state-title">搜索跨项目任务</span>
        <span class="state-description">输入标题或描述中的关键词，查找相关任务</span>
      </div>
      <div v-else-if="!results.length" class="global-search-state" aria-live="polite">
        <span class="state-title">没有找到相关任务</span>
        <span class="state-description">换个关键词或尝试更简短的描述</span>
      </div>
      <div v-else class="global-search-results" role="listbox" aria-label="搜索结果">
        <div class="results-heading">找到 {{ results.length }} 个相关任务</div>
        <button
          v-for="(task, index) in results"
          :key="task.id"
          class="global-search-result"
          :class="{ 'is-active': activeIndex === index }"
          type="button"
          role="option"
          :aria-selected="activeIndex === index"
          @mouseenter="activeIndex = index"
          @click="emit('select', task)"
        >
          <span class="result-icon"><FileText :size="17" /></span>
          <span class="result-content">
            <span class="result-meta"><span class="project-label">{{ projectLabel(task) }}</span><span class="task-key">{{ task.id }}</span></span>
            <strong class="result-title">{{ task.title || '未命名任务' }}</strong>
            <span class="result-description">{{ readableDescription(task.description) }}</span>
          </span>
          <ArrowUpRight class="result-arrow" :size="17" aria-hidden="true" />
        </button>
      </div>

      <footer v-if="query.trim() || results.length" class="global-search-footer">
        <span><kbd>↑</kbd><kbd>↓</kbd> 选择</span><span><kbd>↵</kbd> 打开</span><span><kbd>Esc</kbd> 关闭</span>
      </footer>
    </section>
  </div>
</template>

<style scoped>
.global-search-overlay { position: fixed; inset: 0; z-index: 1000; display: flex; align-items: flex-start; justify-content: center; padding: 10vh 16px 24px; background: rgba(15, 23, 42, 0.42); backdrop-filter: blur(3px); }
.sr-only { position: absolute; width: 1px; height: 1px; padding: 0; margin: -1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; border: 0; }
.global-search-dialog { width: min(720px, 100%); max-height: min(680px, 80vh); overflow: hidden; display: flex; flex-direction: column; background: var(--bg-primary, #fff); border: 1px solid var(--border-color, #e5e7eb); border-radius: 16px; box-shadow: 0 24px 70px rgba(15, 23, 42, 0.26), 0 4px 12px rgba(15, 23, 42, 0.08); }
.global-search-header { display: flex; align-items: center; gap: 12px; padding: 10px 12px 10px 18px; border-bottom: 1px solid var(--border-color, #e5e7eb); }
.global-search-input-wrap { display: flex; align-items: center; gap: 12px; min-width: 0; flex: 1; color: var(--text-secondary, #64748b); }
.global-search-input-wrap input { min-width: 0; flex: 1; height: 42px; border: 0; outline: 0; color: var(--text-primary, #0f172a); background: transparent; font: inherit; font-size: 17px; }
.global-search-input-wrap input::placeholder { color: var(--text-tertiary, #94a3b8); }
.icon-button { width: 40px; height: 40px; display: grid; place-items: center; flex: none; border: 0; border-radius: 9px; color: var(--text-secondary, #64748b); background: transparent; cursor: pointer; }
.icon-button:hover, .icon-button:focus-visible { color: var(--text-primary, #0f172a); background: var(--bg-hover, #f1f5f9); outline: none; }
.global-search-results { overflow-y: auto; padding: 8px; }
.results-heading { padding: 8px 12px 7px; color: var(--text-tertiary, #94a3b8); font-size: 12px; font-weight: 600; }
.global-search-result { width: 100%; display: grid; grid-template-columns: 32px minmax(0, 1fr) 20px; gap: 10px; align-items: start; padding: 12px; text-align: left; border: 1px solid transparent; border-radius: 10px; color: inherit; background: transparent; cursor: pointer; }
.global-search-result:hover, .global-search-result.is-active { border-color: var(--border-color, #e5e7eb); background: var(--bg-hover, #f8fafc); }
.result-icon { width: 30px; height: 30px; display: grid; place-items: center; border-radius: 8px; color: #64748b; background: #f1f5f9; }
.result-content { min-width: 0; display: grid; gap: 4px; }
.result-meta { display: flex; align-items: center; gap: 8px; min-width: 0; color: var(--text-tertiary, #94a3b8); font-size: 12px; }
.project-label, .task-key { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.project-label { color: var(--text-secondary, #64748b); font-weight: 600; }
.task-key { color: var(--text-tertiary, #94a3b8); }
.result-title { overflow: hidden; color: var(--text-primary, #0f172a); font-size: 14px; font-weight: 650; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.result-description { display: -webkit-box; overflow: hidden; color: var(--text-secondary, #64748b); font-size: 13px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.result-arrow { margin-top: 5px; color: var(--text-tertiary, #94a3b8); opacity: 0; transition: opacity 120ms ease, transform 120ms ease; }
.global-search-result:hover .result-arrow, .global-search-result.is-active .result-arrow { opacity: 1; transform: translate(1px, -1px); }
.global-search-state { min-height: 170px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 7px; padding: 28px; color: var(--text-secondary, #64748b); text-align: center; }
.state-icon { margin-bottom: 4px; color: var(--text-tertiary, #94a3b8); }
.state-title { color: var(--text-primary, #334155); font-size: 14px; font-weight: 650; }
.state-description { color: var(--text-secondary, #64748b); font-size: 13px; }
.retry-button { margin-top: 8px; padding: 6px 12px; border: 1px solid var(--border-color, #dbe1e8); border-radius: 7px; color: var(--text-secondary, #475569); background: var(--bg-secondary, #f8fafc); font: inherit; font-size: 13px; cursor: pointer; }
.retry-button:hover, .retry-button:focus-visible { border-color: var(--text-secondary, #94a3b8); background: var(--bg-hover, #f1f5f9); outline: none; }
.spin { animation: search-spin 900ms linear infinite; }
.global-search-footer { display: flex; gap: 16px; align-items: center; padding: 9px 16px; color: var(--text-tertiary, #94a3b8); border-top: 1px solid var(--border-color, #e5e7eb); font-size: 11px; }
kbd { display: inline-flex; min-width: 20px; height: 20px; align-items: center; justify-content: center; margin-right: 3px; padding: 0 5px; border: 1px solid var(--border-color, #dbe1e8); border-bottom-width: 2px; border-radius: 5px; color: var(--text-secondary, #64748b); background: var(--bg-secondary, #f8fafc); font: inherit; font-size: 11px; }
@keyframes search-spin { to { transform: rotate(360deg); } }
@media (max-width: 560px) { .global-search-overlay { padding: 12px; align-items: flex-start; } .global-search-dialog { max-height: calc(100vh - 24px); border-radius: 14px; } .global-search-footer { gap: 8px; } .global-search-footer span:last-child { margin-left: auto; } }
</style>
