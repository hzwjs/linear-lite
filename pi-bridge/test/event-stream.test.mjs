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
    summary: '正在调用工具：weather · 参数：command: curl https://example.test/weather city: 南京',
    payload: {
      toolName: 'weather',
      toolCallId: 'call-1',
      args: { command: 'curl https://example.test/weather', city: '南京' },
    },
  })
})

test('tool updates are not retained as duplicate terminal output', () => {
  assert.equal(toProgressEvent({
    type: 'tool_execution_update',
    toolCallId: 'call-1',
    toolName: 'bash',
    args: { command: 'pwd' },
    partialResult: { content: [{ type: 'text', text: '/tmp/workspace' }] },
  }), null)
})

test('assistant message end exposes the same complete text shown by Pi terminal', () => {
  const event = toProgressEvent({
    type: 'message_end',
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
  assert.equal(event.summary, '工具调用失败：bash · permission denied')
  assert.equal(event.payload.isError, true)
})

test('agent end emits the terminal completion marker without duplicating final text', () => {
  assert.deepEqual(toProgressEvent({
    type: 'agent_end',
    willRetry: false,
    messages: [{ role: 'assistant', content: [{ type: 'text', text: '最终天气结果' }] }],
  }), {
    eventType: 'completed',
    summary: 'Pi 执行完成',
    payload: {},
  })
})

test('turn markers are not retained as terminal output', () => {
  assert.equal(toProgressEvent({ type: 'turn_start' }), null)
})

test('weather query keeps the same complete assistant text as Pi terminal', () => {
  const narrative = '我会查询广州明天的天气预报，并给出温度、降雨和出行建议。'
  const answer = '广州明天（8月13日）天气预报：\n\n- 天气：多云为主，间有阵雨\n- 气温：27～37℃'
  const messages = [
    { type: 'agent_start' },
    { type: 'turn_start' },
    { type: 'message_end', message: { role: 'assistant', content: [{ type: 'text', text: narrative }] } },
    { type: 'tool_execution_start', toolCallId: 'weather-1', toolName: 'bash', args: { command: 'curl weather' } },
    { type: 'tool_execution_update', toolCallId: 'weather-1', toolName: 'bash', partialResult: { content: [] } },
    { type: 'tool_execution_end', toolCallId: 'weather-1', toolName: 'bash', result: { content: [{ type: 'text', text: 'weather json' }] }, isError: false },
    { type: 'message_end', message: { role: 'assistant', content: [{ type: 'text', text: answer }] } },
    { type: 'agent_end', willRetry: false },
  ]

  const events = messages.map(toProgressEvent).filter(Boolean)
  assert.deepEqual(events.map((event) => event.eventType), [
    'started', 'progress', 'tool_call', 'tool_result', 'progress', 'completed',
  ])
  assert.equal(events[1].payload.content, narrative)
  assert.equal(events[4].payload.content, answer)
})

test('large skill output remains readable text after truncation', () => {
  const event = toProgressEvent({
    type: 'tool_execution_end',
    toolCallId: 'skill-1',
    toolName: 'read',
    result: { content: [{ type: 'text', text: `# ego-browser\n${'browser instructions\n'.repeat(500)}` }] },
    isError: false,
  })

  assert.equal(typeof event.payload.result, 'string')
  assert.match(event.payload.result, /^# ego-browser\nbrowser instructions/)
  assert.equal(event.payload.result.includes('\\n'), false)
  assert.match(event.payload.result, /…详情已截断$/)
})
