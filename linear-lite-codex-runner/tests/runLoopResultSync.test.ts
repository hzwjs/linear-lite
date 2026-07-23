import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { syncFinishedDesktopRuns } from '../src/runLoop.js'

describe('syncFinishedDesktopRuns', () => {
  it('只写回一次完成结果，并在本地记录已同步', async () => {
    const root = await mkdtemp(join(tmpdir(), 'runner-result-sync-'))
    const stateDirectory = join(root, 'state')
    const codexDirectory = join(root, 'codex')
    const marker = 'linear-lite-run:run-1'
    await mkdir(join(stateDirectory, 'runs'), { recursive: true })
    await mkdir(join(codexDirectory, 'sessions', '2026', '07', '23'), { recursive: true })
    await writeFile(join(stateDirectory, 'runs', 'run-1.json'), JSON.stringify({ runId: 'run-1', worktreePath: '/tmp/worktree', branchName: 'codex/task-1', activationMarker: marker }))
    await writeFile(join(codexDirectory, 'sessions', '2026', '07', '23', 'rollout-thread-1.jsonl'), [
      { type: 'session_meta', payload: { id: 'thread-1' } },
      { type: 'response_item', payload: { type: 'message', role: 'user', content: [{ text: `[${marker}]\n任务` }], internal_chat_message_metadata_passthrough: { turn_id: 'turn-1' } } },
      { type: 'event_msg', payload: { type: 'task_complete', turn_id: 'turn-1', last_agent_message: '执行结果', duration_ms: 99 } },
    ].map(JSON.stringify).join('\n'))
    const complete = vi.fn(async () => undefined)

    await syncFinishedDesktopRuns(stateDirectory, codexDirectory, { complete })
    await syncFinishedDesktopRuns(stateDirectory, codexDirectory, { complete })

    expect(complete).toHaveBeenCalledTimes(1)
    expect(complete).toHaveBeenCalledWith('run-1', {
      status: 'completed', codexThreadId: 'thread-1', resultSummary: '执行结果',
      resultPayload: JSON.stringify({ source: 'codex_desktop_session', threadId: 'thread-1', turnId: 'turn-1', durationMs: 99 }),
      errorCode: null, errorMessage: null,
    })
    expect(JSON.parse(await readFile(join(stateDirectory, 'runs', 'run-1.json'), 'utf8')).resultSyncedAt).toBeTypeOf('string')
  })

  it('损坏的单个状态文件不会阻断其他 run 同步', async () => {
    const root = await mkdtemp(join(tmpdir(), 'runner-result-sync-corrupt-'))
    const stateDirectory = join(root, 'state')
    const codexDirectory = join(root, 'codex')
    const marker = 'linear-lite-run:run-valid'
    await mkdir(join(stateDirectory, 'runs'), { recursive: true })
    await mkdir(join(codexDirectory, 'sessions'), { recursive: true })
    await writeFile(join(stateDirectory, 'runs', 'a-corrupt.json'), '{')
    await writeFile(join(stateDirectory, 'runs', 'z-valid.json'), JSON.stringify({ runId: 'run-valid', worktreePath: '/tmp/worktree', branchName: 'codex/task-valid', activationMarker: marker }))
    await writeFile(join(codexDirectory, 'sessions', 'rollout-thread-valid.jsonl'), [
      { type: 'session_meta', payload: { id: 'thread-valid' } },
      { type: 'response_item', payload: { type: 'message', role: 'user', content: [{ text: `[${marker}]\n任务` }], internal_chat_message_metadata_passthrough: { turn_id: 'turn-valid' } } },
      { type: 'event_msg', payload: { type: 'task_complete', turn_id: 'turn-valid', last_agent_message: '有效结果', duration_ms: 1 } },
    ].map(JSON.stringify).join('\n'))
    const complete = vi.fn(async () => undefined)
    const error = vi.spyOn(console, 'error').mockImplementation(() => undefined)

    await syncFinishedDesktopRuns(stateDirectory, codexDirectory, { complete })

    expect(complete).toHaveBeenCalledOnce()
    expect(error).toHaveBeenCalledOnce()
    error.mockRestore()
  })
})
