import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import readline from 'node:readline'
import { fileURLToPath } from 'node:url'

const cli = fileURLToPath(new URL('../src/cli.mjs', import.meta.url))
test('serve starts the loopback identity endpoint without a model or batch', async () => {
  const state = await mkdtemp(path.join(tmpdir(), 'll122-cli-'))
  const child = spawn(process.execPath, [cli, 'serve', '--origin', 'https://example.test', '--state-dir', state], { stdio: ['ignore', 'pipe', 'pipe'] })
  try {
    const line = await Promise.race([
      new Promise((resolve, reject) => {
        readline.createInterface({ input: child.stdout }).once('line', resolve)
        child.once('exit', code => reject(new Error(`宿主启动失败: ${code}`)))
      }),
      new Promise((_, reject) => setTimeout(() => reject(new Error('宿主启动超时')), 5000)),
    ])
    assert.deepEqual(JSON.parse(line), { state: 'READY', port: 9781 })
    const response = await fetch('http://127.0.0.1:9781/identity', { headers: { Origin: 'https://example.test' } })
    assert.equal(response.status, 200)
    assert.match((await response.json()).deviceId, /^[a-f0-9-]{36}$/)
    const denied = await fetch('http://127.0.0.1:9781/identity', { headers: { Origin: 'https://other.test' } })
    assert.equal(denied.status, 403)
  } finally {
    child.kill('SIGTERM')
    if (child.exitCode === null) await new Promise(resolve => child.once('exit', resolve))
    await rm(state, { recursive: true, force: true })
  }
})

test('plan prints a disabled Asia Shanghai schedule', async () => {
  const child = spawn(process.execPath, [cli, 'plan'], { stdio: ['ignore', 'pipe', 'pipe'] })
  let out = ''; child.stdout.on('data', part => { out += part })
  const exit = await new Promise(resolve => child.once('exit', resolve))
  assert.equal(exit, 0)
  assert.deepEqual(JSON.parse(out), { cron: '0 1 * * *', timeZone: 'Asia/Shanghai', enabled: false })
})
