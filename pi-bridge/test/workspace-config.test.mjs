import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { ProjectConfigStore } from '../src/workspace-config.mjs'
import { BridgeSettingsStore } from '../src/bridge-settings.mjs'
import { createConfigServer } from '../src/config-server.mjs'

test('project mapping saves, lists and resolves one exact local directory', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-config-'))
  const directoryPath = join(root, 'directory')
  await mkdir(directoryPath)
  const directory = await realpath(directoryPath)
  try {
    const store = new ProjectConfigStore(join(root, 'nested', 'projects.json'))
    const saved = await store.save(42, 'Engineering', directory)

    assert.equal(saved.projectId, 42)
    assert.equal(saved.projectName, 'Engineering')
    assert.equal(saved.directoryPath, directory)
    assert.equal(await store.resolveDirectory(42), directory)
    assert.deepEqual(await store.list(), [{ projectId: 42, projectName: 'Engineering', directoryPath: directory, valid: true }])
    const config = JSON.parse(await readFile(join(root, 'nested', 'projects.json'), 'utf8'))
    assert.deepEqual(config.projects, [{ projectId: 42, projectName: 'Engineering', directoryPath: directory }])
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('unconfigured and invalid local directories fail explicitly', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-config-'))
  const localDirectory = join(root, 'local')
  await mkdir(localDirectory)
  try {
    const store = new ProjectConfigStore(join(root, 'projects.json'))
    await assert.rejects(() => store.resolveDirectory(99), /projectId 未配置本地目录映射：99/)
    await assert.rejects(() => store.save(10, 'Missing', join(root, 'missing')), /本地目录不存在/)
    await assert.rejects(() => store.save(11, 'Relative', './relative-directory'), /必须是绝对路径/)

    const legacyConfig = join(root, 'legacy.json')
    await writeFile(legacyConfig, JSON.stringify({ workspaces: { engineering: localDirectory } }))
    await assert.rejects(() => new ProjectConfigStore(legacyConfig).read(), /Bridge 配置文件格式无效/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('local HTTP page reads, validates, saves and removes mappings', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-http-'))
  const directory = join(root, 'directory')
  await mkdir(directory)
  const store = new ProjectConfigStore(join(root, 'projects.json'))
  const settingsStore = new BridgeSettingsStore(join(root, 'settings.json'))
  const configServer = createConfigServer({
    store,
    settingsStore,
    projectProvider: async () => [{ projectId: 42, projectName: 'Engineering' }],
    healthProvider: () => ({
      status: 'online',
      configured: true,
      backendReachable: true,
      version: 'test',
      startedAt: '2026-08-17T00:00:00.000Z',
      lastConnectedAt: '2026-08-17T00:00:01.000Z',
    }),
    onConnectExecution: async ({ executionId, executionCredential, apiBaseUrl }) => {
      assert.equal(executionId, 'execution-1')
      assert.equal(executionCredential, 'execution-credential')
      assert.equal(apiBaseUrl, 'https://linear.example.com/linear-lite')
      return { executionId }
    },
    port: 0,
  })
  const address = await configServer.listen()
  const baseUrl = `http://127.0.0.1:${address.port}`
  try {
    const page = await fetch(`${baseUrl}/`)
    assert.equal(page.status, 200)
    const pageText = await page.text()
    assert.match(pageText, /Pi Bridge 本机诊断/)
    assert.doesNotMatch(pageText, /Bridge Credential/)
    assert.match(pageText, /项目设置 → 本地执行/)
    assert.match(pageText, /name="apiBaseUrl"/)
    assert.match(pageText, /Linear Lite 项目/)
    assert.match(pageText, /添加目录映射/)

    const initialSettings = await fetch(`${baseUrl}/api/settings`)
    assert.deepEqual(await initialSettings.json(), { configured: false, apiBaseUrl: '' })

    const manualSettings = await fetch(`${baseUrl}/api/settings`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiBaseUrl: 'https://linear.example.com/linear-lite' }),
    })
    assert.equal(manualSettings.status, 200)
    assert.deepEqual(await manualSettings.json(), { apiBaseUrl: 'https://linear.example.com/linear-lite' })

    const health = await fetch(`${baseUrl}/healthz`, {
      headers: { Origin: 'https://linear.example.com' },
    })
    assert.equal(health.status, 200)
    assert.deepEqual(await health.json(), {
      status: 'online',
      configured: true,
      backendReachable: true,
      version: 'test',
      startedAt: '2026-08-17T00:00:00.000Z',
      lastConnectedAt: '2026-08-17T00:00:01.000Z',
    })
    assert.equal(health.headers.get('access-control-allow-origin'), 'https://linear.example.com')

    const connected = await fetch(`${baseUrl}/api/executions`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', Origin: 'https://linear.example.com' },
      body: JSON.stringify({
        executionId: 'execution-1',
        executionCredential: 'execution-credential',
        apiBaseUrl: 'https://linear.example.com/linear-lite',
      }),
    })
    assert.equal(connected.status, 200)
    assert.deepEqual(await connected.json(), { executionId: 'execution-1' })
    assert.equal(connected.headers.get('access-control-allow-origin'), 'https://linear.example.com')

    const preflight = await fetch(`${baseUrl}/api/executions`, {
      method: 'OPTIONS',
      headers: {
        Origin: 'http://localhost:5173',
        'Access-Control-Request-Method': 'POST',
        'Access-Control-Request-Headers': 'content-type',
        'Access-Control-Request-Private-Network': 'true',
      },
    })
    assert.equal(preflight.status, 204)
    assert.equal(preflight.headers.get('access-control-allow-methods'), 'GET,POST,PUT,DELETE,OPTIONS')
    assert.equal(preflight.headers.get('access-control-allow-headers'), 'Content-Type')
    assert.equal(preflight.headers.get('access-control-allow-private-network'), 'true')

    const empty = await fetch(`${baseUrl}/api/projects`)
    assert.deepEqual(await empty.json(), { projects: [] })

    const available = await fetch(`${baseUrl}/api/available-projects`)
    assert.deepEqual(await available.json(), { projects: [{ projectId: 42, projectName: 'Engineering' }] })

    const saved = await fetch(`${baseUrl}/api/projects/42`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectName: 'Engineering', directoryPath: directory }),
    })
    assert.equal(saved.status, 200)
    assert.deepEqual(await saved.json(), { projectId: 42, projectName: 'Engineering', directoryPath: await realpath(directory) })

    const invalid = await fetch(`${baseUrl}/api/projects/43`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectName: 'Broken', directoryPath: missingDirectoryPath(root) }),
    })
    assert.equal(invalid.status, 400)
    assert.match((await invalid.json()).message, /本地目录不存在/)

    const removed = await fetch(`${baseUrl}/api/projects/42`, { method: 'DELETE' })
    assert.equal(removed.status, 200)
    assert.deepEqual((await (await fetch(`${baseUrl}/api/projects`)).json()).projects, [])
  } finally {
    await configServer.close()
    await rm(root, { recursive: true, force: true })
  }
})

function missingDirectoryPath(root) {
  return join(root, 'does-not-exist')
}
