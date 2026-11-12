import { execFileSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { access, chmod, copyFile, cp, link, mkdir, mkdtemp, readFile, realpath, rm, unlink, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'
import { sha256File, sourceManifest } from './distribution-manifest.mjs'

const installDirectory = path.dirname(fileURLToPath(import.meta.url))
const hostDirectory = path.dirname(installDirectory)
const projectDirectory = path.dirname(hostDirectory)

function options(args) {
  const result = {}
  for (let i = 0; i < args.length; i += 2) {
    if (!['--node', '--license', '--output'].includes(args[i]) || !args[i + 1] || Object.hasOwn(result, args[i]))
      throw new Error('构建参数无效')
    result[args[i]] = args[i + 1]
  }
  return result
}

function tool(program, args) {
  return execFileSync(program, args, { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'], timeout: 300000 })
}

async function main() {
  if (process.platform !== 'darwin' || process.arch !== 'arm64') throw new Error('构建须在 Apple Silicon macOS 运行')
  const opts = options(process.argv.slice(2))
  const source = await sourceManifest(hostDirectory)
  const runtime = await realpath(opts['--node'] ?? process.execPath)
  const license = path.resolve(opts['--license'] ?? path.join(path.dirname(runtime), '..', 'LICENSE'))
  const output = path.resolve(opts['--output'] ?? path.join(projectDirectory, 'docs', '交付产物',
    `Linear-Lite协作宿主-macOS-arm64-V${source.packageVersion}.dmg`))
  if (!output.endsWith('.dmg')) throw new Error('构建产物须为 DMG')
  const metadataPath = `${output}.json`
  for (const filename of [output, metadataPath]) {
    try { await access(filename); throw new Error('构建产物已存在') } catch (error) { if (error.code !== 'ENOENT') throw error }
  }
  const binaryType = tool('/usr/bin/file', [runtime])
  if (!binaryType.includes('Mach-O') || !binaryType.includes('arm64')) throw new Error('Node 运行时架构无效')
  const libraries = tool('/usr/bin/otool', ['-L', runtime]).split('\n').slice(1).map(line => line.trim().split(' (')[0]).filter(Boolean)
  if (!libraries.length || libraries.some(library => !library.startsWith('/usr/lib/') && !library.startsWith('/System/Library/')))
    throw new Error('Node 运行时依赖非系统动态库')
  const major = Number(tool(runtime, ['-p', 'process.versions.node.split(".")[0]']).trim())
  if (!Number.isSafeInteger(major) || major < 22) throw new Error('Node 运行时版本须为 22 或以上')
  const nodeVersion = tool(runtime, ['-p', 'process.version']).trim()
  tool('/usr/bin/codesign', ['--verify', '--strict', runtime])
  if (!(await readFile(license, 'utf8')).includes('Node.js')) throw new Error('Node 许可文件无效')
  const managerSource = source.sources.find(item => item.path === 'install/manager.mjs')
  const managerUrl = pathToFileURL(path.join(installDirectory, 'manager.mjs'))
  managerUrl.searchParams.set('sha256', managerSource.sha256)
  const { MARKER } = await import(managerUrl.href)
  if (await sha256File(path.join(installDirectory, 'manager.mjs')) !== managerSource.sha256)
    throw new Error('安装管理器在读取期间发生变化')
  const runtimeMetadata = { nodeVersion, sha256: await sha256File(runtime), licenseSha256: await sha256File(license) }

  const temporary = await mkdtemp(path.join(os.tmpdir(), 'linear-lite-host-build-'))
  await mkdir(path.dirname(output), { recursive: true, mode: 0o700 })
  const image = path.join(path.dirname(output), `.linear-lite-${randomUUID()}.dmg`)
  const temporaryMetadata = `${image}.json`
  let createdOutput = false
  let createdMetadata = false
  try {
    const contents = path.join(temporary, 'image')
    await mkdir(contents)
    const app = path.join(contents, 'Linear Lite 协作宿主安装.app')
    tool('/usr/bin/osacompile', ['-o', app, path.join(installDirectory, 'HostInstaller.applescript')])
    const info = path.join(app, 'Contents', 'Info.plist')
    tool('/usr/libexec/PlistBuddy', ['-c', 'Add :CFBundleIdentifier string com.linearlite.codex-host-installer', info])
    for (const field of ['CFBundleVersion', 'CFBundleShortVersionString']) {
      try { tool('/usr/libexec/PlistBuddy', ['-c', `Set :${field} ${source.packageVersion}`, info]) }
      catch { tool('/usr/libexec/PlistBuddy', ['-c', `Add :${field} string ${source.packageVersion}`, info]) }
    }
    const resources = path.join(app, 'Contents', 'Resources')
    await mkdir(path.join(resources, 'runtime'), { recursive: true })
    await copyFile(runtime, path.join(resources, 'runtime', 'node'))
    await chmod(path.join(resources, 'runtime', 'node'), 0o755)
    await copyFile(license, path.join(resources, 'runtime', 'Node-LICENSE'))
    const helperSource = path.join(hostDirectory, 'native', 'project-volume-info.c')
    const helper = path.join(resources, 'runtime', 'project-volume-info')
    tool('/usr/bin/clang', ['-arch', 'arm64', '-mmacosx-version-min=11.0', '-std=c11', '-Wall', '-Wextra',
      '-Werror', '-O2', helperSource, '-o', helper])
    const helperType = tool('/usr/bin/file', [helper])
    const helperLibraries = tool('/usr/bin/otool', ['-L', helper]).split('\n').slice(1)
      .map(line => line.trim().split(' (')[0]).filter(Boolean)
    if (!helperType.includes('Mach-O') || !helperType.includes('arm64') || !helperLibraries.length
        || helperLibraries.some(library => !library.startsWith('/usr/lib/') && !library.startsWith('/System/Library/')))
      throw new Error('项目卷校验程序依赖或架构无效')
    tool('/usr/bin/codesign', ['--force', '--sign', '-', '--timestamp=none', helper])
    tool('/usr/bin/codesign', ['--verify', '--strict', helper])
    runtimeMetadata.volumeHelperSha256 = await sha256File(helper)
    await writeFile(path.join(resources, 'linear-lite-host.marker'), MARKER)
    await cp(path.join(hostDirectory, 'src'), path.join(resources, 'codex-host', 'src'), { recursive: true })
    await mkdir(path.join(resources, 'codex-host', 'native'), { recursive: true })
    await copyFile(helperSource, path.join(resources, 'codex-host', 'native', 'project-volume-info.c'))
    await mkdir(path.join(resources, 'codex-host', 'install'), { recursive: true })
    await copyFile(path.join(installDirectory, 'manager.mjs'), path.join(resources, 'codex-host', 'install', 'manager.mjs'))
    await copyFile(path.join(installDirectory, 'HostInstaller.applescript'), path.join(resources, 'codex-host', 'install', 'HostInstaller.applescript'))
    await copyFile(path.join(hostDirectory, 'package.json'), path.join(resources, 'codex-host', 'package.json'))
    const bundledSource = await sourceManifest(path.join(resources, 'codex-host'))
    if (JSON.stringify(bundledSource) !== JSON.stringify(source)
        || await sha256File(path.join(resources, 'runtime', 'node')) !== runtimeMetadata.sha256
        || await sha256File(path.join(resources, 'runtime', 'Node-LICENSE')) !== runtimeMetadata.licenseSha256
        || await sha256File(helper) !== runtimeMetadata.volumeHelperSha256)
      throw new Error('安装应用内容与构建来源不一致')
    const manifest = { manifestVersion: 1, ...source, runtime: runtimeMetadata }
    await writeFile(path.join(resources, 'source-manifest.json'), JSON.stringify(manifest, null, 2) + '\n')
    tool('/usr/bin/codesign', ['--force', '--sign', '-', '--timestamp=none', app])
    tool('/usr/bin/codesign', ['--verify', '--strict', app])
    tool('/usr/bin/codesign', ['--verify', '--strict', helper])
    if (await sha256File(helper) !== runtimeMetadata.volumeHelperSha256)
      throw new Error('项目卷校验程序在签名后发生变化')
    await writeFile(path.join(contents, '安装说明.txt'),
      '打开“Linear Lite 协作宿主安装”，输入站点 HTTPS 地址。安装完成后选择“连接”，在浏览器确认账号与设备。\n适用 Apple Silicon Mac。安装程序未经 Apple 公证；如系统阻止打开，请在系统设置中查看应用安全提示。\n')
    tool('/usr/bin/hdiutil', ['create', '-volname', 'Linear Lite 协作宿主', '-srcfolder', contents,
      '-format', 'UDZO', '-fs', 'HFS+', image])
    tool('/usr/bin/hdiutil', ['imageinfo', image])
    if (JSON.stringify(await sourceManifest(hostDirectory)) !== JSON.stringify(source))
      throw new Error('宿主源码在构建期间发生变化')
    const dmgSha256 = await sha256File(image)
    await writeFile(temporaryMetadata, JSON.stringify({ ...manifest, dmgSha256 }, null, 2) + '\n', { flag: 'wx', mode: 0o600 })
    await link(image, output)
    createdOutput = true
    await link(temporaryMetadata, metadataPath)
    createdMetadata = true
    console.log(output)
  } catch (error) {
    if (createdMetadata) await unlink(metadataPath).catch(() => {})
    if (createdOutput) await unlink(output).catch(() => {})
    throw error
  } finally {
    await unlink(image).catch(() => {})
    await unlink(temporaryMetadata).catch(() => {})
    await rm(temporary, { recursive: true, force: true })
  }
}

main().catch(error => { console.error(error instanceof Error ? error.message : '安装产物构建失败'); process.exitCode = 1 })
