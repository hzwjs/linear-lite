import { bodyToPlainText } from './blockNoteHtml'

export interface InitialAgentPromptInput {
  taskKey: string
  title: string
  description: string
}

/**
 * Build the first instruction from the task editor's current draft values.
 * The description is deliberately flattened before it enters the prompt so
 * the Agent receives task context instead of the editor's storage format.
 */
export function buildInitialAgentPrompt({ taskKey, title, description }: InitialAgentPromptInput): string {
  const plainDescription = bodyToPlainText(description)
  const descriptionSection = plainDescription
    ? `任务描述：\n${plainDescription}`
    : '任务描述：'

  return [
    '请处理当前 Linear Lite 任务。',
    '',
    `任务编号：${taskKey.trim()}`,
    `任务标题：${title.trim()}`,
    descriptionSection,
    '',
    '请先理解任务目标，结合当前工作区完成实现；完成后说明实际改动和结果。',
    ''
  ].join('\n')
}
