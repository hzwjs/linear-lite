import { describe, expect, it } from 'vitest'
import { buildInitialAgentPrompt } from './agentPrompt'

describe('buildInitialAgentPrompt', () => {
  it('uses the task key, current editor values, and plain text description', () => {
    const prompt = buildInitialAgentPrompt({
      taskKey: ' LINEAR-LITE-103 ',
      title: '  优化任务详情页面UI  ',
      description: JSON.stringify([
        {
          id: 'block-1',
          type: 'paragraph',
          props: { textColor: 'default', backgroundColor: 'default', textAlignment: 'left' },
          content: [{ type: 'text', text: '固定 head，现在 head 是随滚动条滚动的。', styles: {} }],
          children: []
        }
      ])
    })

    expect(prompt).toBe(
      '请处理当前 Linear Lite 任务。\n\n' +
      '任务编号：LINEAR-LITE-103\n' +
      '任务标题：优化任务详情页面UI\n' +
      '任务描述：\n' +
      '固定 head，现在 head 是随滚动条滚动的。\n\n' +
      '请先理解任务目标，结合当前工作区完成实现；完成后说明实际改动和结果。\n'
    )
  })

  it('keeps the description section when the current description is empty', () => {
    expect(buildInitialAgentPrompt({ taskKey: 'LINEAR-LITE-103', title: '任务', description: '' }))
      .toContain('任务描述：\n\n请先理解任务目标')
  })
})
