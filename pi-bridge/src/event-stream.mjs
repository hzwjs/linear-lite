const TOOL_EVENT_LABELS = {
  tool_execution_start: '正在调用工具',
  tool_execution_end: '工具调用完成',
}
const MAX_DETAIL_LENGTH = 6000

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
  return `${text.slice(0, MAX_DETAIL_LENGTH)}\n…详情已截断`
}

function detailPreview(value) {
  return jsonText(value).replace(/\s+/g, ' ').trim().slice(0, 180)
}

function toolInputSummary(args) {
  if (args && typeof args.command === 'string' && args.command.trim()) {
    return `命令：${args.command.trim().slice(0, 180)}`
  }
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

/** 将 Pi 生命周期事件转换为可展示的结构化进度，只暴露工具参数和结果，不暴露 assistant 内部过程文本。 */
export function toProgressEvent(message) {
  if (!message || typeof message.type !== 'string') return null
  if (message.type === 'agent_start') {
    return { eventType: 'started', summary: 'Pi 已开始处理任务', payload: {} }
  }
  if (message.type === 'turn_start') {
    return {
      eventType: 'progress',
      summary: `开始第 ${Number(message.turnIndex ?? 0) + 1} 轮处理`,
      payload: { turnIndex: message.turnIndex },
    }
  }
  if (message.type === 'message_update' && message.message?.role === 'assistant') {
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
  if (message.type === 'tool_execution_update') {
    const name = toolName(message)
    const detail = detailPreview(message.partialResult)
    return {
      eventType: 'progress',
      summary: `工具输出：${name}${detail ? ` · ${detail}` : ''}`,
      payload: {
        toolName: name,
        toolCallId: typeof message.toolCallId === 'string' ? message.toolCallId : null,
        partialResult: compactValue(message.partialResult),
      },
    }
  }
  return null
}
