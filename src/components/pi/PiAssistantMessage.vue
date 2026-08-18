<script setup lang="ts">
import { computed } from 'vue'
import type { AgentDisplayContent, ConversationDisplayBlock } from '../../services/api/agent'
import { renderMarkdown } from '../../utils/markdown'

const props = defineProps<{ block: ConversationDisplayBlock }>()

const content = computed<AgentDisplayContent>(() => {
  if (props.block.kind !== 'assistant' || props.block.content === null) {
    throw new Error('assistant 显示块缺少 content')
  }
  return props.block.content
})
const markdownHtml = computed(() => renderMarkdown(content.value.text))
// SSE 先推 thinking 再推正文；视口始终滚到底。正文出现后若思考仍展开，滚动会钉在思考上把回复顶出视野。
const thinkingOpen = computed(() => props.block.phase === 'streaming' && !content.value.text)
</script>

<template>
  <article class="pi-assistant-message" :class="`pi-assistant-message--${block.phase}`">
    <div
      v-if="content.text"
      class="pi-assistant-message__content markdown-body"
      v-html="markdownHtml"
    />
    <span
      v-if="block.phase === 'streaming' && content.text"
      class="pi-assistant-message__cursor"
      aria-label="正在生成"
    />
    <details v-if="content.thinking" class="pi-thinking" :open="thinkingOpen">
      <summary>思考过程</summary>
      <div>{{ content.thinking }}</div>
    </details>
    <span
      v-if="block.phase === 'streaming' && !content.text"
      class="pi-assistant-message__cursor"
      aria-label="正在生成"
    />
  </article>
</template>

<style scoped>
.pi-assistant-message {
  display: block;
  margin: 0 0 22px;
  color: var(--color-text-primary);
}
.pi-thinking {
  margin: 10px 0 0;
  color: var(--color-text-secondary);
  font-size: var(--font-size-caption);
  font-style: italic;
  line-height: 1.55;
}
.pi-thinking summary {
  width: fit-content;
  margin-bottom: 4px;
  cursor: pointer;
  color: var(--color-text-muted);
  font-style: normal;
}
.pi-thinking div {
  padding-left: 14px;
  white-space: pre-wrap;
}
.pi-assistant-message__content {
  font-size: var(--font-size-body);
  line-height: 1.65;
  overflow-wrap: anywhere;
}
.pi-assistant-message__content :deep(> :first-child) { margin-top: 0; }
.pi-assistant-message__content :deep(> :last-child) { margin-bottom: 0; }
.pi-assistant-message__content :deep(p),
.pi-assistant-message__content :deep(ul),
.pi-assistant-message__content :deep(ol),
.pi-assistant-message__content :deep(pre),
.pi-assistant-message__content :deep(blockquote),
.pi-assistant-message__content :deep(table) { margin: 0 0 10px; }
.pi-assistant-message__content :deep(pre) {
  max-width: 100%;
  overflow: auto;
  padding: 10px 12px;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-md);
  background: var(--color-bg-muted);
}
.pi-assistant-message__content :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.92em;
}
.pi-assistant-message__content :deep(:not(pre) > code) {
  padding: 1px 4px;
  border-radius: var(--radius-xs);
  background: var(--color-bg-muted);
}
.pi-assistant-message__content :deep(table) {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--font-size-caption);
}
.pi-assistant-message__content :deep(th),
.pi-assistant-message__content :deep(td) {
  padding: 6px 8px;
  border: 1px solid var(--color-border);
  text-align: left;
  vertical-align: top;
}
.pi-assistant-message__cursor {
  display: inline-block;
  width: 6px;
  height: 14px;
  margin-left: 2px;
  vertical-align: -2px;
  background: var(--color-text-muted);
  animation: pi-cursor-blink 900ms steps(1) infinite;
}
.pi-assistant-message--error .pi-assistant-message__content { color: var(--color-danger); }
@keyframes pi-cursor-blink { 50% { opacity: 0; } }
</style>
