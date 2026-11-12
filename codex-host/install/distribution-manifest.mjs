import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { lstat, readFile, readdir } from 'node:fs/promises'
import path from 'node:path'
import { pathToFileURL } from 'node:url'

const codeExtension = '.mjs'
const digestPattern = /^[a-f0-9]{64}$/

export async function sha256File(filename) {
  const hash = createHash('sha256')
  for await (const chunk of createReadStream(filename)) hash.update(chunk)
  return hash.digest('hex')
}

async function sourceFiles(hostDirectory) {
  const files = ['package.json', 'install/HostInstaller.applescript', 'install/manager.mjs', 'native/project-volume-info.c']
  async function walk(relative) {
    const entries = await readdir(path.join(hostDirectory, relative), { withFileTypes: true })
    for (const entry of entries) {
      const name = `${relative}/${entry.name}`
      if (entry.isDirectory()) await walk(name)
      else if (entry.isFile() && entry.name.endsWith(codeExtension)) files.push(name)
      else throw new Error('宿主源码目录包含不可打包的文件')
    }
  }
  await walk('src')
  return files.sort()
}

/** 清单路径与安装应用内的 codex-host 路径一致。 */
export async function sourceManifest(hostDirectory) {
  const files = await sourceFiles(hostDirectory)
  const sources = []
  for (const relative of files) {
    const filename = path.join(hostDirectory, relative)
    if (!(await lstat(filename)).isFile()) throw new Error('宿主源码不是普通文件')
    sources.push({ path: relative, sha256: await sha256File(filename) })
  }
  const descriptorText = await readFile(path.join(hostDirectory, 'package.json'), 'utf8')
  const descriptorDigest = createHash('sha256').update(descriptorText).digest('hex')
  if (sources.find(item => item.path === 'package.json').sha256 !== descriptorDigest)
    throw new Error('宿主源码在读取期间发生变化')
  const descriptor = JSON.parse(descriptorText)
  if (descriptor.name !== 'linear-lite-codex-host' || !/^\d+\.\d+\.\d+$/.test(descriptor.version))
    throw new Error('宿主版本无效')
  const protocol = sources.find(item => item.path === 'src/protocol.mjs')
  if (!protocol) throw new Error('宿主协议文件缺失')
  const protocolPath = path.join(hostDirectory, protocol.path)
  const protocolUrl = pathToFileURL(protocolPath)
  protocolUrl.searchParams.set('sha256', protocol.sha256)
  const { HOST_PROTOCOL_VERSION } = await import(protocolUrl.href)
  if (!Number.isSafeInteger(HOST_PROTOCOL_VERSION) || HOST_PROTOCOL_VERSION <= 0
      || await sha256File(protocolPath) !== protocol.sha256)
    throw new Error('宿主协议版本无效或发生变化')
  return { packageVersion: descriptor.version, protocolVersion: HOST_PROTOCOL_VERSION, sources }
}

export async function verifyDistribution({ hostDirectory, image, manifestPath }) {
  const metadata = JSON.parse(await readFile(manifestPath, 'utf8'))
  if (!metadata || metadata.manifestVersion !== 1
      || typeof metadata.dmgSha256 !== 'string' || !digestPattern.test(metadata.dmgSha256)
      || !metadata.runtime || typeof metadata.runtime.nodeVersion !== 'string'
      || !/^v(?:2[2-9]|[3-9]\d|\d{3,})\.\d+\.\d+$/.test(metadata.runtime.nodeVersion)
      || !digestPattern.test(metadata.runtime.sha256) || !digestPattern.test(metadata.runtime.licenseSha256)
      || !digestPattern.test(metadata.runtime.volumeHelperSha256))
    throw new Error('宿主安装包清单无效')
  const expected = await sourceManifest(hostDirectory)
  if (metadata.packageVersion !== expected.packageVersion || metadata.protocolVersion !== expected.protocolVersion
      || JSON.stringify(metadata.sources) !== JSON.stringify(expected.sources))
    throw new Error('宿主安装包与当前源码或协议不一致，请重新构建')
  const imageStat = await lstat(image)
  if (!imageStat.isFile() || imageStat.size === 0 || await sha256File(image) !== metadata.dmgSha256)
    throw new Error('宿主安装包内容与清单不一致')
  return metadata
}
