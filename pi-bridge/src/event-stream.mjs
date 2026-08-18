function emptyContent() {
  return { text: '', thinking: '' }
}

function requireObject(value, label) {
  if (value === null || typeof value !== 'object' || Array.isArray(value)) {
    throw new Error(`${label} 结构无效`)
  }
  return value
}

function messageItems(message) {
  if (typeof message.content === 'string') return [{ type: 'text', text: message.content }]
  if (!Array.isArray(message.content)) throw new Error('Pi message.content 结构无效')
  return message.content
}

function toolContent(result) {
  const source = requireObject(result, 'Pi tool result')
  if (!Array.isArray(source.content)) throw new Error('Pi tool result.content 结构无效')
  return source.content
    .filter((item) => item?.type === 'text' || item?.type === 'image')
    .map((item) => structuredClone(item))
}

function toolSnapshot(name, args, result, isError) {
  const source = requireObject(result, 'Pi tool result')
  return {
    name,
    arguments: structuredClone(requireObject(args, 'Pi tool arguments')),
    content: toolContent(source),
    isError,
    // Pi 0.84.1 的 details.truncation 是截断信息的唯一来源。
    truncation: source.details?.truncation ?? null,
  }
}

/**
 * 将 Pi RPC 增量归并为 RuntimeDisplayBlock。该类只表示当前轮临时层，
 * 不接收 user 消息，也不产生任何历史快照。
 */
export class RuntimeDisplayBlockAssembler {
  constructor() {
    this.blocks = new Map()
    this.messageParts = new Map()
    this.activeAssistantBlockId = null
  }

  accept(event) {
    if (!event || typeof event.type !== 'string') return []
    switch (event.type) {
      case 'message_start': return this.#startMessage(event.message)
      case 'message_update': return this.#updateMessage(event.assistantMessageEvent)
      case 'message_end': return this.#endMessage(event.message)
      case 'tool_execution_start': return this.#startTool(event)
      case 'tool_execution_update': return this.#updateTool(event)
      case 'tool_execution_end': return this.#endTool(event)
      default: return []
    }
  }

  sealOpenBlocksAsError() {
    const snapshots = []
    for (const block of this.blocks.values()) {
      if (block.phase !== 'streaming') continue
      snapshots.push(this.#replace(block.blockId, { ...block, phase: 'error' }))
    }
    return snapshots
  }

  #startMessage(message) {
    if (message?.role !== 'assistant') return []
    if (!Number.isFinite(message.timestamp)) throw new Error('Pi assistant message 缺少 timestamp')
    const blockId = `assistant:${message.timestamp}`
    this.activeAssistantBlockId = blockId
    const parts = new Map()
    messageItems(message).forEach((item, contentIndex) => {
      if (item?.type === 'text') parts.set(contentIndex, { type: 'text', value: item.text })
      if (item?.type === 'thinking') parts.set(contentIndex, { type: 'thinking', value: item.thinking })
    })
    this.messageParts.set(blockId, parts)
    return [this.#create({
      blockId,
      kind: 'assistant',
      phase: 'streaming',
      content: this.#assembledContent(parts),
      tool: null,
    })]
  }

  #updateMessage(delta) {
    const blockId = this.activeAssistantBlockId
    if (!blockId || !delta || !Number.isInteger(delta.contentIndex)) return []
    const parts = this.messageParts.get(blockId)
    const block = this.blocks.get(blockId)
    if (!parts || !block || block.phase !== 'streaming') return []

    switch (delta.type) {
      case 'text_start': parts.set(delta.contentIndex, { type: 'text', value: '' }); break
      case 'text_delta': {
        const part = parts.get(delta.contentIndex)
        if (!part || part.type !== 'text') return []
        part.value += delta.delta
        break
      }
      case 'text_end': parts.set(delta.contentIndex, { type: 'text', value: delta.content }); break
      case 'thinking_start': parts.set(delta.contentIndex, { type: 'thinking', value: '' }); break
      case 'thinking_delta': {
        const part = parts.get(delta.contentIndex)
        if (!part || part.type !== 'thinking') return []
        part.value += delta.delta
        break
      }
      case 'thinking_end': parts.set(delta.contentIndex, { type: 'thinking', value: delta.content }); break
      default: return []
    }
    return [this.#replace(blockId, { ...block, content: this.#assembledContent(parts) })]
  }

  #endMessage(message) {
    if (message?.role !== 'assistant') return []
    const blockId = `assistant:${message.timestamp}`
    const block = this.blocks.get(blockId)
    if (!block) return []
    const content = emptyContent()
    for (const item of messageItems(message)) {
      if (item?.type === 'text') content.text += item.text
      if (item?.type === 'thinking') content.thinking += item.thinking
    }
    const phase = message.stopReason === 'error' || message.stopReason === 'aborted' ? 'error' : 'streaming'
    const snapshot = this.#replace(blockId, { ...block, phase, content })
    this.messageParts.delete(blockId)
    if (this.activeAssistantBlockId === blockId) this.activeAssistantBlockId = null
    return [snapshot]
  }

  #startTool(event) {
    if (typeof event.toolCallId !== 'string' || typeof event.toolName !== 'string') return []
    return [this.#create({
      blockId: event.toolCallId,
      kind: 'tool',
      phase: 'streaming',
      content: null,
      tool: {
        name: event.toolName,
        arguments: structuredClone(requireObject(event.args, 'Pi tool arguments')),
        content: [],
        isError: false,
        truncation: null,
      },
    })]
  }

  #updateTool(event) {
    const block = this.blocks.get(event.toolCallId)
    if (!block || block.kind !== 'tool' || block.phase !== 'streaming') return []
    return [this.#replace(event.toolCallId, {
      ...block,
      // partialResult 是累计快照，必须直接替换。
      tool: toolSnapshot(event.toolName, block.tool.arguments, event.partialResult, false),
    })]
  }

  #endTool(event) {
    const block = this.blocks.get(event.toolCallId)
    if (!block || block.kind !== 'tool') return []
    return [this.#replace(event.toolCallId, {
      ...block,
      phase: event.isError === true ? 'error' : 'streaming',
      tool: toolSnapshot(event.toolName, block.tool.arguments, event.result, event.isError === true),
    })]
  }

  #assembledContent(parts) {
    const content = emptyContent()
    for (const [, part] of [...parts.entries()].sort(([left], [right]) => left - right)) {
      if (part.type === 'text') content.text += part.value
      if (part.type === 'thinking') content.thinking += part.value
    }
    return content
  }

  #create(block) {
    const snapshot = { ...block, revision: 1 }
    this.blocks.set(block.blockId, snapshot)
    return structuredClone(snapshot)
  }

  #replace(blockId, block) {
    const current = this.blocks.get(blockId)
    const snapshot = { ...block, revision: current.revision + 1 }
    this.blocks.set(blockId, snapshot)
    return structuredClone(snapshot)
  }
}

/** 从 leafId 严格沿 parentId 还原当前活动分支。 */
export function activeSessionBranch(entries, leafId) {
  if (!Array.isArray(entries)) throw new Error('Pi get_entries.entries 结构无效')
  if (leafId === null) return []
  if (typeof leafId !== 'string' || !leafId) throw new Error('Pi get_entries.leafId 结构无效')
  const byId = new Map(entries.map((entry) => [entry.id, entry]))
  const reversed = []
  const visited = new Set()
  let cursor = leafId
  while (cursor !== null) {
    if (visited.has(cursor)) throw new Error('Pi session parentId 存在循环')
    visited.add(cursor)
    const entry = byId.get(cursor)
    if (!entry) throw new Error(`Pi session 活动分支缺少 entry: ${cursor}`)
    reversed.push(entry)
    cursor = entry.parentId
  }
  return reversed.reverse()
}

function flushAssistantSegment(drafts, entry, segment) {
  if (segment.startIndex === null) return
  drafts.push({
    blockId: `${entry.id}:${segment.startIndex}`,
    entryId: entry.id,
    runtimeBlockId: `assistant:${entry.message.timestamp}`,
    kind: 'assistant',
    phase: entry.message.stopReason === 'error' || entry.message.stopReason === 'aborted' ? 'error' : 'final',
    content: { text: segment.text, thinking: segment.thinking },
    tool: null,
    createdAt: entry.timestamp,
  })
  segment.startIndex = null
  segment.text = ''
  segment.thinking = ''
}

/** 仅将 Pi 活动分支中终端可见的 message/tool 转换为稳定历史块。 */
export function createSessionSnapshot({ executionId, piSessionId, entries, leafId }) {
  if (!executionId || !piSessionId) throw new Error('SessionSnapshot 缺少 executionId 或 piSessionId')
  const drafts = []
  const toolDrafts = new Map()
  for (const entry of activeSessionBranch(entries, leafId)) {
    if (entry.type !== 'message') continue
    const message = requireObject(entry.message, 'Pi session message')
    const items = messageItems(message)
    if (message.role === 'user') {
      items.forEach((item, contentIndex) => {
        if (item?.type !== 'text') return
        drafts.push({
          blockId: `${entry.id}:${contentIndex}`,
          entryId: entry.id,
          runtimeBlockId: null,
          kind: 'user',
          phase: 'final',
          content: { text: item.text, thinking: '' },
          tool: null,
          createdAt: entry.timestamp,
        })
      })
      continue
    }
    if (message.role === 'assistant') {
      if (!Number.isFinite(message.timestamp)) throw new Error('Pi session assistant message 缺少 timestamp')
      const segment = { startIndex: null, text: '', thinking: '' }
      items.forEach((item, contentIndex) => {
        if (item?.type === 'text' || item?.type === 'thinking') {
          if (segment.startIndex === null) segment.startIndex = contentIndex
          if (item.type === 'text') segment.text += item.text
          else segment.thinking += item.thinking
          return
        }
        if (item?.type !== 'toolCall') return
        flushAssistantSegment(drafts, entry, segment)
        const tool = {
          blockId: item.id,
          entryId: entry.id,
          runtimeBlockId: item.id,
          kind: 'tool',
          phase: 'final',
          content: null,
          tool: {
            name: item.name,
            arguments: structuredClone(requireObject(item.arguments, 'Pi session tool arguments')),
            content: [],
            isError: false,
            truncation: null,
          },
          createdAt: entry.timestamp,
        }
        drafts.push(tool)
        toolDrafts.set(item.id, tool)
      })
      flushAssistantSegment(drafts, entry, segment)
      continue
    }
    if (message.role === 'toolResult') {
      const tool = toolDrafts.get(message.toolCallId)
      if (!tool) throw new Error(`Pi session 工具结果缺少调用: ${message.toolCallId}`)
      tool.phase = message.isError === true ? 'error' : 'final'
      tool.tool = toolSnapshot(tool.tool.name, tool.tool.arguments, message, message.isError === true)
    }
  }
  return {
    executionId,
    piSessionId,
    leafId,
    blocks: drafts.map((block, index) => ({ ...block, order: index + 1 })),
  }
}

export function finalAssistantText(snapshot) {
  const assistants = snapshot.blocks.filter((block) => block.kind === 'assistant' && block.content.text.trim())
  const last = assistants.at(-1)
  if (!last) return ''
  return assistants
    .filter((block) => block.entryId === last.entryId)
    .map((block) => block.content.text)
    .join('')
}
