import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, symlink, writeFile } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { sha256File, sourceManifest, verifyDistribution } from '../install/distribution-manifest.mjs'
import { HOST_PROTOCOL_VERSION } from '../src/protocol.mjs'

async function fixture() {
  const root = await mkdtemp(path.join(os.tmpdir(), 'linear-lite-dist-test-'))
  const hostDirectory = path.join(root, 'codex-host')
  await mkdir(path.join(hostDirectory, 'src'), { recursive: true })
  await mkdir(path.join(hostDirectory, 'install'))
  await mkdir(path.join(hostDirectory, 'native'))
  await writeFile(path.join(hostDirectory, 'native', 'project-volume-info.c'), 'int main(void) { return 0; }\n')
  await writeFile(path.join(hostDirectory, 'src', 'pool.mjs'), 'export const source = true\n')
  await writeFile(path.join(hostDirectory, 'src', 'protocol.mjs'), `export const HOST_PROTOCOL_VERSION = ${HOST_PROTOCOL_VERSION}\n`)
  await writeFile(path.join(hostDirectory, 'install', 'manager.mjs'), 'export const marker = true\n')
  await writeFile(path.join(hostDirectory, 'install', 'HostInstaller.applescript'), 'return\n')
  await writeFile(path.join(hostDirectory, 'package.json'), JSON.stringify({ name: 'linear-lite-codex-host', version: '3.0.0' }))
  const image = path.join(root, 'installer.dmg')
  const manifestPath = `${image}.json`
  await writeFile(image, 'fixture-image')
  const source = await sourceManifest(hostDirectory)
  const metadata = { manifestVersion: 1, ...source, runtime: { nodeVersion: 'v22.23.4',
    sha256: 'a'.repeat(64), licenseSha256: 'b'.repeat(64), volumeHelperSha256: 'c'.repeat(64) }, dmgSha256: await sha256File(image) }
  await writeFile(manifestPath, JSON.stringify(metadata))
  return { root, hostDirectory, image, manifestPath, metadata }
}

test('distribution provenance matches source, protocol and image', async () => {
  const f = await fixture()
  try {
    const result = await verifyDistribution(f)
    assert.equal(result.packageVersion, '3.0.0')
    assert.equal(result.protocolVersion, HOST_PROTOCOL_VERSION)
    assert.deepEqual(result.sources.map(entry => entry.path),
      ['install/HostInstaller.applescript', 'install/manager.mjs', 'native/project-volume-info.c', 'package.json', 'src/pool.mjs', 'src/protocol.mjs'])
  } finally { await rm(f.root, { recursive: true, force: true }) }
})

test('stale source, changed image and protocol mismatch prevent distribution', async () => {
  const f = await fixture()
  try {
    await writeFile(path.join(f.hostDirectory, 'src', 'pool.mjs'), 'different source\n')
    await assert.rejects(verifyDistribution(f), /源码或协议不一致/)
    await writeFile(path.join(f.hostDirectory, 'src', 'pool.mjs'), 'export const source = true\n')
    await writeFile(f.image, 'different image')
    await assert.rejects(verifyDistribution(f), /内容与清单不一致/)
    await writeFile(f.image, 'fixture-image')
    await writeFile(f.manifestPath, JSON.stringify({ ...f.metadata, protocolVersion: HOST_PROTOCOL_VERSION + 1 }))
    await assert.rejects(verifyDistribution(f), /源码或协议不一致/)
    await writeFile(f.manifestPath, JSON.stringify({ ...f.metadata, runtime: { ...f.metadata.runtime, volumeHelperSha256: null } }))
    await assert.rejects(verifyDistribution(f), /清单无效/)
    await writeFile(f.manifestPath, JSON.stringify(f.metadata))
    await writeFile(path.join(f.hostDirectory, 'native', 'project-volume-info.c'), 'changed helper\n')
    await assert.rejects(verifyDistribution(f), /源码或协议不一致/)
  } finally { await rm(f.root, { recursive: true, force: true }) }
})

test('configuration files and symlinked source cannot enter the bundle', async () => {
  const f = await fixture()
  try {
    await writeFile(path.join(f.hostDirectory, 'src', '.env'), 'private fixture')
    await assert.rejects(sourceManifest(f.hostDirectory), /不可打包/)
    await rm(path.join(f.hostDirectory, 'src', '.env'))
    await symlink(f.image, path.join(f.hostDirectory, 'src', 'linked.mjs'))
    await assert.rejects(sourceManifest(f.hostDirectory), /不可打包/)
  } finally { await rm(f.root, { recursive: true, force: true }) }
})
