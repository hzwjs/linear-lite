import test from 'node:test'
import assert from 'node:assert/strict'
import { extractFinalAssistantText, toProgressEvent } from '../src/event-stream.mjs'

test('final result only uses the last assistant message from agent_end', () => {
  const result = extractFinalAssistantText({
    messages: [
      { role: 'assistant', content: [{ type: 'text', text: '中间说明，不应写入评论' }] },
      { role: 'toolResult', content: [{ type: 'text', text: '工具输出' }] },
      { role: 'assistant', content: [{ type: 'text', text: '最终天气结果' }] },
    ],
  })
  assert.equal(result, '最终天气结果')
})

test('tool lifecycle exposes structured input details', () => {
  const event = toProgressEvent({
    type: 'tool_execution_start',
    toolCallId: 'call-1',
    toolName: 'weather',
    args: { command: 'curl https://example.test/weather', city: '南京' },
  })
  assert.deepEqual(event, {
    eventType: 'tool_call',
    summary: '正在调用工具：weather · 命令：curl https://example.test/weather',
    payload: {
      toolName: 'weather',
      toolCallId: 'call-1',
      args: { command: 'curl https://example.test/weather', city: '南京' },
    },
  })
})

test('tool updates expose partial output for realtime diagnosis', () => {
  const event = toProgressEvent({
    type: 'tool_execution_update',
    toolCallId: 'call-1',
    toolName: 'bash',
    args: { command: 'pwd' },
    partialResult: { content: [{ type: 'text', text: '/tmp/workspace' }] },
  })
  assert.equal(event.eventType, 'progress')
  assert.equal(event.summary, '工具输出：bash · {"content":[{"type":"text","text":"/tmp/workspace"}]}')
  assert.deepEqual(event.payload.partialResult, {
    content: [{ type: 'text', text: '/tmp/workspace' }],
  })
})

test('assistant updates expose the visible execution narrative', () => {
  const event = toProgressEvent({
    type: 'message_update',
    message: {
      role: 'assistant',
      content: [{ type: 'text', text: '我会查询长春明天的天气，并给出出行提示。' }],
    },
  })
  assert.equal(event.summary, 'Pi：我会查询长春明天的天气，并给出出行提示。')
  assert.equal(event.payload.content, '我会查询长春明天的天气，并给出出行提示。')
})

test('tool errors expose the returned error detail', () => {
  const event = toProgressEvent({
    type: 'tool_execution_end',
    toolCallId: 'call-1',
    toolName: 'bash',
    result: { content: [{ type: 'text', text: 'permission denied' }] },
    isError: true,
  })
  assert.equal(event.summary, '工具调用失败：bash · {"content":[{"type":"text","text":"permission denied"}]}')
  assert.equal(event.payload.isError, true)
})

test('assistant message events are not progress events', () => {
  assert.equal(toProgressEvent({
    type: 'message_end',
    message: { role: 'assistant', content: [{ type: 'text', text: '内部过程' }] },
  }), null)
})
