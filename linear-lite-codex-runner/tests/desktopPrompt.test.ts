import { describe, expect, it } from 'vitest'
import { activationMarker, buildDesktopTaskPrompt, visualActivationMarker } from '../src/desktopPrompt.js'
import type { Run } from '../src/serverClient.js'

const run: Run = {
  id: 'cf8c998e-9b68-4e63-8bf4-2cd4368be214', taskKey: 'LINEAR-LITE-41',
  taskSnapshot: JSON.stringify({ taskKey: 'LINEAR-LITE-41', projectIdentifier: 'LINEAR-LITE', projectName: 'Linear Lite', title: '接入消息推送', description: JSON.stringify([{ type: 'paragraph', content: [{ type: 'text', text: '支持微信' }], children: [] }]), status: 'in_progress', priority: 'high', labels: ['后端'], dueDate: null, plannedStartDate: null }),
  dispatchInstruction: '先完成邮件渠道', repositoryId: 6, repositoryKey: 'linear-lite', baseBranch: 'main', branchName: 'codex/linear-lite-41-cf8c998e', status: 'claimed',
}
const worktreePath = '/Users/test/.linear-lite/worktrees/cf8c998e-9b68-4e63-8bf4-2cd4368be214'

describe('desktop task prompt', () => {
  it('uses the run id as the only activation marker', () => {
    expect(activationMarker(run)).toBe('linear-lite-run:cf8c998e-9b68-4e63-8bf4-2cd4368be214')
    expect(visualActivationMarker(run)).toBe('LLRUN-CF8C998E')
  })

  it('contains the exact task snapshot and the target branch', () => {
    const prompt = buildDesktopTaskPrompt(run, worktreePath)
    expect(prompt).toContain('[linear-lite-run:cf8c998e-9b68-4e63-8bf4-2cd4368be214]')
    expect(prompt).toContain('[LLRUN-CF8C998E]')
    expect(prompt).toContain('Linear Lite 任务 LINEAR-LITE-41')
    expect(prompt).toContain('支持微信')
    expect(prompt).toContain('codex/linear-lite-41-cf8c998e')
    expect(prompt).toContain(`执行目录（唯一）：${worktreePath}`)
    expect(prompt).toContain('所有代码读取、编辑、命令执行和验证只能在该目录内进行')
  })

  it('converts BlockNote blocks to task text without exposing editor JSON', () => {
    const prompt = buildDesktopTaskPrompt(run, worktreePath)

    expect(prompt).toContain('任务目标：\n支持微信')
    expect(prompt).not.toContain('"backgroundColor"')
    expect(prompt).not.toContain('"children"')
  })

  it('keeps the task goal ahead of engineering context and avoids irrelevant metadata', () => {
    const prompt = buildDesktopTaskPrompt(run, worktreePath)

    expect(prompt.indexOf('任务目标：')).toBeLessThan(prompt.indexOf('执行环境：'))
    expect(prompt).toContain('若任务只是查询或问答，直接回答')
    expect(prompt).toContain('仅当任务明确要求修改代码时')
    expect(prompt).not.toContain('状态：in_progress')
    expect(prompt).not.toContain('优先级：high')
    expect(prompt).not.toContain('计划开始日期：')
  })

  it('requires an explicit worktree execution directory without fallback', () => {
    expect(() => buildDesktopTaskPrompt(run, '')).toThrow('WORKTREE_PATH_REQUIRED')
  })
})
