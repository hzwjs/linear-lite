const TOOL_EVENT_LABELS = {
  tool_execution_start: '正在调用工具',
  tool_execution_end: '工具调用完成',
}
const MAX_DETAIL_LENGTH = 6000
const MAX_SUMMARY_DETAIL_LENGTH = 180

function toolName(message) {
  return typeof message.toolName === 'string' && message.toolName.trim()
    ? message.toolName.trim()
    : '工具'
}

function jsonText(value) {
  if (typeof value === 'string') return value
  if (value == null) return ''
  return JSON.stringify(value)
}

function compactValue(value) {
  if (value === undefined) return null
  const text = jsonText(value)
  if (text.length <= MAX_DETAIL_LENGTH) return value
  // 大型工具结果按终端可读文本截断，不能退化成带转义符的 JSON 字符串。
  return `${readableValue(value).slice(0, MAX_DETAIL_LENGTH)}\n…详情已截断`
}

function readableValue(value) {
  if (value == null) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  if (
    value &&
    typeof value === 'object' &&
    !Array.isArray(value) &&
    Array.isArray(value.content) &&
    value.content.every((item) => item && item.type === 'text' && typeof item.text === 'string')
  ) {
    return value.content.map((item) => item.text).join('\n')
  }
  if (Array.isArray(value)) {
    const textItems = value.filter((item) => item && item.type === 'text' && typeof item.text === 'string')
    if (textItems.length === value.length) return textItems.map((item) => item.text).join('\n')
    return value.map((item, index) => `${index + 1}. ${readableValue(item)}`).join('\n')
  }
  if (typeof value === 'object') {
    return Object.entries(value)
      .map(([key, item]) => {
        const detail = readableValue(item)
        return detail ? `${key}: ${detail}` : `${key}:`
      })
      .join('\n')
  }
  return String(value)
}

function detailPreview(value) {
  return readableValue(value).replace(/\s+/g, ' ').trim().slice(0, MAX_SUMMARY_DETAIL_LENGTH)
}

function toolInputSummary(args) {
  const preview = detailPreview(args)
  return preview ? `参数：${preview}` : ''
}

function textFromMessage(message) {
  return message?.content
    ?.filter((item) => item?.type === 'text' && typeof item.text === 'string')
    .map((item) => item.text)
    .join('')
    .trim() ?? ''
}

/** agent_end.messages 是 Pi 对本次执行的最终消息快照，只取最后一条 assistant 文本。 */
export function extractFinalAssistantText(agentEnd) {
  const messages = Array.isArray(agentEnd?.messages) ? agentEnd.messages : []
  for (let index = messages.length - 1; index >= 0; index -= 1) {
    const message = messages[index]
    if (message?.role === 'assistant') {
      const text = textFromMessage(message)
      if (text) return text
    }
  }
  return ''
}

/** 将 Pi 终端保留的完整消息与工具结果转换为 SSE，跳过 token 增量和工具中间刷新。 */
export function toProgressEvent(message) {
  if (!message || typeof message.type !== 'string') return null
  if (message.type === 'agent_start') {
    return { eventType: 'started', summary: 'Pi 已开始处理任务', payload: {} }
  }
  if (message.type === 'message_end' && message.message?.role === 'assistant') {
    const text = textFromMessage(message.message)
    if (!text) return null
    return {
      eventType: 'progress',
      summary: `Pi：${detailPreview(text)}`,
      payload: {
        role: 'assistant',
        content: compactValue(text),
      },
    }
  }
  if (message.type === 'agent_end' && message.willRetry !== true) {
    return { eventType: 'completed', summary: 'Pi 执行完成', payload: {} }
  }
  if (message.type === 'tool_execution_start' || message.type === 'tool_execution_end') {
    const name = toolName(message)
    const detail = message.type === 'tool_execution_start'
      ? toolInputSummary(message.args)
      : detailPreview(message.result)
    const label = message.type === 'tool_execution_end' && message.isError
      ? '工具调用失败'
      : TOOL_EVENT_LABELS[message.type]
    return {
      eventType: message.type === 'tool_execution_start' ? 'tool_call' : 'tool_result',
      summary: `${label}：${name}${detail ? ` · ${detail}` : ''}`,
      payload: {
        toolName: name,
        toolCallId: typeof message.toolCallId === 'string' ? message.toolCallId : null,
        ...(message.type === 'tool_execution_start'
          ? { args: compactValue(message.args) }
          : { result: compactValue(message.result), isError: message.isError === true }),
      },
    }
  }
  return null
}
