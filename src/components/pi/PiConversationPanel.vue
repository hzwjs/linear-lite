<script setup lang="ts">
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import { agentApi } from '../../services/api/agent'
import { toApiError } from '../../services/api'
import {
  applySessionSnapshot,
  appendPendingUserMessage,
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
  prompt: string
  error: string
}>()
const emit = defineEmits<{
  'update:prompt': [value: string]
  submit: []
  stop: []
}>()

const state = reactive(createAgentConversationState())
const blocks = computed(() => conversationDisplayBlocks(state))
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
      if (props.error) removePendingUserMessage(state, pendingSubmitBlockId)
      pendingSubmitBlockId = null
    }
  }
)

function submitTurn() {
  const text = props.prompt.trim()
  if (text && props.executionId) {
    pendingSubmitBlockId = appendPendingUserMessage(state, props.executionId, text)
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
    <PiConversationViewport
      :blocks="blocks"
      :preparing="preparing || snapshotLoading"
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
</style>
