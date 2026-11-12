import test from 'node:test'
import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import { createHash } from 'node:crypto'
import { once } from 'node:events'
import { chmod, cp, mkdtemp, mkdir, readFile, rm, writeFile, access } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { HostInstaller, LABEL, MARKER, launchAgentPlist } from '../install/manager.mjs'
import { writeHostConfig } from '../src/host-config.mjs'

const deviceId = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

async function bundle(directory, version, marker = MARKER) {
  await mkdir(path.join(directory, 'Contents', 'Resources', 'runtime'), { recursive: true })
  await mkdir(path.join(directory, 'Contents', 'Resources', 'codex-host', 'src'), { recursive: true })
  await writeFile(path.join(directory, 'Contents', 'Resources', 'linear-lite-host.marker'), marker)
  await writeFile(path.join(directory, 'Contents', 'Resources', 'runtime', 'node'), 'fixture runtime')
  const helper = path.join(directory, 'Contents', 'Resources', 'runtime', 'project-volume-info')
  await writeFile(helper, 'fixture helper')
  await chmod(helper, 0o755)
  const volumeHelperSha256 = createHash('sha256').update('fixture helper').digest('hex')
  await writeFile(path.join(directory, 'Contents', 'Resources', 'source-manifest.json'),
    JSON.stringify({ manifestVersion: 1, runtime: { volumeHelperSha256 } }))
  await writeFile(path.join(directory, 'Contents', 'Resources', 'codex-host', 'src', 'cli.mjs'), version)
}

async function fixture() {
  const homeDirectory = await mkdtemp(path.join(os.tmpdir(), 'll133-installer-'))
  const source = path.join(homeDirectory, 'installer.app')
  await bundle(source, 'new version')
  const calls = [], requests = [], removed = []
  let loaded = false, program, failNextBootstrap = false, statusPid = 4141, listeners = [], protocolVersion = 3, connection = 'ONLINE', postPid = null
  let onBootstrap = async () => {}
  let onPortOwners = async () => {}
  const runLaunchctl = async args => {
    calls.push(args)
    if (args[0] === 'print') {
      if (!loaded) { const error = new Error('absent'); error.code = 113; throw error }
      return `program = ${program}\npid = 4141\n`
    }
    if (args[0] === 'bootstrap') {
      if (failNextBootstrap) { failNextBootstrap = false; throw new Error('fixture bootstrap failure') }
      loaded = true; await onBootstrap(); return ''
    }
    if (args[0] === 'bootout') { loaded = false; return '' }
    throw new Error('unexpected launchctl operation')
  }
  const fetchImpl = async (url, options) => {
    requests.push({ url, method: options.method, origin: options.headers.Origin, body: options.body })
    if (!loaded) throw new Error('offline')
    return new Response(JSON.stringify({ deviceId, hostInstanceId: 'host-1',
      processId: options.method === 'POST' && postPid !== null ? postPid : statusPid,
      connection, protocolVersion, userId: 7, grantId: 9, deviceName: '测试 Mac', lastHeartbeatAt: '2026-11-10T00:00:00Z' }),
    { status: options.method === 'POST' && url.endsWith('/connect') ? 202 : 200,
      headers: { 'Content-Type': 'application/json' } })
  }
  const installer = new HostInstaller({ homeDirectory, uid: 501, runLaunchctl, fetchImpl,
    portOwners: async () => { await onPortOwners(); return listeners }, copyApp: (source, target) => cp(source, target, { recursive: true }),
    verifySignature: async () => {},
    sleep: async () => {} })
  program = installer.nodePath
  await mkdir(installer.stateDirectory, { recursive: true })
  await writeFile(path.join(installer.stateDirectory, 'device.json'), JSON.stringify({ deviceId }))
  return { homeDirectory, source, installer, calls, requests, removed,
    setLoaded(value) { loaded = value }, setProgram(value) { program = value },
    failBootstrap() { failNextBootstrap = true }, setStatusPid(value) { statusPid = value },
    setListeners(value) { listeners = value }, setProtocol(value) { protocolVersion = value }, setConnection(value) { connection = value },
    setPostPid(value) { postPid = value },
    setOnBootstrap(value) { onBootstrap = value }, setOnPortOwners(value) { onPortOwners = value } }
}

test('user installer registers one LaunchAgent, offers status and preserves device on uninstall', async () => {
  const f = await fixture(), { installer } = f
  try {
    assert.match(await installer.install(f.source, 'https://linear.example/'), /安装完成/)
    assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://linear.example')
    assert.equal(await readFile(installer.plistPath, 'utf8'), launchAgentPlist(f.homeDirectory))
    const plist = await readFile(installer.plistPath, 'utf8')
    assert.match(plist, /<key>RunAtLoad<\/key><true\/>/)
    assert.match(plist, /<key>KeepAlive<\/key><true\/>/)
    assert(!plist.includes('fixture-secret'))
    assert.equal(await installer.status(), '宿主状态：已连接')
    assert.match(await installer.connect(), /浏览器/)
    assert.equal((await installer.disconnect()), '宿主授权已撤销。')
    await writeFile(path.join(installer.stateDirectory, 'user-note.txt'), 'preserve')
    assert.match(await installer.uninstall(), /卸载/)
    await assert.rejects(access(installer.appPath)); await assert.rejects(access(installer.plistPath))
    assert.equal(JSON.parse(await readFile(path.join(installer.stateDirectory, 'device.json'), 'utf8')).deviceId, deviceId)
    assert.equal(await readFile(path.join(installer.stateDirectory, 'user-note.txt'), 'utf8'), 'preserve')
    assert.deepEqual(f.removed, [])
    assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://linear.example')
    assert(f.calls.some(call => call[0] === 'bootstrap'))
    assert(f.calls.some(call => call[0] === 'bootout' && call[1] === `gui/501/${LABEL}`))
    assert(f.requests.every(req => req.origin === 'https://linear.example'))
    assert(f.requests.filter(req => req.method === 'POST').every(req => req.body === '{}'))
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('installer rejects a missing or changed project-volume helper before service changes', async () => {
  for (const scenario of ['missing', 'changed', 'signature']) {
    const f = await fixture(), { installer } = f
    try {
      const helper = path.join(f.source, 'Contents', 'Resources', 'runtime', 'project-volume-info')
      if (scenario === 'missing') await rm(helper)
      if (scenario === 'changed') await writeFile(helper, 'changed helper')
      if (scenario === 'signature') installer.verifySignature = async () => { throw new Error('signature failure') }
      await assert.rejects(installer.install(f.source, 'https://linear.example'))
      assert.equal(f.calls.length, 0)
      await assert.rejects(access(installer.appPath))
    } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
  }
})

test('installer checks staged helper before stopping the previous service', async () => {
  const f = await fixture(), { installer } = f
  try {
    await bundle(installer.appPath, 'old version')
    await mkdir(path.dirname(installer.plistPath), { recursive: true })
    await writeFile(installer.plistPath, installer.plist)
    await writeHostConfig(installer.stateDirectory, 'https://old.example')
    f.setLoaded(true)
    installer.copyApp = async (source, target) => {
      await cp(source, target, { recursive: true })
      await writeFile(path.join(target, 'Contents', 'Resources', 'runtime', 'project-volume-info'), 'damaged')
    }
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /完整性校验失败/)
    assert(!f.calls.some(call => call[0] === 'bootout'))
    assert.equal(await readFile(path.join(installer.appPath, 'Contents', 'Resources', 'codex-host', 'src', 'cli.mjs'), 'utf8'), 'old version')
    assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://old.example')
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('upgrade can identify a closed previous installation without a helper', async () => {
  const f = await fixture(), { installer } = f
  try {
    await bundle(installer.appPath, 'old version')
    await rm(path.join(installer.appPath, 'Contents', 'Resources', 'runtime', 'project-volume-info'))
    await rm(path.join(installer.appPath, 'Contents', 'Resources', 'source-manifest.json'))
    await mkdir(path.dirname(installer.plistPath), { recursive: true })
    await writeFile(installer.plistPath, installer.plist)
    await writeHostConfig(installer.stateDirectory, 'https://old.example')
    f.setLoaded(true)
    assert.match(await installer.install(f.source, 'https://linear.example'), /安装完成/)
    assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://linear.example')
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('frozen work, foreign app and foreign launch service cannot be overwritten', async () => {
  const f = await fixture(), { installer } = f
  try {
    await mkdir(path.join(installer.stateDirectory, 'runs', 'fixture'), { recursive: true })
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /待确认/)
    assert.equal(f.calls.length, 0)
    await rm(path.join(installer.stateDirectory, 'runs'), { recursive: true })
    await mkdir(installer.appPath, { recursive: true })
    await writeFile(path.join(installer.appPath, 'foreign.txt'), 'preserve')
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /归属无效/)
    assert.equal(await readFile(path.join(installer.appPath, 'foreign.txt'), 'utf8'), 'preserve')
    await rm(installer.appPath, { recursive: true })
    f.setLoaded(true); f.setProgram('/another/program')
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /不属于/)
    assert(!f.calls.some(call => call[0] === 'bootout'))
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('runs, attempt and process journals block install and uninstall without touching existing service', async () => {
  for (const [directory, file] of [['runs/batch', '1.json'], ['attempts/batch/1', 'agent.json'],
    ['process-groups/batch/1', 'agent.json']]) {
    const f = await fixture(), { installer } = f
    try {
      const journal = path.join(installer.stateDirectory, directory)
      await mkdir(journal, { recursive: true })
      await writeFile(path.join(journal, file), '{}')
      await assert.rejects(installer.install(f.source, 'https://linear.example'), /待确认/)
      await assert.rejects(installer.uninstall(), /待确认/)
      assert.equal(f.calls.length, 0)
      assert.equal(JSON.parse(await readFile(path.join(installer.stateDirectory, 'device.json'), 'utf8')).deviceId, deviceId)
    } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
  }
})

test('new protocol ignores old roots while old installations require their prior work to be closed', async () => {
  const f = await fixture(), { installer } = f
  try {
    const roots = path.join(installer.stateDirectory, 'roots', 'former')
    await mkdir(roots, { recursive: true })
    await writeFile(path.join(roots, 'owner.json'), '{}')
    await bundle(installer.appPath, 'legacy', 'linear-lite-codex-host-v1\n')
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /旧宿主存在待确认/)
    assert.equal(await readFile(path.join(roots, 'owner.json'), 'utf8'), '{}')
    await rm(installer.appPath, { recursive: true })
    assert.match(await installer.install(f.source, 'https://linear.example'), /安装完成/)
    assert.match(await installer.uninstall(), /卸载/)
    assert.equal(await readFile(path.join(roots, 'owner.json'), 'utf8'), '{}')
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('upgrade accepts managed old application after its work is closed', async () => {
  const f = await fixture(), { installer } = f
  try {
    await bundle(installer.appPath, 'legacy', 'linear-lite-codex-host-v1\n')
    await mkdir(path.dirname(installer.plistPath), { recursive: true })
    await writeFile(installer.plistPath, installer.plist)
    await writeHostConfig(installer.stateDirectory, 'https://old.example')
    f.setLoaded(true)
    assert.match(await installer.install(f.source, 'https://linear.example'), /安装完成/)
    assert.equal(await readFile(path.join(installer.appPath, 'Contents', 'Resources', 'linear-lite-host.marker'), 'utf8'), MARKER)
    assert.equal(JSON.parse(await readFile(path.join(installer.stateDirectory, 'device.json'), 'utf8')).deviceId, deviceId)
    assert.equal(f.requests.some(request => request.url.endsWith('/disconnect')), false)
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('readiness rejects an old protocol and restores the managed application', async () => {
  const f = await fixture(), { installer } = f
  try {
    await bundle(installer.appPath, 'legacy', 'linear-lite-codex-host-v1\n')
    await mkdir(path.dirname(installer.plistPath), { recursive: true })
    await writeFile(installer.plistPath, installer.plist)
    await writeHostConfig(installer.stateDirectory, 'https://old.example')
    f.setLoaded(true); f.setProtocol(1)
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /版本需要升级/)
    assert.equal(await readFile(path.join(installer.appPath, 'Contents', 'Resources', 'codex-host', 'src', 'cli.mjs'), 'utf8'), 'legacy')
    assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://old.example')
    assert.equal((await installer.serviceState()).loaded, true)
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('refuses protocol mismatch before connect or disconnect without contacting authorization endpoint', async () => {
  const f = await fixture(), { installer } = f
  try {
    await installer.install(f.source, 'https://linear.example')
    f.setProtocol(undefined)
    await assert.rejects(installer.status(), /版本需要升级/)
    await assert.rejects(installer.connect(), /版本需要升级/)
    await assert.rejects(installer.disconnect(), /版本需要升级/)
    assert.equal(f.requests.some(request => request.method === 'POST'), false)
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('installs a matching local host when the connected service requires an upgrade', async () => {
  const f = await fixture(), { installer } = f
  try {
    f.setConnection('UPGRADE_REQUIRED')
    assert.match(await installer.install(f.source, 'https://linear.example'), /安装完成/)
    assert.match(await installer.status(), /协作服务版本不匹配/)
    await assert.rejects(installer.connect(), /协作服务版本不匹配/)
    assert.equal(f.requests.some(request => request.method === 'POST'), false)
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('does not replace an installer after startup creates an unconfirmed batch', async () => {
  const f = await fixture(), { installer } = f
  try {
    await bundle(installer.appPath, 'legacy', 'linear-lite-codex-host-v1\n')
    await mkdir(path.dirname(installer.plistPath), { recursive: true })
    await writeFile(installer.plistPath, installer.plist)
    await writeHostConfig(installer.stateDirectory, 'https://old.example')
    f.setLoaded(true); f.setStatusPid(9999)
    f.setOnBootstrap(async () => {
      const directory = path.join(installer.stateDirectory, 'runs', 'unconfirmed')
      await mkdir(directory, { recursive: true })
      await writeFile(path.join(directory, '1.json'), '{}')
    })
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /待确认/)
    assert.equal(await readFile(path.join(installer.appPath, 'Contents', 'Resources', 'codex-host', 'src', 'cli.mjs'), 'utf8'), 'new version')
    assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://linear.example')
    assert.equal((await installer.serviceState()).loaded, true)
    const siblings = await (await import('node:fs/promises')).readdir(path.dirname(installer.appPath))
    assert(siblings.some(name => name.includes('.backup-')))
    assert.equal(await readFile(path.join(installer.stateDirectory, 'runs', 'unconfirmed', '1.json'), 'utf8'), '{}')
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

for (const action of ['install', 'uninstall']) {
  test(`checks journals after port release during ${action}`, async () => {
    const f = await fixture(), { installer } = f
    try {
      await bundle(installer.appPath, 'old version')
      await mkdir(path.dirname(installer.plistPath), { recursive: true })
      await writeFile(installer.plistPath, installer.plist)
      await writeHostConfig(installer.stateDirectory, 'https://old.example')
      f.setLoaded(true)
      f.setOnPortOwners(async () => {
        if (!f.calls.some(call => call[0] === 'bootout')) return
        const directory = path.join(installer.stateDirectory, 'runs', 'late')
        await mkdir(directory, { recursive: true })
        await writeFile(path.join(directory, '1.json'), '{}')
      })
      const operation = action === 'install'
        ? installer.install(f.source, 'https://linear.example') : installer.uninstall()
      await assert.rejects(operation, /待确认/)
      assert.equal(await readFile(path.join(installer.appPath, 'Contents', 'Resources', 'codex-host', 'src', 'cli.mjs'), 'utf8'), 'old version')
      assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://old.example')
      assert.equal((await installer.serviceState()).loaded, true)
      assert.equal(await readFile(path.join(installer.stateDirectory, 'runs', 'late', '1.json'), 'utf8'), '{}')
    } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
  })
}

test('orphan edit helper group blocks installation and completed historical facts are retained', async () => {
  const f = await fixture(), { installer } = f
  const child = spawn(process.execPath, ['-e', 'setInterval(() => {}, 1000)'], { detached: true, stdio: 'ignore' })
  await once(child, 'spawn')
  try {
    const directory = path.join(installer.stateDirectory, 'file-operations', '42')
    await mkdir(directory, { recursive: true })
    const record = path.join(directory, 'edit.json')
    await writeFile(record, JSON.stringify({ status: 'pending', processGroupId: child.pid }))
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /待确认/)
    await writeFile(record, JSON.stringify({ status: 'completed', processGroupId: child.pid }))
    await installer.install(f.source, 'https://linear.example')
    await installer.uninstall()
    assert.equal(JSON.parse(await readFile(record, 'utf8')).status, 'completed')
  } finally {
    try { process.kill(-child.pid, 'SIGTERM') } catch {}
    await once(child, 'exit')
    await rm(f.homeDirectory, { recursive: true, force: true })
  }
})

test('failed replacement restores prior managed app, configuration and launch job', async () => {
  const f = await fixture(), { installer } = f
  try {
    await bundle(installer.appPath, 'old version')
    await mkdir(path.dirname(installer.plistPath), { recursive: true })
    await writeFile(installer.plistPath, installer.plist)
    await writeHostConfig(installer.stateDirectory, 'https://old.example')
    f.setLoaded(true); f.failBootstrap()
    await assert.rejects(installer.install(f.source, 'https://new.example'), /bootstrap failure/)
    assert.equal(await readFile(path.join(installer.appPath, 'Contents', 'Resources', 'codex-host', 'src', 'cli.mjs'), 'utf8'), 'old version')
    assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://old.example')
    assert.equal(await readFile(installer.plistPath, 'utf8'), installer.plist)
    assert.equal((await installer.serviceState()).loaded, true)
    assert.equal(f.calls.filter(call => call[0] === 'bootstrap').length, 2)
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('status and connect refuse a port owned by another process', async () => {
  const f = await fixture(), { installer } = f
  try {
    await bundle(installer.appPath, 'version')
    await mkdir(path.dirname(installer.plistPath), { recursive: true })
    await writeFile(installer.plistPath, installer.plist)
    await writeHostConfig(installer.stateDirectory, 'https://linear.example')
    f.setLoaded(true); f.setStatusPid(9999)
    await assert.rejects(installer.status(), /其他进程/)
    await assert.rejects(installer.connect(), /其他进程/)
    assert(!f.requests.some(request => request.method === 'POST'))
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('authorization reports a change in port owner during a local operation', async () => {
  const f = await fixture(), { installer } = f
  try {
    await installer.install(f.source, 'https://linear.example')
    f.setPostPid(9999)
    await assert.rejects(installer.connect(), /其他进程/)
    await assert.rejects(installer.disconnect(), /其他进程/)
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('an unrelated port owner does not prevent uninstalling a stopped host', async () => {
  const f = await fixture(), { installer } = f
  try {
    await installer.install(f.source, 'https://linear.example')
    f.setLoaded(false); f.setListeners([36795])
    assert.match(await installer.uninstall(), /卸载/)
    assert.equal(JSON.parse(await readFile(installer.configPath, 'utf8')).origin, 'https://linear.example')
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('installer refuses an unmanaged listening process before registering or stopping a job', async () => {
  const f = await fixture(), { installer } = f
  try {
    f.setListeners([36795])
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /进程 36795 占用/)
    assert(!f.calls.some(call => call[0] === 'bootout' || call[0] === 'bootstrap'))
    await assert.rejects(access(installer.appPath)); await assert.rejects(access(installer.plistPath))
    assert.equal(JSON.parse(await readFile(path.join(installer.stateDirectory, 'device.json'), 'utf8')).deviceId, deviceId)
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})

test('failed startup removes the new launch job and preserves the device identity', async () => {
  const f = await fixture(), { installer } = f
  try {
    f.setStatusPid(9999)
    await assert.rejects(installer.install(f.source, 'https://linear.example'), /未能启动/)
    assert.equal((await installer.serviceState()).loaded, false)
    assert(f.calls.some(call => call[0] === 'bootout'))
    await assert.rejects(access(installer.appPath)); await assert.rejects(access(installer.plistPath))
    assert.equal(JSON.parse(await readFile(path.join(installer.stateDirectory, 'device.json'), 'utf8')).deviceId, deviceId)
  } finally { await rm(f.homeDirectory, { recursive: true, force: true }) }
})
