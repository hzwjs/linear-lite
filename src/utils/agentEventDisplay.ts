import type { AgentTaskEvent } from '../services/api/agent'

type AgentEventPayload = {
  args?: unknown
  partialResult?: unknown
  result?: unknown
  content?: unknown
  role?: string
  toolName?: string
  toolCallId?: string | null
  isError?: boolean
}

const fieldLabels: Record<string, string> = {
  command: '命令',
  path: '路径',
  file: '文件',
  query: '查询',
  result: '结果',
  error: '错误',
  isError: '是否错误'
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function parseStructuredText(value: string): Record<string, unknown> | unknown[] | null {
  const text = value.trim()
  if ((!text.startsWith('{') || !text.endsWith('}')) && (!text.startsWith('[') || !text.endsWith(']'))) {
    return null
  }
  try {
    const parsed: unknown = JSON.parse(text)
    return isRecord(parsed) || Array.isArray(parsed) ? parsed : null
  } catch {
    return null
  }
}

function formatReadableValue(value: unknown, indent = ''): string {
  if (value == null) return ''
  if (typeof value === 'string') {
    // 命令行工具以文本返回 JSON；在唯一展示入口解析后按层级排版，避免页面暴露压缩 JSON。
    const structuredValue = parseStructuredText(value)
    return structuredValue ? formatReadableValue(structuredValue, indent) : value
  }
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)

  if (Array.isArray(value)) {
    const textItems = value.filter(
      (item): item is { type: 'text'; text: string } =>
        isRecord(item) && item.type === 'text' && typeof item.text === 'string'
    )
    if (textItems.length === value.length) {
      return textItems.map((item) => formatReadableValue(item.text, indent)).join('\n')
    }
    return value
      .map((item, index) => `${indent}${index + 1}. ${formatReadableValue(item, `${indent}  `)}`)
      .join('\n')
  }

  if (isRecord(value)) {
    const textContent = Array.isArray(value.content)
      ? value.content.filter(
          (item): item is { type: 'text'; text: string } =>
            isRecord(item) && item.type === 'text' && typeof item.text === 'string'
        )
      : []
    if (Array.isArray(value.content) && textContent.length === value.content.length) {
      return textContent.map((item) => formatReadableValue(item.text, indent)).join('\n')
    }
    return Object.entries(value)
      .map(([key, item]) => {
        const label = fieldLabels[key] ?? key
        const formatted = formatReadableValue(item, `${indent}  `)
        if (!formatted) return `${indent}${label}：`
        if (formatted.includes('\n') || Array.isArray(item) || isRecord(item)) {
          return `${indent}${label}：\n${formatted}`
        }
        return `${indent}${label}：${formatted}`
      })
      .join('\n')
  }

  return String(value)
}

function parsePayload(event: AgentTaskEvent): AgentEventPayload | null {
  try {
    return JSON.parse(event.payload) as AgentEventPayload
  } catch {
    return null
  }
}

export function agentEventToolCallId(event: AgentTaskEvent): string {
  const payload = parsePayload(event)
  return typeof payload?.toolCallId === 'string' ? payload.toolCallId : ''
}

export function agentEventSkillName(event: AgentTaskEvent): string {
  if (event.eventType !== 'tool_call') return ''
  const payload = parsePayload(event)
  if (!isRecord(payload?.args) || typeof payload.args.path !== 'string') return ''
  const match = payload.args.path.match(/\/skills\/([^/]+)\/SKILL\.md$/)
  return match?.[1] ?? ''
}

export function formatAgentToolCall(event: AgentTaskEvent): string {
  const payload = parsePayload(event)
  if (!isRecord(payload?.args)) return ''
  if (typeof payload.args.command !== 'string') return formatReadableValue(payload.args)

  const remainingArgs = Object.fromEntries(
    Object.entries(payload.args).filter(([key]) => key !== 'command')
  )
  const metadata = formatReadableValue(remainingArgs)
  return `$ ${payload.args.command}${metadata ? `\n${metadata}` : ''}`
}

function toolActivityLabel(toolName: string, command: string): string {
  if (toolName === 'read') return '正在读取文件…'
  if (toolName === 'write' || toolName === 'edit' || toolName === 'apply_patch') return '正在修改文件…'
  if (/\b(rg|grep|find|fd)\b/.test(command)) return '正在搜索文件…'
  if (toolName === 'bash') return '正在执行命令…'
  return `正在调用 ${toolName}…`
}

/** 将内部工具事件压缩为单条实时活动，避免把执行过程累积成页面日志。 */
export function formatAgentEventActivity(event: AgentTaskEvent): string {
  const payload = parsePayload(event)
  if (!payload) return '正在处理…'

  switch (event.eventType) {
    case 'started':
      return '正在启动 Pi…'
    case 'progress':
      return '正在整理结果…'
    case 'tool_call': {
      const toolName = payload.toolName ?? '工具'
      const args = isRecord(payload.args) ? payload.args : {}
      const command = typeof args.command === 'string' ? args.command : ''
      return toolActivityLabel(toolName, command)
    }
    case 'tool_result':
      return payload.isError === true ? '工具执行失败' : '工具执行完成'
    default:
      return ''
  }
}

/** 按事件类型读取固定字段，避免把原始 payload JSON 直接暴露给用户。 */
export function formatAgentEventDetail(event: AgentTaskEvent): string {
  const payload = parsePayload(event)
  if (!payload) return ''

  switch (event.eventType) {
    case 'tool_call':
      return formatReadableValue(payload.args)
    case 'tool_result':
      return formatReadableValue(payload.result)
    case 'progress':
      if (payload.role === 'assistant') return formatReadableValue(payload.content)
      if (payload.toolName) return formatReadableValue(payload.partialResult)
      return ''
    default:
      return ''
  }
}
