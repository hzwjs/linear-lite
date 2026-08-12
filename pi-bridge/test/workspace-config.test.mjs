import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { ProjectConfigStore } from '../src/workspace-config.mjs'
import { BridgeSettingsStore } from '../src/bridge-settings.mjs'
import { createConfigServer } from '../src/config-server.mjs'

function command(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let stderr = ''
    child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
    child.on('error', reject)
    child.on('close', (code) => code === 0 ? resolve() : reject(new Error(stderr || `${command} exited ${code}`)))
  })
}

async function gitRepository() {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-repository-'))
  await command('git', ['init', '--quiet', root])
  await command('git', ['-C', root, 'config', 'user.email', 'pi-bridge-test@example.test'])
  await command('git', ['-C', root, 'config', 'user.name', 'Pi Bridge Test'])
  await writeFile(join(root, 'README.md'), 'test\n')
  await command('git', ['-C', root, 'add', 'README.md'])
  await command('git', ['-C', root, 'commit', '--quiet', '-m', 'test'])
  return realpath(root)
}

test('project mapping saves, lists and resolves one exact repository path', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-config-'))
  const repository = await gitRepository()
  try {
    const store = new ProjectConfigStore(join(root, 'nested', 'projects.json'))
    const saved = await store.save(42, 'Engineering', repository)

    assert.equal(saved.projectId, 42)
    assert.equal(saved.projectName, 'Engineering')
    assert.equal(saved.repositoryPath, repository)
    assert.equal(await store.resolveRepository(42), repository)
    assert.deepEqual(await store.list(), [{ projectId: 42, projectName: 'Engineering', repositoryPath: repository, valid: true }])
    const config = JSON.parse(await readFile(join(root, 'nested', 'projects.json'), 'utf8'))
    assert.deepEqual(config.projects, [{ projectId: 42, projectName: 'Engineering', repositoryPath: repository }])
  } finally {
    await rm(root, { recursive: true, force: true })
    await rm(repository, { recursive: true, force: true })
  }
})

test('unconfigured and invalid repositories fail explicitly', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-config-'))
  const nonGitDirectory = join(root, 'not-git')
  await mkdir(nonGitDirectory)
  try {
    const store = new ProjectConfigStore(join(root, 'projects.json'))
    await assert.rejects(() => store.resolveRepository(99), /projectId 未配置本地仓库映射：99/)
    await assert.rejects(() => store.save(10, 'Not Git', nonGitDirectory), /不是有效 Git 仓库/)
    await assert.rejects(() => store.save(11, 'Relative', './relative-repository'), /必须是绝对路径/)

    const legacyConfig = join(root, 'legacy.json')
    await writeFile(legacyConfig, JSON.stringify({ workspaces: { engineering: nonGitDirectory } }))
    await assert.rejects(() => new ProjectConfigStore(legacyConfig).read(), /Bridge 配置文件格式无效/)
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})

test('local HTTP page reads, validates, saves and removes mappings', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-http-'))
  const repository = await gitRepository()
  const store = new ProjectConfigStore(join(root, 'projects.json'))
  const settingsStore = new BridgeSettingsStore(join(root, 'settings.json'))
  const configServer = createConfigServer({
    store,
    settingsStore,
    projectProvider: async () => [{ projectId: 42, projectName: 'Engineering' }],
    port: 0,
  })
  const address = await configServer.listen()
  const baseUrl = `http://127.0.0.1:${address.port}`
  try {
    const page = await fetch(`${baseUrl}/`)
    assert.equal(page.status, 200)
    const pageText = await page.text()
    assert.match(pageText, /Pi Bridge 配置/)
    assert.match(pageText, /Agent Token/)
    assert.match(pageText, /Linear Lite 项目/)
    assert.match(pageText, /添加项目绑定/)

    const initialSettings = await fetch(`${baseUrl}/api/settings`)
    assert.deepEqual(await initialSettings.json(), { apiBaseUrl: 'http://127.0.0.1:9080', configured: false })

    const savedSettings = await fetch(`${baseUrl}/api/settings`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ apiBaseUrl: 'http://127.0.0.1:9080/', agentToken: 'pi_test_token' }),
    })
    assert.equal(savedSettings.status, 200)
    assert.deepEqual(await savedSettings.json(), { apiBaseUrl: 'http://127.0.0.1:9080', configured: true })
    assert.match(await readFile(join(root, 'settings.json'), 'utf8'), /pi_test_token/)

    const health = await fetch(`${baseUrl}/healthz`, {
      headers: { Origin: 'http://localhost:5173' },
    })
    assert.equal(health.status, 200)
    assert.deepEqual(await health.json(), { status: 'ok' })
    assert.equal(health.headers.get('access-control-allow-origin'), 'http://localhost:5173')

    const empty = await fetch(`${baseUrl}/api/projects`)
    assert.deepEqual(await empty.json(), { projects: [] })

    const available = await fetch(`${baseUrl}/api/available-projects`)
    assert.deepEqual(await available.json(), { projects: [{ projectId: 42, projectName: 'Engineering' }] })

    const saved = await fetch(`${baseUrl}/api/projects/42`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectName: 'Engineering', repositoryPath: repository }),
    })
    assert.equal(saved.status, 200)
    assert.deepEqual(await saved.json(), { projectId: 42, projectName: 'Engineering', repositoryPath: repository })

    const invalid = await fetch(`${baseUrl}/api/projects/43`, {
      method: 'PUT',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ projectName: 'Broken', repositoryPath: nonGitPath(root) }),
    })
    assert.equal(invalid.status, 400)
    assert.match((await invalid.json()).message, /本地目录不存在/)

    const removed = await fetch(`${baseUrl}/api/projects/42`, { method: 'DELETE' })
    assert.equal(removed.status, 200)
    assert.deepEqual((await (await fetch(`${baseUrl}/api/projects`)).json()).projects, [])
  } finally {
    await configServer.close()
    await rm(root, { recursive: true, force: true })
    await rm(repository, { recursive: true, force: true })
  }
})

function nonGitPath(root) {
  return join(root, 'does-not-exist')
}
