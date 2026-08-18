<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { ChevronRight, Loader2 } from 'lucide-vue-next'
import type { AgentDisplayTool, ConversationDisplayBlock } from '../../services/api/agent'
import { formatAgentValue } from '../../utils/agentConversationState'
import PiToolCall from './PiToolCall.vue'
import PiToolResult from './PiToolResult.vue'

const props = defineProps<{ block: ConversationDisplayBlock }>()

const tool = computed<AgentDisplayTool>(() => {
  if (props.block.kind !== 'tool' || props.block.tool === null) {
    throw new Error('tool 显示块缺少 tool')
  }
  return props.block.tool
})
const expanded = ref(props.block.phase !== 'final')
const argumentPreview = computed(() => formatAgentValue(tool.value.arguments).replace(/\s+/g, ' ').trim())
const phaseLabel = computed(() => {
  if (props.block.phase === 'streaming') return '执行中'
  if (props.block.phase === 'error') return '失败'
  return '成功'
})

watch(() => props.block.phase, (phase) => {
  // 成功块收敛为终端摘要；运行中和失败块保持正文可见。
  expanded.value = phase !== 'final'
})
</script>

<template>
  <article class="pi-tool" :class="`pi-tool--${block.phase}`">
    <button
      type="button"
      class="pi-tool__header"
      :aria-expanded="expanded"
      @click="expanded = !expanded"
    >
      <ChevronRight class="pi-tool__chevron" :class="{ 'pi-tool__chevron--open': expanded }" aria-hidden="true" />
      <Loader2 v-if="block.phase === 'streaming'" class="pi-tool__spinner" aria-hidden="true" />
      <span v-else class="pi-tool__dot" aria-hidden="true" />
      <code class="pi-tool__name">{{ tool.name }}</code>
      <code v-if="argumentPreview" class="pi-tool__preview">{{ argumentPreview }}</code>
      <span class="pi-tool__phase">{{ phaseLabel }}</span>
    </button>
    <div v-if="expanded" class="pi-tool__body">
      <PiToolCall :tool="tool" />
      <PiToolResult v-if="tool.content.length || tool.truncation !== null" :tool="tool" />
    </div>
  </article>
</template>

<style scoped>
.pi-tool {
  margin: 0 0 16px;
  border-left: 2px solid var(--color-border-strong);
  background: var(--color-bg-subtle);
}
.pi-tool--error { border-left-color: var(--color-danger); }
.pi-tool__header {
  display: grid;
  grid-template-columns: 14px 14px auto minmax(0, 1fr) auto;
  align-items: center;
  gap: 7px;
  width: 100%;
  min-height: 34px;
  padding: 6px 9px;
  border: 0;
  color: var(--color-text-secondary);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.pi-tool__header:hover { background: var(--color-bg-hover); }
.pi-tool__chevron {
  width: 14px;
  height: 14px;
  color: var(--color-text-muted);
  transition: transform var(--transition-fast);
}
.pi-tool__chevron--open { transform: rotate(90deg); }
.pi-tool__spinner {
  width: 13px;
  height: 13px;
  color: var(--color-accent);
  animation: pi-tool-spin 1s linear infinite;
}
.pi-tool__dot {
  width: 6px;
  height: 6px;
  margin-left: 4px;
  border-radius: var(--radius-full);
  background: var(--color-success);
}
.pi-tool--error .pi-tool__dot { background: var(--color-danger); }
.pi-tool__name,
.pi-tool__preview {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: var(--font-size-caption);
}
.pi-tool__name {
  color: var(--color-text-primary);
  font-weight: var(--font-weight-semibold);
}
.pi-tool__preview {
  min-width: 0;
  overflow: hidden;
  color: var(--color-text-secondary);
  text-overflow: ellipsis;
  white-space: nowrap;
}
.pi-tool__phase {
  color: var(--color-text-muted);
  font-size: var(--font-size-xs);
}
.pi-tool--error .pi-tool__phase { color: var(--color-danger); }
.pi-tool__body {
  display: grid;
  gap: 9px;
  padding: 4px 12px 11px 35px;
}
@keyframes pi-tool-spin { to { transform: rotate(360deg); } }
</style>
