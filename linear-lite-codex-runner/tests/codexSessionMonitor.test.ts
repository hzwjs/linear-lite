import { mkdtemp, mkdir, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { CodexSessionMonitor, parseSession } from '../src/codexSessionMonitor.js'

const marker = 'linear-lite-run:run-1'

describe('CodexSessionMonitor', () => {
  it('会话标题被 Codex 自动重命名后仍通过 rollout marker 读取最终回答', async () => {
    const root = await mkdtemp(join(tmpdir(), 'codex-session-monitor-'))
    const sessionDirectory = join(root, 'sessions', '2026', '07', '23')
    await mkdir(sessionDirectory, { recursive: true })
    await writeFile(join(root, 'session_index.jsonl'), JSON.stringify({ id: 'thread-1', thread_name: '查询长春当天的天气' }) + '\n{"id":')
    await writeFile(join(sessionDirectory, 'rollout-thread-1.jsonl'), records([
      { type: 'session_meta', payload: { id: 'thread-1' } },
      responseUser('turn-1', `[${marker}]\n任务内容`),
      { type: 'event_msg', payload: { type: 'task_complete', turn_id: 'turn-1', last_agent_message: '最终 **结果**', duration_ms: 1234 } },
    ]))

    await expect(new CodexSessionMonitor(root).findOutcome(marker)).resolves.toEqual({
      status: 'completed', threadId: 'thread-1', turnId: 'turn-1', result: '最终 **结果**', durationMs: 1234,
    })
  })

  it('不会把其他会话内嵌的 marker 文本当成任务入口', () => {
    const content = records([
      { type: 'session_meta', payload: { id: 'thread-decoy' } },
      responseUser('turn-decoy', `历史记录包含 [${marker}]\n但不是开头`),
      { type: 'event_msg', payload: { type: 'task_complete', turn_id: 'turn-decoy', last_agent_message: '错误结果', duration_ms: 1 } },
    ])
    expect(parseSession(content, marker)).toBeNull()
  })

  it('会话后来有其他 turn 时仍返回 marker 对应 turn 的结果', () => {
    const content = records([
      { type: 'session_meta', payload: { id: 'thread-1' } },
      responseUser('turn-1', `[${marker}]\n任务内容`),
      { type: 'event_msg', payload: { type: 'task_complete', turn_id: 'turn-1', last_agent_message: '任务结果', duration_ms: 10 } },
      responseUser('turn-2', '用户后续追问'),
      { type: 'event_msg', payload: { type: 'task_complete', turn_id: 'turn-2', last_agent_message: '后续结果', duration_ms: 20 } },
    ])
    expect(parseSession(content, marker)).toMatchObject({ status: 'completed', turnId: 'turn-1', result: '任务结果' })
  })

  it('将匹配 turn 的中止事件映射为明确失败', () => {
    const content = records([
      { type: 'session_meta', payload: { id: 'thread-1' } },
      responseUser('turn-1', `[${marker}]\n任务内容`),
      { type: 'event_msg', payload: { type: 'turn_aborted', turn_id: 'turn-1', reason: 'interrupted' } },
    ])
    expect(parseSession(content, marker)).toEqual({
      status: 'failed', threadId: 'thread-1', turnId: 'turn-1', errorCode: 'CODEX_DESKTOP_TURN_ABORTED', errorMessage: 'Codex 桌面任务已中止: interrupted',
    })
  })
})

function responseUser(turnId: string, text: string) {
  return { type: 'response_item', payload: { type: 'message', role: 'user', content: [{ type: 'input_text', text }], internal_chat_message_metadata_passthrough: { turn_id: turnId } } }
}

function records(values: unknown[]): string { return values.map((value) => JSON.stringify(value)).join('\n') + '\n' }
