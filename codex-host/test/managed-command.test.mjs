import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import { mkdir, mkdtemp, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { readFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { createManagedCommandGate } from '../src/managed-command.mjs'

const worker = fileURLToPath(new URL('../src/managed-command-worker.mjs', import.meta.url))

async function fixture(fn) {
  const tmp = await realpath(await mkdtemp(path.join(os.tmpdir(), 'linear-lite-command-test-')))
  const root = path.join(tmp, 'project'), state = path.join(tmp, 'state')
  await mkdir(root); await mkdir(state)
  try { await fn({ tmp, root, state }) } finally { await rm(tmp, { recursive: true, force: true }) }
}

function launchWorker({ root }) {
  // 单元测试使用临时目录和固定系统命令，不代表生产沙箱。
  return spawn(process.execPath, [worker], { cwd: root, detached: true,
    env: { PATH: '/usr/bin:/bin' }, stdio: ['pipe', 'pipe', 'ignore'] })
}

function options({ root, state }, overrides = {}) {
  return { projectDirectory: root, stateDirectory: state, attemptId: 31, agentInstanceId: 'agent_1',
    isAuthorized: () => true, knownCredentials: () => ['fixture-secret'],
    verifyBoundary: async () => true, verifyProcessGroup: async () => true, launchWorker, ...overrides }
}

test('没有经验证的启动器或卷验证器时拒绝', async () => fixture(async f => {
  await assert.rejects(createManagedCommandGate(options(f, { launchWorker: undefined })), /沙箱/)
  await assert.rejects(createManagedCommandGate(options(f, { verifyBoundary: undefined })), /沙箱/)
}))

test('先持久化 pending 和进程组再放行参数，重复标识不覆盖记录', async () => fixture(async f => {
  const file = path.join(f.state, 'command-operations/31/op_1.json')
  let started = false, released = false
  const gate = await createManagedCommandGate(options(f, { launchWorker: context => {
    const before = JSON.parse(readFileSync(file, 'utf8'))
    assert.equal(before.status, 'pending'); assert.equal(before.processGroupId, null)
    started = true
    const child = launchWorker(context), end = child.stdin.end.bind(child.stdin)
    child.stdin.end = data => {
      const after = JSON.parse(readFileSync(file, 'utf8'))
      assert.equal(after.status, 'pending'); assert.equal(after.processGroupId, child.pid)
      released = true
      return end(data)
    }
    return child
  } }))
  const result = await gate.run({ argv: ['/bin/echo', 'hello'], cwd: '.', timeoutMs: 5000, operationId: 'op_1' })
  assert.equal(result.exitCode, 0); assert.equal(result.stdout, 'hello\n')
  assert.equal(started, true); assert.equal(released, true)
  const before = await readFile(file, 'utf8')
  assert.equal(JSON.parse(before).status, 'completed')
  assert.equal(before.includes('hello'), false)
  await assert.rejects(gate.run({ argv: ['/bin/echo', 'replacement'], cwd: '.', timeoutMs: 5000, operationId: 'op_1' }))
  assert.equal(await readFile(file, 'utf8'), before)
}))

test('租约在放行前失效则拒绝启动', async () => fixture(async f => {
  let valid = true, launched = false
  const gate = await createManagedCommandGate(options(f, { isAuthorized: () => valid,
    verifyBoundary: async () => { valid = false; return true },
    launchWorker: () => { launched = true; throw new Error('unexpected') } }))
  await assert.rejects(gate.run({ argv: ['/bin/echo', 'hello'], cwd: '.', timeoutMs: 5000, operationId: 'op_2' }), /租约/)
  assert.equal(launched, false)
  assert.equal(JSON.parse(await readFile(path.join(f.state, 'command-operations/31/op_2.json'), 'utf8')).status, 'rejected')
}))

test('卷校验超时不会启动命令', async () => fixture(async f => {
  let launched = false
  const gate = await createManagedCommandGate(options(f, { verifyBoundary: () => new Promise(() => {}),
    launchWorker: () => { launched = true; throw new Error('unexpected') } }))
  await assert.rejects(gate.run({ argv: ['/bin/echo', 'hello'], cwd: '.', timeoutMs: 40, operationId: 'op_wait' }), /超时/)
  assert.equal(launched, false)
  const fact = JSON.parse(await readFile(path.join(f.state, 'command-operations/31/op_wait.json'), 'utf8'))
  assert.equal(fact.status, 'rejected'); assert.equal(fact.timedOut, true)
}))

test('参数数组支持空参数及 UTF-8 输出', async () => fixture(async f => {
  const gate = await createManagedCommandGate(options(f))
  const result = await gate.run({ argv: ['/usr/bin/printf', '%s%s', '', '测试'], cwd: '.', timeoutMs: 5000, operationId: 'op_utf8' })
  assert.equal(result.stdout, '测试')
}))

test('超时终止登记进程组并保留未知结果', async () => fixture(async f => {
  const gate = await createManagedCommandGate(options(f))
  await assert.rejects(gate.run({ argv: ['/bin/sleep', '30'], cwd: '.', timeoutMs: 300, operationId: 'op_3' }))
  const fact = JSON.parse(await readFile(path.join(f.state, 'command-operations/31/op_3.json'), 'utf8'))
  assert.equal(fact.status, 'unknown'); assert.equal(fact.timedOut, true)
  await assert.rejects(gate.run({ argv: ['/bin/echo', 'x'], cwd: '.', timeoutMs: 5000, operationId: 'op_4' }), /停止/)
}))

test('停止命令会终止进程组且等待结果记录', async () => fixture(async f => {
  const gate = await createManagedCommandGate(options(f))
  const marker = path.join(f.root, 'started')
  const running = gate.run({ argv: ['/bin/sh', '-c', 'echo ready > "$1"; sleep 30', 'sh', marker],
    cwd: '.', timeoutMs: 5000, operationId: 'op_5' }).then(() => null, error => error)
  let observed = false
  for (let i = 0; i < 100; i++) {
    try { await readFile(marker); observed = true; break } catch { await delay(20) }
  }
  assert.equal(observed, true)
  await gate.stop()
  assert.ok(await running instanceof Error)
  assert.equal(JSON.parse(await readFile(path.join(f.state, 'command-operations/31/op_5.json'), 'utf8')).status, 'unknown')
}))

test('拒绝已知凭据输出且不写入日志', async () => fixture(async f => {
  await writeFile(path.join(f.root, 'input'), 'fixture-secret')
  const gate = await createManagedCommandGate(options(f))
  await assert.rejects(gate.run({ argv: ['/bin/cat', 'input'], cwd: '.', timeoutMs: 5000, operationId: 'op_6' }), /凭据/)
  const record = await readFile(path.join(f.state, 'command-operations/31/op_6.json'), 'utf8')
  assert.equal(record.includes('fixture-secret'), false)
  assert.equal(JSON.parse(record).status, 'unknown')
}))

test('非法进程标识不会触发停止信号', async () => fixture(async f => {
  for (const pid of [1, -2]) {
    let signals = 0
    const gate = await createManagedCommandGate(options(f, { launchWorker: () => ({ pid }) }),
      { stopGroup: async () => { signals++ } })
    await assert.rejects(gate.run({ argv: ['/bin/echo'], cwd: '.', timeoutMs: 5000, operationId: `invalid_${pid}` }))
    assert.equal(signals, 0)
  }
}))

test('停止会唤醒等待中的边界校验', async () => fixture(async f => {
  let entered
  const started = new Promise(resolve => { entered = resolve })
  const gate = await createManagedCommandGate(options(f, { verifyBoundary: () => { entered(); return new Promise(() => {}) } }))
  const running = gate.run({ argv: ['/bin/echo'], cwd: '.', timeoutMs: 60000, operationId: 'wait_stop' }).catch(error => error)
  await started
  await gate.stop()
  assert.match((await running).message, /停止/)
}))

test('输出截断时不返回正文', async () => fixture(async f => {
  await writeFile(path.join(f.root, 'large'), 'x'.repeat(16382) + 'fixture-secret')
  const gate = await createManagedCommandGate(options(f))
  const result = await gate.run({ argv: ['/bin/cat', 'large'], cwd: '.', timeoutMs: 5000, operationId: 'truncated' })
  assert.equal(result.truncated, true)
  assert.equal(result.stdout, '')
  assert.equal(result.stderr, '')
}))

test('参数超出 worker 字节限制时拒绝启动', async () => fixture(async f => {
  let launched = false
  const gate = await createManagedCommandGate(options(f, { launchWorker: () => { launched = true } }))
  await assert.rejects(gate.run({ argv: ['/bin/echo', ...Array(5).fill('x'.repeat(30000))], cwd: '.', timeoutMs: 5000, operationId: 'large_args' }), /超过限制/)
  assert.equal(launched, false)
}))

test('停止失败且进程组未证实退出时保持冻结', async () => fixture(async f => {
  const fake = new EventEmitter()
  fake.pid = 40000
  fake.stdin = new PassThrough()
  fake.stdout = new PassThrough()
  let released
  const started = new Promise(resolve => { released = resolve })
  fake.stdin.on('finish', released)
  const gate = await createManagedCommandGate(options(f, { launchWorker: () => fake }), {
    stopGroup: async () => { throw new Error('fixture stop failure') }, isGone: () => false,
  })
  const running = gate.run({ argv: ['/bin/echo'], cwd: '.', timeoutMs: 5000, operationId: 'stop_failure' }).catch(error => error)
  await started
  await assert.rejects(gate.stop(), /未确认退出/)
  assert.ok(await running instanceof Error)
  await assert.rejects(gate.stop(), /未确认退出/)
}))

test('启动失败异步报错不会成为未处理异常', async () => fixture(async f => {
  const gate = await createManagedCommandGate(options(f, {
    launchWorker: () => spawn('/nonexistent-linear-lite-fixture', [], { stdio: ['pipe', 'pipe', 'ignore'] }),
  }))
  await assert.rejects(gate.run({ argv: ['/bin/echo'], cwd: '.', timeoutMs: 5000, operationId: 'spawn_error' }), /进程组无效/)
  await delay(30)
}))

test('进程组核验期间结束的 worker 不遗漏 close', async () => fixture(async f => {
  const fake = new EventEmitter()
  fake.pid = 40001
  fake.stdin = new PassThrough()
  fake.stdout = new PassThrough()
  const gate = await createManagedCommandGate(options(f, {
    launchWorker: () => { setTimeout(() => fake.emit('close', 1, null), 5); return fake },
    verifyProcessGroup: async () => { await delay(30); return true },
  }), { isGone: () => true, stopGroup: async () => {} })
  const start = Date.now()
  await assert.rejects(gate.run({ argv: ['/bin/echo'], cwd: '.', timeoutMs: 5000, operationId: 'early_close' }))
  assert.ok(Date.now() - start < 1000)
}))
