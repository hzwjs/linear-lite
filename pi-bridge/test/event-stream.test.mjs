import assert from 'node:assert/strict'
import test from 'node:test'
import {
  RuntimeDisplayBlockAssembler,
  activeSessionBranch,
  createSessionSnapshot,
  finalAssistantText,
} from '../src/event-stream.mjs'

function assistant(timestamp = 1000) {
  return { role: 'assistant', timestamp, content: [] }
}

test('assistant deltas update one runtime block and message_end remains temporary', () => {
  const assembler = new RuntimeDisplayBlockAssembler()
  const [started] = assembler.accept({ type: 'message_start', message: assistant() })
  const [delta] = assembler.accept({
    type: 'message_update',
    assistantMessageEvent: { type: 'text_start', contentIndex: 0 },
  })
  const [updated] = assembler.accept({
    type: 'message_update',
    assistantMessageEvent: { type: 'text_delta', contentIndex: 0, delta: '检查中' },
  })
  const [ended] = assembler.accept({
    type: 'message_end',
    message: { ...assistant(), content: [{ type: 'text', text: '权威内容等待 session 快照' }] },
  })

  assert.equal(started.blockId, 'assistant:1000')
  assert.equal(delta.revision, 2)
  assert.equal(updated.content.text, '检查中')
  assert.equal(ended.phase, 'streaming')
  assert.equal(ended.content.text, '权威内容等待 session 快照')
})

test('tool partialResult replaces the same runtime block', () => {
  const assembler = new RuntimeDisplayBlockAssembler()
  assembler.accept({
    type: 'tool_execution_start', toolCallId: 'call-1', toolName: 'bash', args: { command: 'pwd' },
  })
  assembler.accept({
    type: 'tool_execution_update', toolCallId: 'call-1', toolName: 'bash',
    partialResult: { content: [{ type: 'text', text: 'partial' }] },
  })
  const [ended] = assembler.accept({
    type: 'tool_execution_end', toolCallId: 'call-1', toolName: 'bash', isError: false,
    result: { content: [{ type: 'text', text: '/tmp' }], details: { truncation: { truncated: false } } },
  })

  assert.equal(ended.blockId, 'call-1')
  assert.equal(ended.phase, 'streaming')
  assert.equal(ended.tool.content[0].text, '/tmp')
  assert.deepEqual(ended.tool.truncation, { truncated: false })
})

test('active branch follows leafId and excludes abandoned siblings', () => {
  const entries = [
    { type: 'model_change', id: 'root', parentId: null },
    { type: 'message', id: 'user', parentId: 'root' },
    { type: 'message', id: 'abandoned', parentId: 'user' },
    { type: 'message', id: 'active', parentId: 'user' },
  ]

  assert.deepEqual(activeSessionBranch(entries, 'active').map((entry) => entry.id), ['root', 'user', 'active'])
  assert.throws(() => activeSessionBranch(entries, 'missing'), /missing|\u7f3a少/)
})

test('session snapshot converts only terminal-visible active branch content and merges tool results', () => {
  const entries = [
    { type: 'model_change', id: 'root', parentId: null, timestamp: '2026-08-18T00:00:00Z' },
    {
      type: 'message', id: 'u1', parentId: 'root', timestamp: '2026-08-18T00:00:01Z',
      message: { role: 'user', content: [{ type: 'text', text: '检查当前修改' }] },
    },
    {
      type: 'message', id: 'a1', parentId: 'u1', timestamp: '2026-08-18T00:00:02Z',
      message: {
        role: 'assistant', timestamp: 1000, stopReason: 'toolUse', content: [
          { type: 'thinking', thinking: '先看文件' },
          { type: 'text', text: '我先检查。' },
          { type: 'toolCall', id: 'call-1', name: 'bash', arguments: { command: 'pwd' } },
        ],
      },
    },
    {
      type: 'message', id: 't1', parentId: 'a1', timestamp: '2026-08-18T00:00:03Z',
      message: {
        role: 'toolResult', toolCallId: 'call-1', toolName: 'bash', isError: false,
        content: [{ type: 'text', text: '/workspace' }], details: { truncation: null },
      },
    },
    {
      type: 'message', id: 'a2', parentId: 't1', timestamp: '2026-08-18T00:00:04Z',
      message: { role: 'assistant', timestamp: 2000, stopReason: 'stop', content: [{ type: 'text', text: '检查完成' }] },
    },
    {
      type: 'message', id: 'old', parentId: 'u1', timestamp: '2026-08-18T00:00:05Z',
      message: { role: 'assistant', content: [{ type: 'text', text: '废弃分支' }] },
    },
  ]
  const snapshot = createSessionSnapshot({
    executionId: 'exec-1', piSessionId: 'pi-1', entries, leafId: 'a2',
  })

  assert.equal(snapshot.blocks.length, 4)
  assert.deepEqual(snapshot.blocks.map((block) => block.order), [1, 2, 3, 4])
  assert.equal(snapshot.blocks[0].kind, 'user')
  assert.equal(snapshot.blocks[0].runtimeBlockId, null)
  assert.equal(snapshot.blocks[1].content.thinking, '先看文件')
  assert.equal(snapshot.blocks[1].runtimeBlockId, 'assistant:1000')
  assert.equal(snapshot.blocks[2].blockId, 'call-1')
  assert.equal(snapshot.blocks[2].runtimeBlockId, 'call-1')
  assert.equal(snapshot.blocks[2].tool.content[0].text, '/workspace')
  assert.equal(snapshot.blocks[3].runtimeBlockId, 'assistant:2000')
  assert.equal(snapshot.blocks[3].content.text, '检查完成')
  assert.equal(snapshot.blocks.some((block) => block.content?.text === '废弃分支'), false)
  assert.equal(finalAssistantText(snapshot), '检查完成')
})
