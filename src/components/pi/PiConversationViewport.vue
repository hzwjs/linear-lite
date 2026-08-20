<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Loader2 } from 'lucide-vue-next'
import type { AgentDisplayContent, ConversationDisplayBlock } from '../../services/api/agent'
import PiAssistantMessage from './PiAssistantMessage.vue'
import PiToolExecution from './PiToolExecution.vue'
import PiUserMessage from './PiUserMessage.vue'

const props = defineProps<{
  blocks: ConversationDisplayBlock[]
  preparing: boolean
  waiting: boolean
  error: string
  streamError: string
}>()

const viewport = ref<HTMLElement | null>(null)
const visibleBlocks = computed(() => props.blocks.filter((block) => {
  if (block.kind !== 'assistant') return true
  if (block.content === null) return false
  return Boolean(block.content.text || block.content.thinking)
}))
const revisionKey = computed(() => props.blocks.map((block) =>
  `${block.blockId}:${'revision' in block ? block.revision : block.phase}`
).join('|'))

function textContent(block: ConversationDisplayBlock): AgentDisplayContent {
  if (block.content === null) throw new Error('文本显示块缺少 content')
  return block.content
}

watch(revisionKey, async () => {
  await nextTick()
  if (viewport.value) viewport.value.scrollTop = viewport.value.scrollHeight
})
</script>

<template>
  <div ref="viewport" class="pi-conversation-viewport" aria-live="polite">
    <div v-if="error" class="pi-conversation-notice pi-conversation-notice--error">{{ error }}</div>
    <div v-else-if="streamError" class="pi-conversation-notice">{{ streamError }}</div>
    <div v-if="preparing && !visibleBlocks.length" class="pi-conversation-preparing">
      <Loader2 aria-hidden="true" />
      <span>正在读取 Pi session…</span>
    </div>
    <div v-else-if="waiting" class="pi-conversation-preparing" role="status">
      <Loader2 aria-hidden="true" />
      <span>正在等待 Agent 响应…</span>
    </div>
    <div v-for="block in visibleBlocks" :key="block.blockId" class="pi-conversation-block">
      <PiAssistantMessage v-if="block.kind === 'assistant'" :block="block" />
      <PiToolExecution v-else-if="block.kind === 'tool'" :block="block" />
      <PiUserMessage v-else :content="textContent(block).text" />
    </div>
  </div>
</template>

<style scoped>
.pi-conversation-viewport {
  min-height: 0;
  flex: 1 1 auto;
  overflow-y: auto;
  padding: 2px 3px 18px 0;
  scrollbar-gutter: stable;
}
.pi-conversation-block {
  /* 变高 Markdown/工具块使用浏览器原生可见性虚拟化，不截断 Pi session 快照内容。 */
  content-visibility: auto;
  contain-intrinsic-block-size: auto 96px;
}
.pi-conversation-notice,
.pi-conversation-preparing,
.pi-turn-error {
  margin: 0 0 14px;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  line-height: 1.5;
}
.pi-conversation-notice--error,
.pi-turn-error { color: var(--color-danger); }
.pi-conversation-preparing {
  display: flex;
  align-items: center;
  gap: 8px;
}
.pi-conversation-preparing svg {
  width: 14px;
  height: 14px;
  color: var(--color-accent);
  animation: pi-viewport-spin 1s linear infinite;
}
.pi-turn-error {
  padding: 8px 10px;
  border-left: 2px solid var(--color-danger);
  background: color-mix(in srgb, var(--color-danger) 5%, var(--color-bg-base));
  white-space: pre-wrap;
}
@keyframes pi-viewport-spin { to { transform: rotate(360deg); } }
</style>
