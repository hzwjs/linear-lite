import type { Run } from './serverClient.js'

export function activationMarker(run: Run): string {
  return `linear-lite-run:${run.id}`
}

export function visualActivationMarker(run: Run): string {
  return `LLRUN-${run.id.slice(0, 8).toUpperCase()}`
}

export function buildDesktopTaskPrompt(run: Run, worktreePath: string): string {
  if (!worktreePath) throw new Error('WORKTREE_PATH_REQUIRED')
  const snapshot = JSON.parse(run.taskSnapshot) as Record<string, unknown>
  const description = blockNoteText(snapshot.description)
  return `[${activationMarker(run)}]
[${visualActivationMarker(run)}]
Linear Lite 任务 ${snapshot.taskKey}：${snapshot.title}

任务目标：
${description}

补充指令：${run.dispatchInstruction}

执行环境：独立 Git worktree 和分支 ${run.branchName} 已准备完成，不需要重复核对。
执行目录（唯一）：${worktreePath}
所有代码读取、编辑、命令执行和验证只能在该目录内进行；不得使用 Codex 当前显示的项目根目录代替。
执行规则：若任务只是查询或问答，直接回答，不检查仓库、Git 或执行环境，不扩大任务范围；仅当任务明确要求修改代码时，才检查工作区并完成必要的实现和验证。不要提交、推送或创建合并请求。`
}

function blockNoteText(value: unknown): string {
  if (value === '') return ''
  if (typeof value !== 'string') throw new Error('TASK_DESCRIPTION_FORMAT_INVALID')
  const blocks = JSON.parse(value) as unknown
  if (!Array.isArray(blocks)) throw new Error('TASK_DESCRIPTION_FORMAT_INVALID')
  return blocks.map(blockText).filter(Boolean).join('\n')
}

function blockText(block: unknown): string {
  if (typeof block !== 'object' || block == null) throw new Error('TASK_DESCRIPTION_FORMAT_INVALID')
  const node = block as { content?: unknown; children?: unknown }
  const content = inlineText(node.content)
  if (!Array.isArray(node.children)) throw new Error('TASK_DESCRIPTION_FORMAT_INVALID')
  return [content, ...node.children.map(blockText).filter(Boolean)].filter(Boolean).join('\n')
}

function inlineText(content: unknown): string {
  if (!Array.isArray(content)) throw new Error('TASK_DESCRIPTION_FORMAT_INVALID')
  return content.map((item) => {
    if (typeof item !== 'object' || item == null || !('text' in item) || typeof item.text !== 'string') throw new Error('TASK_DESCRIPTION_FORMAT_INVALID')
    return item.text
  }).join('')
}
