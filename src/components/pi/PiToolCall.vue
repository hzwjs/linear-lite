<script setup lang="ts">
import { computed } from 'vue'
import type { AgentDisplayTool } from '../../services/api/agent'
import { formatAgentValue } from '../../utils/agentConversationState'

const props = defineProps<{ tool: AgentDisplayTool }>()

const command = computed(() => {
  if (props.tool.name !== 'bash') return ''
  return typeof props.tool.arguments.command === 'string' ? props.tool.arguments.command : ''
})
const remainingArguments = computed(() => {
  if (!command.value) return props.tool.arguments
  return Object.fromEntries(Object.entries(props.tool.arguments).filter(([key]) => key !== 'command'))
})
const argumentText = computed(() => formatAgentValue(remainingArguments.value))
</script>

<template>
  <div class="pi-tool-call">
    <code v-if="command" class="pi-tool-call__command">$ {{ command }}</code>
    <pre v-if="argumentText" class="pi-tool-call__arguments">{{ argumentText }}</pre>
  </div>
</template>

<style scoped>
.pi-tool-call {
  display: grid;
  gap: 7px;
  min-width: 0;
}
.pi-tool-call__command,
.pi-tool-call__arguments {
  margin: 0;
  color: var(--color-text-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: var(--font-size-caption);
  line-height: 1.55;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.pi-tool-call__arguments {
  padding: 0;
  background: transparent;
}
</style>
