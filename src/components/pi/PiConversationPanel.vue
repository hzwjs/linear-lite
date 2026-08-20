<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { agentApi } from '../../services/api/agent'
import { toApiError } from '../../services/api'
import {
  applySessionSnapshot,
  appendPendingAssistantMessage,
  appendPendingUserMessage,
  clearPendingAssistantMessage,
  clearRuntimeDisplayBlocks,
  conversationDisplayBlocks,
  createAgentConversationState,
  removePendingUserMessage,
  resetAgentConversationState,
  upsertRuntimeDisplayBlock
} from '../../utils/agentConversationState'
import PiConversationViewport from './PiConversationViewport.vue'
import PiTurnComposer from './PiTurnComposer.vue'

const props = defineProps<{
  taskKey: string
  executionId: string | null
  hasSubmittedTurn: boolean
  active: boolean
  canSubmit: boolean
  preparing: boolean
  submitting: boolean
  canceling: boolean
  stalled: boolean
  reconnecting: boolean
  prompt: string
  error: string
}>()
const emit = defineEmits<{
  'update:prompt': [value: string]
  submit: []
  stop: []
  reconnect: []
}>()

const state = reactive(createAgentConversationState())
const blocks = computed(() => conversationDisplayBlocks(state))
const waitingForResponse = computed(() => props.active && state.runtimeIds.length === 0
  && state.pendingAssistantBlock === null)
const streamError = ref('')
const sessionReadError = ref('')
const snapshotLoading = ref(false)
let source: EventSource | null = null
let connectionSequence = 0
let pendingSubmitBlockId: string | null = null

function closeStream() {
  source?.close()
  source = null
}

async function requestSnapshot(taskKey: string, executionId: string, sequence: number) {
  snapshotLoading.value = true
  try {
    await agentApi.requestSessionSnapshot(taskKey, executionId)
    if (sequence === connectionSequence) sessionReadError.value = ''
  } catch (error) {
    if (sequence === connectionSequence) {
      sessionReadError.value = toApiError(error).message || 'Pi session 读取失败'
      snapshotLoading.value = false
    }
  }
}

function connectStream() {
  closeStream()
  streamError.value = ''
  sessionReadError.value = ''
  snapshotLoading.value = false
  const taskKey = props.taskKey
  const executionId = props.executionId
  if (!taskKey || !executionId) return
  const sequence = ++connectionSequence
  source = agentApi.openSessionStream(
    taskKey,
    executionId,
    (snapshot) => {
      if (sequence !== connectionSequence || snapshot.executionId !== executionId) return
      applySessionSnapshot(state, snapshot)
      sessionReadError.value = ''
      snapshotLoading.value = false
    },
    (block) => {
      if (sequence !== connectionSequence || block.executionId !== executionId) return
      upsertRuntimeDisplayBlock(state, block)
    },
    (message) => {
      if (sequence === connectionSequence) {
        sessionReadError.value = message
        snapshotLoading.value = false
      }
    },
    () => {
      if (sequence !== connectionSequence) return
      streamError.value = ''
      // 空会话没有历史可读；等 Bridge 回读只会让首屏卡在「正在读取 Pi session」。
      if (props.hasSubmittedTurn) void requestSnapshot(taskKey, executionId, sequence)
    },
    () => {
      if (sequence === connectionSequence) streamError.value = '实时输出连接中断，正在重连…'
    }
  )
}

watch(
  () => props.active,
  (active) => {
    if (!active) clearRuntimeDisplayBlocks(state)
  },
  { immediate: true }
)

watch(
  () => [props.taskKey, props.executionId] as const,
  ([taskKey, executionId], previous) => {
    if (!previous || previous[0] !== taskKey || previous[1] !== executionId) {
      resetAgentConversationState(state, executionId)
      connectStream()
    }
  },
  { immediate: true }
)

watch(
  () => props.submitting,
  (submitting, wasSubmitting) => {
    if (wasSubmitting && !submitting && pendingSubmitBlockId) {
      if (props.error) {
        removePendingUserMessage(state, pendingSubmitBlockId)
        clearPendingAssistantMessage(state)
      }
      pendingSubmitBlockId = null
    }
  }
)

function submitTurn() {
  const text = props.prompt.trim()
  if (text && props.executionId) {
    pendingSubmitBlockId = appendPendingUserMessage(state, props.executionId, text)
    appendPendingAssistantMessage(state, props.executionId)
  }
  emit('submit')
}

onBeforeUnmount(() => {
  connectionSequence += 1
  closeStream()
})
</script>

<template>
  <section class="pi-conversation-panel">
    <div v-if="stalled" class="pi-conversation-recovery" role="alert">
      <div class="pi-conversation-recovery__copy">
        <strong>本轮执行已中断</strong>
        <span>Bridge 租约已过期，重新连接后可以再次提交指令。</span>
      </div>
      <button
        type="button"
        class="pi-conversation-recovery__button"
        :disabled="reconnecting"
        :aria-busy="reconnecting"
        @click="emit('reconnect')"
      >
        {{ reconnecting ? '连接中…' : '重新连接 Bridge' }}
      </button>
    </div>
    <PiConversationViewport
      :blocks="blocks"
      :preparing="preparing || snapshotLoading"
      :waiting="waitingForResponse"
      :error="error || sessionReadError"
      :stream-error="streamError"
    />
    <PiTurnComposer
      v-if="executionId"
      :model-value="prompt"
      :active="active"
      :can-submit="canSubmit"
      :submitting="submitting"
      :canceling="canceling"
      @update:model-value="emit('update:prompt', $event)"
      @submit="submitTurn"
      @stop="emit('stop')"
    />
  </section>
</template>

<style scoped>
.pi-conversation-panel {
  display: flex;
  flex: 1 1 auto;
  flex-direction: column;
  width: 100%;
  min-height: 0;
  overflow: hidden;
}
.pi-conversation-recovery {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
  padding: 10px 12px;
  border: 1px solid color-mix(in srgb, var(--color-danger) 28%, var(--color-border));
  border-radius: var(--radius-md);
  color: var(--color-text-secondary);
  background: color-mix(in srgb, var(--color-danger) 5%, var(--color-bg-base));
}
.pi-conversation-recovery__copy {
  display: grid;
  gap: 2px;
  min-width: 0;
  font-size: var(--font-size-caption);
  line-height: 1.45;
}
.pi-conversation-recovery__copy strong { color: var(--color-danger); }
.pi-conversation-recovery__button {
  flex: 0 0 auto;
  min-height: 36px;
  padding: 0 12px;
  border: 1px solid var(--color-border-strong);
  border-radius: var(--radius-sm);
  color: var(--color-text-primary);
  background: var(--color-bg-base);
  cursor: pointer;
  font: inherit;
  font-size: var(--font-size-caption);
}
.pi-conversation-recovery__button:hover:not(:disabled) { background: var(--color-bg-hover); }
.pi-conversation-recovery__button:disabled { cursor: wait; opacity: 0.6; }
.pi-conversation-recovery__button:focus-visible { outline: 2px solid var(--color-accent); outline-offset: 2px; }
</style>
