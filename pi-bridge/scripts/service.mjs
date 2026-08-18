import { cp, mkdir, rename, rm, writeFile } from 'node:fs/promises'
import { execFile, execFileSync } from 'node:child_process'
import { promisify } from 'node:util'
import { dirname, isAbsolute, join, resolve } from 'node:path'
import { homedir, userInfo } from 'node:os'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'

const execFileAsync = promisify(execFile)
const bridgeRoot = resolve(fileURLToPath(new URL('..', import.meta.url)))
const applicationRoot = join(homedir(), 'Library', 'Application Support', 'Linear Lite', 'Pi Bridge')
const appRoot = join(applicationRoot, 'app')
const dataRoot = join(applicationRoot, 'data')
const sessionRoot = join(applicationRoot, 'sessions')
const runtimeRoot = join(applicationRoot, 'runtime')
const logRoot = join(homedir(), 'Library', 'Logs', 'Linear Lite')
const plistPath = join(homedir(), 'Library', 'LaunchAgents', 'com.linearlite.pi-bridge.plist')
const label = 'com.linearlite.pi-bridge'
const domain = `gui/${userInfo().uid}`

function xml(value) {
  return String(value)
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&apos;')
}

function resolvePiBinary() {
  const configured = process.env.PI_BINARY
  if (configured && isAbsolute(configured)) return configured
  try {
    return execFileSync('/usr/bin/which', [configured ?? 'pi'], { encoding: 'utf8' }).trim()
  } catch {
    throw new Error('找不到 pi 可执行文件，请设置 PI_BINARY 为绝对路径后重新安装 Bridge')
  }
}

function plistValue(value) {
  return `<string>${xml(value)}</string>`
}

export function makePlist(nodeBinary, piBinary) {
  const environment = {
    PI_BINARY: piBinary,
    // pi 是通过 /usr/bin/env node 启动的脚本；launchd 不继承交互式 nvm PATH，必须显式提供 Node 所在目录。
    PATH: `${dirname(nodeBinary)}:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin`,
    PI_BRIDGE_CONFIG_HOST: '127.0.0.1',
    PI_BRIDGE_CONFIG_PORT: '9780',
    PI_BRIDGE_CONFIG_FILE: join(dataRoot, 'projects.json'),
    PI_BRIDGE_SETTINGS_FILE: join(dataRoot, 'settings.json'),
    PI_BRIDGE_SESSION_ROOT: sessionRoot,
    PI_BRIDGE_RUNTIME_ROOT: runtimeRoot,
    PI_BRIDGE_VERSION: process.env.PI_BRIDGE_VERSION ?? 'installed',
  }
  const environmentXml = Object.entries(environment)
    .map(([key, value]) => `<key>${xml(key)}</key>\n${plistValue(value)}`)
    .join('\n')
  const argumentsXml = [nodeBinary, join(appRoot, 'src', 'index.mjs')].map(plistValue).join('\n')
  return `<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key>
  ${plistValue(label)}
  <key>ProgramArguments</key>
  <array>
    ${argumentsXml}
  </array>
  <key>WorkingDirectory</key>
  ${plistValue(appRoot)}
  <key>EnvironmentVariables</key>
  <dict>
    ${environmentXml}
  </dict>
  <key>KeepAlive</key>
  <true/>
  <key>ThrottleInterval</key>
  <integer>10</integer>
  <key>ExitTimeOut</key>
  <integer>15</integer>
  <key>ProcessType</key>
  ${plistValue('Background')}
  <key>StandardOutPath</key>
  ${plistValue(join(logRoot, 'pi-bridge.out.log'))}
  <key>StandardErrorPath</key>
  ${plistValue(join(logRoot, 'pi-bridge.err.log'))}
</dict>
</plist>
`
}

async function launchctl(args, allowFailure = false) {
  try {
    return await execFileAsync('/bin/launchctl', args)
  } catch (error) {
    if (allowFailure) return null
    throw new Error(error.stderr?.trim() || error.message)
  }
}

async function isLoaded() {
  return Boolean(await launchctl(['print', `${domain}/${label}`], true))
}

async function unloadLoadedService() {
  if (!await isLoaded()) return
  // launchd 偶尔在 Node 进程收到 SIGTERM 后仍保留服务记录；先按 plist 路径卸载，再确认域中已无该服务。
  await launchctl(['bootout', domain, plistPath], true)
  if (await isLoaded()) {
    await launchctl(['kill', 'SIGKILL', `${domain}/${label}`], true)
    for (let attempt = 0; attempt < 20 && await isLoaded(); attempt += 1) {
      await delay(100)
    }
    await launchctl(['bootout', domain, plistPath], true)
  }
  if (await isLoaded()) {
    throw new Error('无法卸载旧的 Pi Bridge 服务，请先执行 npm run service:status 后重试')
  }
}

async function writeAtomic(filePath, content) {
  const temporaryPath = `${filePath}.${process.pid}.tmp`
  await writeFile(temporaryPath, content, { mode: 0o600 })
  await rename(temporaryPath, filePath)
}

async function assertPortAvailable() {
  try {
    await fetch('http://127.0.0.1:9780/healthz')
    throw new Error('本地 9780 端口仍被其他 Bridge 进程占用，请先停止手动启动的 npm start，再执行 service:install')
  } catch (error) {
    if (error.message.startsWith('本地 9780')) throw error
    // ECONNREFUSED 表示没有旧 Bridge；其他网络错误也不会阻止 launchd 接管端口。
  }
}

async function install() {
  const nodeBinary = process.execPath
  const piBinary = resolvePiBinary()
  const loadedService = await isLoaded()
  if (!loadedService) await assertPortAvailable()
  // 先停止旧实例，再替换入口文件，避免运行中的 Bridge 读取到半更新目录。
  await unloadLoadedService()
  await Promise.all([
    mkdir(join(applicationRoot, 'data'), { recursive: true, mode: 0o700 }),
    mkdir(sessionRoot, { recursive: true, mode: 0o700 }),
    mkdir(runtimeRoot, { recursive: true, mode: 0o700 }),
    mkdir(logRoot, { recursive: true, mode: 0o700 }),
    mkdir(dirname(plistPath), { recursive: true, mode: 0o700 }),
  ])

  const stageRoot = join(applicationRoot, `app.stage-${process.pid}`)
  await rm(stageRoot, { recursive: true, force: true })
  await cp(join(bridgeRoot, 'src'), join(stageRoot, 'src'), { recursive: true })
  await cp(join(bridgeRoot, 'package.json'), join(stageRoot, 'package.json'))
  await rm(appRoot, { recursive: true, force: true })
  await rename(stageRoot, appRoot)

  await writeAtomic(plistPath, makePlist(nodeBinary, piBinary))
  await launchctl(['bootstrap', domain, plistPath])
  await launchctl(['kickstart', '-k', `${domain}/${label}`])
  console.log(`Pi Bridge 已安装并启动：${label}`)
  console.log(`配置页：http://127.0.0.1:9780/`)
}

async function start() {
  await launchctl(['kickstart', `${domain}/${label}`])
  console.log('Pi Bridge 已启动')
}

async function restart() {
  await launchctl(['kickstart', '-k', `${domain}/${label}`])
  console.log('Pi Bridge 已重启')
}

async function status() {
  const result = await launchctl(['print', `${domain}/${label}`], true)
  if (!result) {
    console.log('Pi Bridge 未加载')
    return
  }
  console.log(result.stdout.trim())
  try {
    const response = await fetch('http://127.0.0.1:9780/healthz')
    console.log(JSON.stringify(await response.json()))
  } catch {
    console.log('healthz: unavailable')
  }
}

async function logs() {
  console.log(join(logRoot, 'pi-bridge.out.log'))
  console.log(join(logRoot, 'pi-bridge.err.log'))
}

async function uninstall() {
  await launchctl(['bootout', domain, label], true)
  await rm(plistPath, { force: true })
  await rm(appRoot, { recursive: true, force: true })
  console.log('Pi Bridge 服务已卸载；配置、session 和日志数据已保留')
}

const isMainModule = process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href
if (isMainModule) {
  const command = process.argv[2]
  const commands = { install, start, restart, status, logs, uninstall }
  if (!commands[command]) {
    console.error('用法：node scripts/service.mjs <install|start|restart|status|logs|uninstall>')
    process.exitCode = 1
  } else {
    commands[command]().catch((error) => {
      console.error(`[pi-bridge] ${error.message}`)
      process.exitCode = 1
    })
  }
}
