import { Codex } from '@openai/codex-sdk'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { Run, ServerClient } from './serverClient.js'
import { sanitizeEvent } from './eventSanitizer.js'

type Output = { outcome: 'completed' | 'needs_input' | 'failed'; summary: string; question: string | null; changedFiles: string[]; verification: { command: string; result: string }[]; blockers: string[] }
const schema = { type: 'object', properties: { outcome: { type: 'string', enum: ['completed', 'needs_input', 'failed'] }, summary: { type: 'string' }, question: { type: ['string', 'null'] }, changedFiles: { type: 'array', items: { type: 'string' } }, verification: { type: 'array', items: { type: 'object', properties: { command: { type: 'string' }, result: { type: 'string' } }, required: ['command', 'result'], additionalProperties: false } }, blockers: { type: 'array', items: { type: 'string' } } }, required: ['outcome', 'summary', 'question', 'changedFiles', 'verification', 'blockers'], additionalProperties: false }
export class CodexExecutor {
  constructor(private readonly client: ServerClient, private readonly stateDirectory: string) {}
  private stateFile(run: Run) { return join(this.stateDirectory, 'runs', `${run.id}.json`) }
  async execute(run: Run, worktreePath: string): Promise<void> {
    await mkdir(join(this.stateDirectory, 'runs'), { recursive: true })
    let sequence = 0
    const emit = async (type: string, payload: unknown) => { const sanitized = sanitizeEvent(type, payload); if (sanitized) await this.client.event(run.id, ++sequence, type, sanitized) }
    const env: Record<string, string> = {}
    for (const key of ['PATH', 'CODEX_HOME', 'TMPDIR', 'CODEX_API_KEY']) if (process.env[key]) env[key] = process.env[key]!
    const codex = new Codex({ env })
    const message = run.codexThreadId ? await this.client.claimMessage(run.id) : null
    if (run.codexThreadId && !message) return
    const thread = run.codexThreadId ? codex.resumeThread(run.codexThreadId, { workingDirectory: worktreePath, sandboxMode: 'workspace-write', networkAccessEnabled: false }) : codex.startThread({ workingDirectory: worktreePath, sandboxMode: 'workspace-write', networkAccessEnabled: false })
    const snapshot = JSON.parse(run.taskSnapshot) as Record<string, unknown>
    const prompt = message ? `用户针对当前任务的补充指令：${message.content}` : `你正在执行 Linear Lite 任务 ${snapshot.taskKey}。\n项目：${snapshot.projectIdentifier} / ${snapshot.projectName}\n标题：${snapshot.title}\n描述：${snapshot.description ?? ''}\n状态：${snapshot.status}\n优先级：${snapshot.priority}\n标签：${(snapshot.labels as string[]).join(', ')}\n截止日期：${snapshot.dueDate ?? ''}\n计划开始日期：${snapshot.plannedStartDate ?? ''}\n派发补充指令：${run.dispatchInstruction}\n\n工作约束：当前目录是独立 Git worktree；遵守 AGENTS.md；完成范围内实现和验证；不提交、不推送、不创建合并请求；需要业务确认时返回 needs_input；最终响应必须符合给定 JSON Schema。`
    try {
      const streamed = await thread.runStreamed(prompt, { outputSchema: schema })
      let finalResponse = ''
      for await (const event of streamed.events) {
        if (event.type === 'turn.started' && message) await this.client.consumed(run.id, message.id)
        if (event.type === 'thread.started' && thread.id) { await writeFile(this.stateFile(run), JSON.stringify({ runId: run.id, codexThreadId: thread.id, worktreePath, branchName: run.branchName, lastSequenceNo: sequence })); await this.client.thread(run.id, thread.id) }
        if (event.type === 'item.completed') { const item = event.item as { type?: string; text?: string }; if (item.type === 'agent_message') finalResponse = item.text ?? ''; await emit('status_changed', { itemType: item.type }) }
        if (event.type === 'turn.failed') throw new Error(event.error.message)
      }
      const output = JSON.parse(finalResponse) as Output
      if (!output || !['completed', 'needs_input', 'failed'].includes(output.outcome)) throw new Error('CODEX_OUTPUT_INVALID')
      if (output.outcome === 'needs_input') { if (!output.question) throw new Error('CODEX_OUTPUT_INVALID'); await emit('input_requested', { question: output.question }); await this.client.needsInput(run.id, output.question); return }
      if (output.outcome === 'failed') { await this.client.complete(run.id, { status: 'failed', resultSummary: output.summary, resultPayload: JSON.stringify(output), errorCode: 'CODEX_OUTPUT_FAILED', errorMessage: output.blockers.join('\n') }); return }
      await emit('run_completed', { changedFiles: output.changedFiles, verification: output.verification })
      await this.client.complete(run.id, { status: 'completed', resultSummary: output.summary, resultPayload: JSON.stringify(output) })
    } catch (error) { const code = error instanceof Error && /^[A-Z_]+$/.test(error.message) ? error.message : 'CODEX_THREAD_START_FAILED'; await this.client.complete(run.id, { status: 'failed', errorCode: code, errorMessage: error instanceof Error ? error.message : String(error) }) }
  }
}
