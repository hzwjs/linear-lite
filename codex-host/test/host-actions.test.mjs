import test from 'node:test'
import assert from 'node:assert/strict'
import { HOST_ACTION_PROMPT, HOST_ACTION_TOOL, validateHostAction } from '../src/host-actions.mjs'

const accepted = [
  { action: 'observe', payload: {} },
  { action: 'claim', payload: { taskId: 5, inputRevision: 2 } },
  { action: 'list_files', payload: { path: '.' } },
  { action: 'read_file', payload: { path: 'docs/a.md' } },
  { action: 'create_file', payload: { path: 'a', content: '', operationId: 'op-1' } },
  { action: 'apply_patch', payload: { path: 'a', version: 'v1', oldText: 'x', newText: '', operationId: 'op-2' } },
  { action: 'replace_file', payload: { path: 'a', version: 'v1', content: '', operationId: 'op-3' } },
  { action: 'remove_file', payload: { path: 'a', version: 'v1', operationId: 'op-4' } },
  { action: 'rename_file', payload: { from: 'a', to: 'b', version: 'v1', operationId: 'op-5' } },
  { action: 'make_directory', payload: { path: 'a', operationId: 'op-6' } },
  { action: 'list_documents', payload: {} },
  { action: 'read_document', payload: { documentId: 3 } },
  { action: 'adopt_context', payload: { documentIds: [3], projectPaths: ['docs/a.md'] } },
  { action: 'change_dependency', payload: { action: 'ADD', prerequisiteTaskId: 4, missing: '材料', reason: '需要', evidence: '事实' } },
  { action: 'retract_dependency', payload: { relationOperationId: 'op-7', missing: '材料', reason: '判断有误', evidence: '事实' } },
  { action: 'change_dependency', payload: { action: 'REQUEST_GOAL', missing: '材料', reason: '需要', evidence: '事实', goal: '目标', impact: '影响' } },
  { action: 'split_task', payload: { reason: '包含接口与页面两个独立成果', subtasks: [{ title: '接口', description: '实现接口' }, { title: '页面' }] } },
  { action: 'review_prerequisite', payload: { sourceTaskId: 4, sourceAttemptId: 7, sourceInputRevision: 2, decision: 'ADOPT', finding: '满足', evidence: '检查', paths: ['docs/a.md'] } },
  { action: 'checkpoint', payload: { artifactRefs: [], finding: '发现' } },
  { action: 'deliver', payload: { conditions: [{ condition: '完成', result: '通过', evidenceRefs: ['docs/a.md'] }], artifactRefs: ['docs/a.md'] } },
  { action: 'stop', payload: { reason: '缺少信息' } },
]

test('action catalog advertises and accepts the same operations', () => {
  const advertised = HOST_ACTION_TOOL.inputSchema.properties.action.enum
  assert.deepEqual(new Set(advertised), new Set(accepted.map(item => item.action)))
  for (const input of accepted) {
    assert.equal(validateHostAction(input), input)
    assert.ok(HOST_ACTION_PROMPT.includes(`\n${input.action} `))
  }
  assert.match(HOST_ACTION_PROMPT, /通过 list_files 浏览目录/)
  assert.match(HOST_ACTION_PROMPT, /^deliver .*conditions 至少包含一项，每项必填 condition、result、evidenceRefs，.*evidenceRefs 为字符串数组/m)
})

test('Codex sees a named action and payload, and can correct the observed missing payload error', () => {
  const { type, properties, required, oneOf } = HOST_ACTION_TOOL.inputSchema
  assert.equal(type, 'object')
  assert.equal(oneOf, undefined)
  assert.equal(properties.action.type, 'string')
  assert.equal(properties.payload.type, 'object')
  assert.deepEqual(required, ['action', 'payload'])
  assert.match(HOST_ACTION_TOOL.description, /tools\.host_action\(\{ action: "observe", payload: \{\} \}\)/)
  assert.match(HOST_ACTION_PROMPT, /tools\.host_action\(\{ action: "claim", payload: \{ taskId, inputRevision \} \}\)/)
  assert.throws(() => validateHostAction({ action: 'observe' }), error =>
    error.code === 'TOOL_INPUT_INVALID' && /payload: \{\}/.test(error.message))
  assert.throws(() => validateHostAction({ action: 'deliver', payload: { conditions: [{}], artifactRefs: [] } }),
    /每项必填 condition、result、evidenceRefs/)
})

test('invalid and forged host parameters are rejected before dispatch', () => {
  const bad = [
    { action: 'shell', payload: { command: 'write' } },
    { action: 'observe', payload: {}, token: 'secret' },
    JSON.parse('{"action":"observe","payload":{"__proto__":{}}}'),
    { action: 'claim', payload: { taskId: 1, inputRevision: 1, batchToken: 'secret' } },
    { action: 'claim', payload: { taskId: 1.5, inputRevision: 1 } },
    { action: 'adopt_context', payload: { documentIds: [1], projectPaths: [], contentVersion: 'forged' } },
    { action: 'review_prerequisite', payload: { sourceTaskId: 4, sourceAttemptId: 7, sourceInputRevision: 2, decision: 'ADOPT', finding: 'x', evidence: 'y', paths: ['a'], contentVersion: 'forged' } },
    { action: 'change_dependency', payload: { action: 'ADD', missing: 'x', reason: 'y', evidence: 'z' } },
    { action: 'split_task', payload: { reason: 'x', subtasks: [] } },
    { action: 'split_task', payload: { reason: 'x', subtasks: Array.from({ length: 9 }, (_, i) => ({ title: `t${i}` })) } },
    { action: 'split_task', payload: { reason: 'x', subtasks: [{ title: 't', prerequisiteTaskId: 3 }] } },
    { action: 'change_dependency', payload: { action: 'REQUEST_GOAL', prerequisiteTaskId: 1, missing: 'x', reason: 'y', evidence: 'z', goal: 'g', impact: 'i' } },
    { action: 'deliver', payload: { conditions: [{ condition: 'x', result: 'y', evidenceRefs: [], changedPaths: ['secret'] }], artifactRefs: [] } },
    { action: 'deliver', payload: { conditions: [], artifactRefs: [] } },
  ]
  for (const input of bad) assert.throws(() => validateHostAction(input), error =>
    error.code === 'TOOL_INPUT_INVALID' && error.message.startsWith('宿主操作参数无效'))
})
