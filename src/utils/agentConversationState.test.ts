import { describe, expect, it } from 'vitest'
import type { RuntimeDisplayBlock, SessionSnapshot } from '../services/api/agent'
import {
  applySessionSnapshot,
  conversationDisplayBlocks,
  createAgentConversationState,
  resetAgentConversationState,
  upsertRuntimeDisplayBlock
} from './agentConversationState'

function runtime(blockId: string, revision: number, text: string): RuntimeDisplayBlock {
  return {
    executionId: 'exec-1',
    jobId: 9,
    blockId,
    revision,
    kind: 'assistant',
    phase: 'streaming',
    content: { text, thinking: '' },
    tool: null,
    createdAt: '2026-08-18T00:00:00Z'
  }
}

function runtimeTool(blockId: string, revision: number, text: string): RuntimeDisplayBlock {
  return {
    executionId: 'exec-1',
    jobId: 9,
    blockId,
    revision,
    kind: 'tool',
    phase: 'streaming',
    content: null,
    tool: {
      name: 'bash',
      arguments: { command: 'pwd' },
      content: [{ type: 'text', text }],
      isError: false,
      truncation: null
    },
    createdAt: '2026-08-18T00:00:00Z'
  }
}

function sessionBlock(
  blockId: string,
  runtimeBlockId: string | null,
  text: string
): SessionSnapshot['blocks'][number] {
  return {
    blockId: `entry:${blockId}`,
    entryId: 'entry-1',
    runtimeBlockId,
    order: 1,
    kind: 'assistant',
    phase: 'final',
    content: { text, thinking: '' },
    tool: null,
    createdAt: '2026-08-18T00:00:00Z'
  }
}

function sessionToolBlock(blockId: string, text: string): SessionSnapshot['blocks'][number] {
  return {
    blockId,
    entryId: 'entry-tool',
    runtimeBlockId: blockId,
    order: 1,
    kind: 'tool',
    phase: 'final',
    content: null,
    tool: {
      name: 'bash',
      arguments: { command: 'pwd' },
      content: [{ type: 'text', text }],
      isError: false,
      truncation: null
    },
    createdAt: '2026-08-18T00:00:00Z'
  }
}

function snapshot(text: string, blocks = [sessionBlock('assistant:runtime', 'assistant:runtime', text)]): SessionSnapshot {
  return {
    executionId: 'exec-1',
    piSessionId: 'pi-session-1',
    leafId: 'entry-1',
    blocks: blocks.map((block, index) => ({ ...block, order: index + 1 }))
  }
}

describe('Agent conversation baseline and runtime overlay', () => {
  it('upserts runtime revisions in place and rejects stale updates', () => {
    const state = createAgentConversationState()
    resetAgentConversationState(state, 'exec-1')

    expect(upsertRuntimeDisplayBlock(state, runtime('assistant:runtime', 1, '草稿'))).toBe(true)
    expect(upsertRuntimeDisplayBlock(state, runtime('assistant:runtime', 3, '最新'))).toBe(true)
    expect(upsertRuntimeDisplayBlock(state, runtime('assistant:runtime', 2, '乱序旧包'))).toBe(false)

    expect(conversationDisplayBlocks(state)).toHaveLength(1)
    expect(conversationDisplayBlocks(state)[0]?.content?.text).toBe('最新')
  })

  it('absorbs landed runtime blocks without dropping runtime blocks not yet written to Pi session', () => {
    const state = createAgentConversationState()
    resetAgentConversationState(state, 'exec-1')
    upsertRuntimeDisplayBlock(state, runtimeTool('call-landed', 1, '工具已落地'))
    upsertRuntimeDisplayBlock(state, runtime('assistant:not-landed', 1, '尚未落地'))

    expect(applySessionSnapshot(state, snapshot('工具终值', [
      sessionToolBlock('call-landed', '工具终值')
    ]))).toBe(true)

    const blocks = conversationDisplayBlocks(state)
    expect(blocks.map((block) => block.blockId)).toEqual(['call-landed', 'assistant:not-landed'])
    expect(state.runtimeIds).toEqual(['assistant:not-landed'])

    // HTTP/SSE 乱序到达的旧 Runtime 不能覆盖已落地的 Pi session 基线。
    expect(upsertRuntimeDisplayBlock(state, runtimeTool('call-landed', 2, '迟到旧包'))).toBe(false)
    expect(conversationDisplayBlocks(state).map((block) => block.blockId))
      .toEqual(['call-landed', 'assistant:not-landed'])
  })

  it('clears every current-turn runtime block once the final snapshot represents them', () => {
    const state = createAgentConversationState()
    resetAgentConversationState(state, 'exec-1')
    upsertRuntimeDisplayBlock(state, runtime('assistant:runtime', 1, '回答中'))
    upsertRuntimeDisplayBlock(state, runtimeTool('call-1', 1, '工具执行中'))

    applySessionSnapshot(state, snapshot('最终回答', [
      sessionBlock('assistant:runtime', 'assistant:runtime', '最终回答'),
      sessionToolBlock('call-1', '工具结果')
    ]))

    expect(state.runtimeIds).toHaveLength(0)
    expect(state.runtimeById.size).toBe(0)
    expect(conversationDisplayBlocks(state)).toHaveLength(2)
  })

  it('clears both layers only when execution changes and rejects a stale session snapshot', () => {
    const state = createAgentConversationState()
    resetAgentConversationState(state, 'exec-1')
    applySessionSnapshot(state, snapshot('旧 session'))

    resetAgentConversationState(state, 'exec-2')

    expect(applySessionSnapshot(state, snapshot('过期快照'))).toBe(false)
    expect(conversationDisplayBlocks(state)).toHaveLength(0)
  })
})
