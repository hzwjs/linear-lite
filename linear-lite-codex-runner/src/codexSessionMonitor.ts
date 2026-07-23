import { readFile, readdir } from 'node:fs/promises'
import { join } from 'node:path'

export type CodexTurnOutcome =
  | { status: 'completed'; threadId: string; turnId: string; result: string; durationMs: number }
  | { status: 'failed'; threadId: string; turnId: string; errorCode: 'CODEX_DESKTOP_TURN_ABORTED'; errorMessage: string }

interface SessionRecord { type?: unknown; payload?: Record<string, unknown> }

/** 直接从 Codex rollout 的用户提示词定位会话，activation marker 是唯一关联键。 */
export class CodexSessionMonitor {
  constructor(private readonly codexDirectory: string) {}

  async findOutcome(activationMarker: string): Promise<CodexTurnOutcome | null> {
    const sessionPaths = await findSessionFiles(join(this.codexDirectory, 'sessions'))
    for (const sessionPath of sessionPaths.sort().reverse()) {
      let contents: string
      try { contents = await readFile(sessionPath, 'utf8') } catch { continue }
      const outcome = parseSession(contents, activationMarker)
      if (outcome) return outcome
    }
    return null
  }
}

export function parseSession(contents: string, activationMarker: string): CodexTurnOutcome | null {
  const promptPrefix = `[${activationMarker}]\n`
  let threadId: string | null = null
  let turnId: string | null = null
  let completed: Record<string, unknown> | null = null
  let aborted: Record<string, unknown> | null = null
  for (const line of contents.split('\n')) {
    if (!line.trim()) continue
    let record: SessionRecord
    try { record = JSON.parse(line) as SessionRecord } catch { continue }
    const payload = record.payload
    if (!payload) continue
    if (record.type === 'session_meta' && typeof payload.id === 'string') threadId = payload.id
    if (record.type === 'response_item' && payload.type === 'message' && payload.role === 'user' && messageStartsWith(payload.content, promptPrefix)) {
      turnId = metadataTurnId(payload.internal_chat_message_metadata_passthrough)
      continue
    }
    if (turnId && record.type === 'event_msg' && payload.type === 'task_complete' && payload.turn_id === turnId) completed = payload
    if (turnId && record.type === 'event_msg' && payload.type === 'turn_aborted' && payload.turn_id === turnId) aborted = payload
  }
  if (!threadId || !turnId) return null
  if (completed?.turn_id === turnId) {
    const result = completed.last_agent_message
    if (typeof result !== 'string' || !result.trim()) return null
    return { status: 'completed', threadId, turnId, result: result.trim(), durationMs: numberValue(completed.duration_ms) }
  }
  if (aborted?.turn_id === turnId) {
    const reason = typeof aborted.reason === 'string' ? aborted.reason : 'unknown'
    return { status: 'failed', threadId, turnId, errorCode: 'CODEX_DESKTOP_TURN_ABORTED', errorMessage: `Codex 桌面任务已中止: ${reason}` }
  }
  return null
}

function messageStartsWith(content: unknown, prefix: string): boolean {
  if (!Array.isArray(content)) return false
  return content.some((item) => typeof item === 'object' && item !== null && typeof (item as { text?: unknown }).text === 'string' && ((item as { text: string }).text).startsWith(prefix))
}

function metadataTurnId(metadata: unknown): string | null {
  if (typeof metadata !== 'object' || metadata === null) return null
  const value = (metadata as { turn_id?: unknown }).turn_id
  return typeof value === 'string' ? value : null
}

function numberValue(value: unknown): number { return typeof value === 'number' && Number.isFinite(value) ? value : 0 }

async function findSessionFiles(directory: string): Promise<string[]> {
  const files: string[] = []
  const entries = await readdir(directory, { withFileTypes: true })
  for (const entry of entries) {
    const path = join(directory, entry.name)
    if (entry.isDirectory()) files.push(...await findSessionFiles(path))
    else if (entry.isFile() && entry.name.endsWith('.jsonl')) files.push(path)
  }
  return files
}
