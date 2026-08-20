import type {
  ConversationDisplayBlock,
  RuntimeDisplayBlock,
  SessionDisplayBlock,
  SessionSnapshot
} from '../services/api/agent'

export interface AgentConversationState {
  executionId: string | null
  sessionBlocks: SessionDisplayBlock[]
  pendingUserBlocks: SessionDisplayBlock[]
  pendingAssistantBlock: RuntimeDisplayBlock | null
  runtimeById: Map<string, RuntimeDisplayBlock>
  runtimeIds: string[]
}

export function createAgentConversationState(): AgentConversationState {
  return {
    executionId: null,
    sessionBlocks: [],
    pendingUserBlocks: [],
    pendingAssistantBlock: null,
    runtimeById: new Map(),
    runtimeIds: []
  }
}

export function resetAgentConversationState(state: AgentConversationState, executionId: string | null): void {
  state.executionId = executionId
  state.sessionBlocks.splice(0)
  state.pendingUserBlocks.splice(0)
  state.pendingAssistantBlock = null
  state.runtimeById.clear()
  state.runtimeIds.splice(0)
}

let pendingUserSeq = 0
let pendingAssistantSeq = 0

/** 提交成功前先写入本地用户块；快照追上后由 applySessionSnapshot 吸收，避免首轮气泡要等 Bridge 回读。 */
export function appendPendingUserMessage(
  state: AgentConversationState,
  executionId: string,
  text: string
): string | null {
  if (state.executionId !== executionId || !text) return null
  pendingUserSeq += 1
  const blockId = `local-user:${pendingUserSeq}`
  state.pendingUserBlocks.push({
    blockId,
    entryId: blockId,
    runtimeBlockId: null,
    order: state.sessionBlocks.length + state.pendingUserBlocks.length + 1,
    kind: 'user',
    phase: 'final',
    content: { text, thinking: '' },
    tool: null,
    createdAt: new Date().toISOString()
  })
  return blockId
}

/** 用户提交后立即给出稳定的助手反馈，避免等待 Bridge 首个事件时对话流看起来没有响应。 */
export function appendPendingAssistantMessage(state: AgentConversationState, executionId: string): boolean {
  if (state.executionId !== executionId || state.pendingAssistantBlock !== null) return false
  pendingAssistantSeq += 1
  state.pendingAssistantBlock = {
    executionId,
    jobId: 0,
    blockId: `local-assistant:${pendingAssistantSeq}`,
    revision: 0,
    kind: 'assistant',
    phase: 'streaming',
    content: { text: '正在处理…', thinking: '' },
    tool: null,
    createdAt: new Date().toISOString()
  }
  return true
}

export function clearPendingAssistantMessage(state: AgentConversationState): void {
  state.pendingAssistantBlock = null
}

export function removePendingUserMessage(state: AgentConversationState, blockId: string): void {
  const index = state.pendingUserBlocks.findIndex((block) => block.blockId === blockId)
  if (index >= 0) state.pendingUserBlocks.splice(index, 1)
}

/** Pi session 快照是权威基线；只吸收已由该快照表示的临时块。 */
export function applySessionSnapshot(state: AgentConversationState, snapshot: SessionSnapshot): boolean {
  if (state.executionId !== snapshot.executionId) return false
  const absorbedRuntimeIds = new Set(
    snapshot.blocks
      .map((block) => block.runtimeBlockId)
      .filter((blockId): blockId is string => blockId !== null)
  )
  state.sessionBlocks.splice(0, state.sessionBlocks.length, ...snapshot.blocks)
  const snapshotUserTexts = snapshot.blocks
    .filter((block) => block.kind === 'user')
    .map((block) => block.content?.text ?? '')
  let cursor = 0
  const remainingPending: SessionDisplayBlock[] = []
  for (const pending of state.pendingUserBlocks) {
    const text = pending.content?.text ?? ''
    const matchedAt = snapshotUserTexts.indexOf(text, cursor)
    if (matchedAt >= 0) cursor = matchedAt + 1
    else remainingPending.push(pending)
  }
  state.pendingUserBlocks.splice(0, state.pendingUserBlocks.length, ...remainingPending)
  if (state.pendingAssistantBlock && remainingPending.length === 0
    && snapshot.blocks.some((block) => block.kind === 'assistant')) {
    state.pendingAssistantBlock = null
  }
  for (const blockId of absorbedRuntimeIds) state.runtimeById.delete(blockId)
  state.runtimeIds.splice(
    0,
    state.runtimeIds.length,
    ...state.runtimeIds.filter((blockId) => !absorbedRuntimeIds.has(blockId))
  )
  return true
}

/** 同一 runtime blockId 只接受更高 revision，首次出现顺序在当前轮内保持不变。 */
export function upsertRuntimeDisplayBlock(
  state: AgentConversationState,
  incoming: RuntimeDisplayBlock
): boolean {
  if (state.executionId !== incoming.executionId) return false
  // 已进入 Pi session 的同身份块由历史基线负责，拒绝迟到的 Runtime 包重新制造重复项。
  if (state.sessionBlocks.some((block) => block.runtimeBlockId === incoming.blockId)) return false
  state.pendingAssistantBlock = null
  const current = state.runtimeById.get(incoming.blockId)
  if (current && incoming.revision <= current.revision) return false
  state.runtimeById.set(incoming.blockId, incoming)
  if (!current) state.runtimeIds.push(incoming.blockId)
  return true
}

/** Job 已离开活动态时清除未收到终态的临时块，避免遗留 streaming 光标伪装成持续执行。 */
export function clearRuntimeDisplayBlocks(state: AgentConversationState): void {
  state.pendingAssistantBlock = null
  state.runtimeById.clear()
  state.runtimeIds.splice(0)
}

export function conversationDisplayBlocks(state: AgentConversationState): ConversationDisplayBlock[] {
  return [
    ...state.sessionBlocks,
    ...state.pendingUserBlocks,
    ...(state.pendingAssistantBlock ? [state.pendingAssistantBlock] : []),
    ...state.runtimeIds.map((blockId) => state.runtimeById.get(blockId)!)
  ]
}

export function formatAgentValue(value: unknown, indent = ''): string {
  if (value === null) return 'null'
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (Array.isArray(value)) {
    return value
      .map((item, index) => `${indent}${index + 1}. ${formatAgentValue(item, `${indent}  `)}`)
      .join('\n')
  }
  if (typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>)
      .map(([key, item]) => {
        const formatted = formatAgentValue(item, `${indent}  `)
        return typeof item === 'object' && item !== null
          ? `${indent}${key}:\n${formatted}`
          : `${indent}${key}: ${formatted}`
      })
      .join('\n')
  }
  return ''
}
