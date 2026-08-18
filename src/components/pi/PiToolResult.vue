<script setup lang="ts">
import { computed } from 'vue'
import type { AgentDisplayTool } from '../../services/api/agent'
import { formatAgentValue } from '../../utils/agentConversationState'

const props = defineProps<{ tool: AgentDisplayTool }>()
const truncationText = computed(() => props.tool.truncation === null ? '' : formatAgentValue(props.tool.truncation))
</script>

<template>
  <div class="pi-tool-result" :class="{ 'pi-tool-result--error': tool.isError }">
    <template v-for="(item, index) in tool.content" :key="index">
      <pre v-if="item.type === 'text'" class="pi-tool-result__text">{{ item.text }}</pre>
      <img
        v-else
        class="pi-tool-result__image"
        :src="`data:${item.mimeType};base64,${item.data}`"
        alt="工具返回的图片"
      />
    </template>
    <div v-if="truncationText" class="pi-tool-result__truncation">{{ truncationText }}</div>
  </div>
</template>

<style scoped>
.pi-tool-result {
  min-width: 0;
  border-top: 1px solid var(--color-border-subtle);
  padding-top: 8px;
}
.pi-tool-result__text {
  max-height: 360px;
  margin: 0;
  overflow: auto;
  color: var(--color-text-secondary);
  background: transparent;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: var(--font-size-caption);
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.pi-tool-result--error .pi-tool-result__text { color: var(--color-danger); }
.pi-tool-result__image {
  display: block;
  max-width: 100%;
  max-height: 320px;
  border-radius: var(--radius-sm);
}
.pi-tool-result__truncation {
  margin-top: 8px;
  color: var(--color-text-muted);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: var(--font-size-xs);
  white-space: pre-wrap;
}
</style>
