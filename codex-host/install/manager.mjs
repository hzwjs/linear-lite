import { spawn } from 'node:child_process'
import { createHash, randomUUID } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { access, lstat, mkdir, readFile, readdir, rename, rm, unlink, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { readHostConfig, writeHostConfig } from '../src/host-config.mjs'
import { normalizeIssuer } from '../src/oauth.mjs'
import { groupIsGone } from '../src/process-groups.mjs'
import { HOST_PROTOCOL_VERSION } from '../src/protocol.mjs'

export const LABEL = 'com.linearlite.codex-host'
export const MARKER = 'linear-lite-codex-host-installer-v3\n'
const LEGACY_MARKER = 'linear-lite-codex-host-v1\n'
const APP_NAME = 'Linear Lite 协作宿主.app'
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/

function xml(value) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;').replaceAll("'", '&apos;')
}

export function launchAgentPlist(homeDirectory) {
  const app = path.join(homeDirectory, 'Applications', APP_NAME)
  const node = path.join(app, 'Contents', 'Resources', 'runtime', 'node')
  const cli = path.join(app, 'Contents', 'Resources', 'codex-host', 'src', 'cli.mjs')
  const state = path.join(homeDirectory, 'Library', 'Application Support', 'Linear-Lite', 'CodexHost')
  const envPath = [path.dirname(node), path.join(homeDirectory, '.local', 'bin'), '/opt/homebrew/bin',
    '/usr/local/bin', '/usr/bin', '/bin', '/usr/sbin', '/sbin'].join(':')
  return `<?xml version="1.0" encoding="UTF-8"?>\n<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">\n<plist version="1.0"><dict>\n` +
    `<key>Label</key><string>${LABEL}</string>\n<key>ProgramArguments</key><array>` +
    [node, cli, 'serve', '--state-dir', state].map(value => `<string>${xml(value)}</string>`).join('') +
    `</array>\n<key>RunAtLoad</key><true/>\n<key>KeepAlive</key><true/>\n` +
    `<key>EnvironmentVariables</key><dict><key>PATH</key><string>${xml(envPath)}</string></dict>\n` +
    `<key>StandardOutPath</key><string>/dev/null</string>\n<key>StandardErrorPath</key><string>/dev/null</string>\n</dict></plist>\n`
}

function run(program, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(program, args, { stdio: ['ignore', 'pipe', 'ignore'] })
    let output = ''
    child.stdout.on('data', chunk => { if (output.length < 65536) output += chunk.toString('utf8') })
    const timer = setTimeout(() => { child.kill('SIGKILL'); reject(new Error('无法管理宿主服务')) }, 10000)
    child.once('error', () => { clearTimeout(timer); reject(new Error('无法管理宿主服务')) })
    child.once('close', code => {
      clearTimeout(timer)
      if (code === 0) resolve(output)
      else { const error = new Error('无法管理宿主服务'); error.code = code; reject(error) }
    })
  })
}

function launchctl(args) { return run('/bin/launchctl', args) }

async function bundleSignature(bundle, helper) {
  try {
    await run('/usr/bin/codesign', ['--verify', '--strict', bundle])
    await run('/usr/bin/codesign', ['--verify', '--strict', helper])
  } catch { throw new Error('宿主安装包签名校验失败') }
}

async function fileDigest(filename) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(filename)) hash.update(chunk)
  return hash.digest('hex')
}

async function portListeners() {
  let output
  try { output = await run('/usr/sbin/lsof', ['-nP', '-t', '-iTCP:9781', '-sTCP:LISTEN']) }
  catch (error) { if (error.code === 1) return []; throw new Error('无法核对本机宿主端口') }
  const values = output.trim().split(/\s+/).map(Number)
  if (!output.trim() || values.some(pid => !Number.isSafeInteger(pid) || pid <= 1))
    throw new Error('无法核对本机宿主端口')
  return [...new Set(values)]
}

async function copyBundle(source, destination) {
  // ditto 保留应用扩展属性，包括 macOS 的下载安全标记。
  try { await run('/usr/bin/ditto', [source, destination]) }
  catch { throw new Error('无法复制宿主安装包') }
}

async function readOptional(filename) {
  try { return await readFile(filename, 'utf8') }
  catch (error) { if (error.code === 'ENOENT') return null; throw error }
}

async function writeAtomic(filename, value, mode) {
  await mkdir(path.dirname(filename), { recursive: true, mode: 0o700 })
  const temporary = path.join(path.dirname(filename), `.linear-lite-${randomUUID()}.tmp`)
  try {
    await writeFile(temporary, value, { flag: 'wx', mode })
    await rename(temporary, filename)
  } finally { await unlink(temporary).catch(() => {}) }
}

async function removeIfExists(filename) {
  try { await unlink(filename) } catch (error) { if (error.code !== 'ENOENT') throw error }
}

async function hasJournalFiles(directory) {
  let entries
  try { entries = await readdir(directory, { withFileTypes: true }) }
  catch (error) { if (error.code === 'ENOENT') return false; throw new Error('无法读取协作恢复状态') }
  for (const entry of entries) {
    if (!entry.isDirectory() || await hasJournalFiles(path.join(directory, entry.name))) return true
  }
  return false
}

export class HostInstaller {
  constructor({ homeDirectory = os.homedir(), uid = process.getuid(), runLaunchctl = launchctl,
    fetchImpl = fetch, portOwners = portListeners, copyApp = copyBundle, verifySignature = bundleSignature,
    sleep = ms => new Promise(resolve => setTimeout(resolve, ms)) } = {}) {
    if (!path.isAbsolute(homeDirectory) || !Number.isSafeInteger(uid) || uid <= 0) throw new Error('用户目录或身份无效')
    this.homeDirectory = homeDirectory; this.uid = uid; this.runLaunchctl = runLaunchctl
    this.fetchImpl = fetchImpl; this.portOwners = portOwners; this.copyApp = copyApp; this.sleep = sleep
    this.verifySignature = verifySignature
    this.appPath = path.join(homeDirectory, 'Applications', APP_NAME)
    this.nodePath = path.join(this.appPath, 'Contents', 'Resources', 'runtime', 'node')
    this.stateDirectory = path.join(homeDirectory, 'Library', 'Application Support', 'Linear-Lite', 'CodexHost')
    this.configPath = path.join(this.stateDirectory, 'host-config.json')
    this.plistPath = path.join(homeDirectory, 'Library', 'LaunchAgents', `${LABEL}.plist`)
    this.plist = launchAgentPlist(homeDirectory)
    this.domain = `gui/${uid}`; this.service = `${this.domain}/${LABEL}`
  }

  async assertNoFrozenWork() {
    let batches
    try { batches = await readdir(path.join(this.stateDirectory, 'runs')) }
    catch (error) { if (error.code === 'ENOENT') batches = []; else throw new Error('无法读取协作恢复状态') }
    if (batches.length) throw new Error('存在待确认的协作执行，请完成停止恢复后操作')
    for (const name of ['attempts', 'process-groups'])
      if (await hasJournalFiles(path.join(this.stateDirectory, name)))
        throw new Error('存在待确认的协作执行，请完成停止恢复后操作')
    const operations = path.join(this.stateDirectory, 'file-operations')
    let attempts
    try { attempts = await readdir(operations) }
    catch (error) { if (error.code === 'ENOENT') return; throw new Error('无法读取协作恢复状态') }
    for (const attempt of attempts) {
      let entries
      try {
        if (!(await lstat(path.join(operations, attempt))).isDirectory()) throw new Error('invalid')
        entries = await readdir(path.join(operations, attempt))
      } catch { throw new Error('无法读取协作恢复状态') }
      for (const name of entries) {
        if (!name.endsWith('.json')) throw new Error('存在待确认的协作执行，请完成停止恢复后操作')
        let fact
        try { fact = JSON.parse(await readFile(path.join(operations, attempt, name), 'utf8')) }
        catch { throw new Error('无法读取协作恢复状态') }
        if (!fact || !['pending', 'unknown', 'completed', 'rejected'].includes(fact.status)
            || (fact.processGroupId !== null && (!Number.isSafeInteger(fact.processGroupId) || fact.processGroupId <= 1)))
          throw new Error('无法读取协作恢复状态')
        // 完成、拒绝和未知结果在 worker 退出后写入，可作为历史事实保留。
        if (fact.status === 'pending' && fact.processGroupId !== null) {
          let gone
          try { gone = groupIsGone(fact.processGroupId) } catch { throw new Error('存在待确认的协作执行，请完成停止恢复后操作') }
          if (!gone) throw new Error('存在待确认的协作执行，请完成停止恢复后操作')
        }
      }
    }
  }

  async assertNoLegacyWork() {
    let entries
    try { entries = await readdir(path.join(this.stateDirectory, 'roots')) }
    catch (error) { if (error.code === 'ENOENT') return; throw new Error('无法读取旧宿主恢复状态') }
    if (entries.length) throw new Error('旧宿主存在待确认的协作执行，请完成停止恢复后操作')
  }

  async checkBundle(bundle, optional = false, installed = false) {
    let stat
    try { stat = await lstat(bundle) }
    catch (error) { if (optional && error.code === 'ENOENT') return false; throw new Error('宿主安装包不存在') }
    if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error('宿主安装目录归属无效')
    const marker = await readOptional(path.join(bundle, 'Contents', 'Resources', 'linear-lite-host.marker'))
    if (marker !== MARKER && !(installed && marker === LEGACY_MARKER)) throw new Error('宿主安装目录归属无效')
    await access(path.join(bundle, 'Contents', 'Resources', 'runtime', 'node'))
    await access(path.join(bundle, 'Contents', 'Resources', 'codex-host', 'src', 'cli.mjs'))
    // 旧安装内容只用于归属识别和回滚；新安装内容必须包含校验程序。
    if (!installed) {
      const resources = path.join(bundle, 'Contents', 'Resources')
      const helper = path.join(resources, 'runtime', 'project-volume-info')
      let metadata, helperStat
      try {
        const manifestPath = path.join(resources, 'source-manifest.json')
        if (!(await lstat(manifestPath)).isFile()) throw new Error('invalid manifest')
        metadata = JSON.parse(await readFile(manifestPath, 'utf8'))
        helperStat = await lstat(helper)
      } catch { throw new Error('宿主安装包内容校验失败') }
      if (!helperStat.isFile() || (helperStat.mode & 0o111) === 0 || metadata?.manifestVersion !== 1
          || typeof metadata.runtime?.volumeHelperSha256 !== 'string'
          || !/^[a-f0-9]{64}$/.test(metadata.runtime.volumeHelperSha256)
          || await fileDigest(helper) !== metadata.runtime.volumeHelperSha256)
        throw new Error('项目卷校验程序完整性校验失败')
      await this.verifySignature(bundle, helper)
    }
    return true
  }

  async serviceState() {
    let result
    try { result = await this.runLaunchctl(['print', this.service]) }
    catch (error) { if (error.code === 113) return { loaded: false, pid: null }; throw new Error('无法读取宿主服务状态') }
    const program = result.match(/^\s*program = (.+)\s*$/m)?.[1].trim()
    if (program !== this.nodePath) throw new Error('同名后台服务不属于此宿主')
    const rawPid = result.match(/^\s*pid = (\d+)\s*$/m)?.[1]
    const pid = rawPid === undefined ? null : Number(rawPid)
    if (pid !== null && (!Number.isSafeInteger(pid) || pid <= 1)) throw new Error('宿主服务进程无效')
    return { loaded: true, pid }
  }

  async checkPlist() {
    const current = await readOptional(this.plistPath)
    if (current !== null && current !== this.plist) throw new Error('同名启动配置不属于此宿主')
    return current
  }

  async checkPort(managedPid) {
    const owners = await this.portOwners()
    const foreign = owners.find(pid => pid !== managedPid)
    if (foreign !== undefined)
      throw new Error(`本机端口被进程 ${foreign} 占用。请退出原协作宿主后重试；如仍占用，请查看活动监视器。`)
    return owners
  }

  async waitPortFree(previousPid) {
    for (let i = 0; i < 40; i++) {
      const owners = await this.checkPort(previousPid)
      if (!owners.length) return
      await this.sleep(100)
    }
    throw new Error('原后台宿主仍在退出，请重试安装')
  }

  async waitReady(origin) {
    for (let i = 0; i < 120; i++) {
      const service = await this.serviceState()
      if (service.loaded && service.pid !== null) {
        try {
          const status = await this.hostRequest(origin, '/status', 'GET', 250)
          const identity = JSON.parse(await readFile(path.join(this.stateDirectory, 'device.json'), 'utf8'))
          if (status.processId === service.pid && status.deviceId === identity.deviceId) {
            this.requireProtocol(status)
            return status
          }
        } catch (error) {
          if (error.code === 'HOST_UPGRADE_REQUIRED') throw error
          // 服务启动期间重新读取状态。
        }
      }
      await this.sleep(250)
    }
    throw new Error('宿主服务未能启动，请检查本机端口与系统授权')
  }

  async hostRequest(origin, endpoint, method, timeoutMs = 15000) {
    const controller = new AbortController(), timer = setTimeout(() => controller.abort(), timeoutMs)
    try {
      const response = await this.fetchImpl(`http://127.0.0.1:9781${endpoint}`, { method, redirect: 'error',
        cache: 'no-store', signal: controller.signal,
        headers: method === 'POST' ? { Origin: origin, 'Content-Type': 'application/json' } : { Origin: origin },
        body: method === 'POST' ? '{}' : undefined })
      if (!response.ok) throw new Error('宿主请求失败')
      const status = await response.json()
      if (!status || !UUID.test(status.deviceId) || typeof status.hostInstanceId !== 'string'
          || !Number.isSafeInteger(status.processId) || status.processId <= 1
          || !['AUTH_REQUIRED', 'CONNECTING', 'ONLINE', 'OFFLINE', 'UPGRADE_REQUIRED'].includes(status.connection))
        throw new Error('宿主状态无效')
      return status
    } catch { throw new Error('无法连接本机协作宿主') } finally { clearTimeout(timer) }
  }

  requireProtocol(status) {
    if (status.protocolVersion !== HOST_PROTOCOL_VERSION) {
      const error = new Error('本机协作宿主版本需要升级，请下载并安装新版本')
      error.code = 'HOST_UPGRADE_REQUIRED'
      throw error
    }
  }

  async install(sourceBundle, originInput) {
    const origin = normalizeIssuer(originInput)
    if (!path.isAbsolute(sourceBundle) || path.resolve(sourceBundle) === this.appPath) throw new Error('宿主安装包无效')
    await this.checkBundle(sourceBundle)
    await this.assertNoFrozenWork()
    const hadApp = await this.checkBundle(this.appPath, true, true)
    const oldMarker = hadApp ? await readOptional(path.join(this.appPath, 'Contents', 'Resources', 'linear-lite-host.marker')) : null
    if (oldMarker === LEGACY_MARKER) await this.assertNoLegacyWork()
    const oldPlist = await this.checkPlist(), oldConfig = await readOptional(this.configPath)
    if (oldConfig !== null) await readHostConfig(this.stateDirectory)
    const oldService = await this.serviceState()
    if (oldService.loaded && (!hadApp || oldPlist === null)) throw new Error('宿主服务归属无法确认')
    await this.checkPort(oldService.pid)
    await mkdir(path.dirname(this.appPath), { recursive: true, mode: 0o700 })
    const stage = `${this.appPath}.new-${randomUUID()}`, backup = `${this.appPath}.backup-${randomUUID()}`
    let stoppedOld = false, movedOld = false, installed = false, wroteConfig = false, wrotePlist = false
    try {
      await this.copyApp(sourceBundle, stage)
      await this.checkBundle(stage)
      if (oldService.loaded) { await this.runLaunchctl(['bootout', this.service]); stoppedOld = true }
      await this.assertNoFrozenWork()
      if (oldMarker === LEGACY_MARKER) await this.assertNoLegacyWork()
      await this.waitPortFree(oldService.pid)
      await this.assertNoFrozenWork()
      if (oldMarker === LEGACY_MARKER) await this.assertNoLegacyWork()
      if (hadApp) { await rename(this.appPath, backup); movedOld = true }
      await rename(stage, this.appPath); installed = true
      await writeHostConfig(this.stateDirectory, origin); wroteConfig = true
      await writeAtomic(this.plistPath, this.plist, 0o644); wrotePlist = true
      await this.checkPort(null)
      await this.runLaunchctl(['bootstrap', this.domain, this.plistPath])
      await this.waitReady(origin)
    } catch (error) {
      if (wrotePlist) {
        const current = await this.serviceState()
        if (current.loaded) {
          await this.runLaunchctl(['bootout', this.service])
          await this.waitPortFree(current.pid)
        }
        try { await this.assertNoFrozenWork() }
        catch {
          // 新服务产生的恢复记录对应新安装内容，保留该内容供恢复使用。
          await this.runLaunchctl(['bootstrap', this.domain, this.plistPath]).catch(() => {})
          throw new Error('存在待确认的协作执行，请完成停止恢复后重试')
        }
      }
      if (installed) await rm(this.appPath, { recursive: true, force: true })
      if (movedOld) await rename(backup, this.appPath)
      if (wroteConfig) {
        if (oldConfig === null) await removeIfExists(this.configPath)
        else await writeAtomic(this.configPath, oldConfig, 0o600)
      }
      if (wrotePlist) {
        if (oldPlist === null) await removeIfExists(this.plistPath)
        else await writeAtomic(this.plistPath, oldPlist, 0o644)
      }
      if (stoppedOld && oldPlist !== null) await this.runLaunchctl(['bootstrap', this.domain, this.plistPath])
      throw error
    } finally { await rm(stage, { recursive: true, force: true }) }
    if (movedOld) await rm(backup, { recursive: true, force: true })
    return '宿主安装完成。选择“连接”后，在浏览器完成设备授权。'
  }

  async status() {
    await this.checkBundle(this.appPath)
    await this.checkPlist()
    const service = await this.serviceState()
    if (!service.loaded || service.pid === null) return '宿主未运行。'
    const config = await readHostConfig(this.stateDirectory)
    const state = await this.hostRequest(config.origin, '/status', 'GET')
    if (state.processId !== service.pid) throw new Error('本机端口被其他进程占用')
    this.requireProtocol(state)
    const labels = { AUTH_REQUIRED: '需要连接授权', CONNECTING: '连接授权进行中', ONLINE: '已连接',
      OFFLINE: '协作服务离线', UPGRADE_REQUIRED: '协作服务版本不匹配' }
    return `宿主状态：${labels[state.connection]}`
  }

  async connect() {
    await this.checkBundle(this.appPath)
    await this.checkPlist()
    const service = await this.serviceState()
    if (!service.loaded || service.pid === null) throw new Error('宿主服务未运行')
    const config = await readHostConfig(this.stateDirectory)
    const prior = await this.hostRequest(config.origin, '/status', 'GET')
    if (prior.processId !== service.pid) throw new Error('本机端口被其他进程占用')
    this.requireProtocol(prior)
    if (prior.connection === 'UPGRADE_REQUIRED') throw new Error('协作服务版本不匹配，请升级服务后重试')
    const result = await this.hostRequest(config.origin, '/connect', 'POST')
    if (result.processId !== service.pid) throw new Error('本机端口被其他进程占用')
    this.requireProtocol(result)
    return '授权请求已提交，请在浏览器完成账号与设备确认。'
  }

  async disconnect() {
    await this.checkBundle(this.appPath)
    await this.checkPlist()
    const service = await this.serviceState()
    if (!service.loaded || service.pid === null) throw new Error('宿主服务未运行')
    const config = await readHostConfig(this.stateDirectory)
    const prior = await this.hostRequest(config.origin, '/status', 'GET')
    if (prior.processId !== service.pid) throw new Error('本机端口被其他进程占用')
    this.requireProtocol(prior)
    const result = await this.hostRequest(config.origin, '/disconnect', 'POST')
    if (result.processId !== service.pid) throw new Error('本机端口被其他进程占用')
    this.requireProtocol(result)
    return '宿主授权已撤销。'
  }

  async uninstall() {
    await this.assertNoFrozenWork()
    const hadApp = await this.checkBundle(this.appPath, true, true)
    const oldMarker = hadApp ? await readOptional(path.join(this.appPath, 'Contents', 'Resources', 'linear-lite-host.marker')) : null
    if (oldMarker === LEGACY_MARKER) await this.assertNoLegacyWork()
    const oldPlist = await this.checkPlist()
    const service = await this.serviceState()
    if (service.loaded && (!hadApp || oldPlist === null)) throw new Error('宿主服务归属无法确认')
    if (service.loaded) await this.runLaunchctl(['bootout', this.service])
    try {
      await this.assertNoFrozenWork()
      if (oldMarker === LEGACY_MARKER) await this.assertNoLegacyWork()
      if (service.pid !== null) await this.waitPortFree(service.pid)
      await this.assertNoFrozenWork()
      if (oldMarker === LEGACY_MARKER) await this.assertNoLegacyWork()
    }
    catch (error) {
      if (service.loaded && oldPlist !== null) await this.runLaunchctl(['bootstrap', this.domain, this.plistPath])
      throw error
    }
    if (oldPlist !== null) await removeIfExists(this.plistPath)
    if (hadApp) await rm(this.appPath, { recursive: true, force: true })
    return '宿主已卸载，设备标识、连接配置和授权保留。'
  }
}

async function main(args) {
  const [action, ...params] = args
  const installer = new HostInstaller()
  if (action === 'install') {
    if (params.length !== 4 || params[0] !== '--source' || params[2] !== '--origin') throw new Error('安装参数无效')
    return installer.install(params[1], params[3])
  }
  if (params.length) throw new Error('宿主操作参数无效')
  if (action === 'status') return installer.status()
  if (action === 'connect') return installer.connect()
  if (action === 'disconnect') return installer.disconnect()
  if (action === 'uninstall') return installer.uninstall()
  throw new Error('宿主操作无效')
}

if (process.argv[1] && pathToFileURL(path.resolve(process.argv[1])).href === import.meta.url) {
  main(process.argv.slice(2)).then(value => console.log(value)).catch(error => {
    console.error(error instanceof Error && error.message ? error.message : '宿主操作失败')
    process.exitCode = 1
  })
}
