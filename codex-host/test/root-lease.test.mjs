import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, symlink, rm, readdir, readFile, writeFile } from 'node:fs/promises'
import { spawn } from 'node:child_process'
import { once } from 'node:events'
import path from 'node:path'
import os from 'node:os'
import { acquireProjectRoot, readProjectRootOwner, recoverProjectRoot } from '../src/root-lease.mjs'

test('same physical root excludes another project and alias until owner releases', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'll-root-lease-'))
  try {
    const projectDirectory = path.join(directory, 'project'); await mkdir(projectDirectory)
    const alias = path.join(directory, 'alias'); await symlink(projectDirectory, alias)
    const config = { stateDirectory: path.join(directory, 'state'), identity: { deviceId: 'device', hostInstanceId: 'host-a' }, projectId: 1, projectDirectory }
    const first = await acquireProjectRoot(config)
    await assert.rejects(() => acquireProjectRoot({ ...config, projectId: 2, projectDirectory: alias }), { code: 'ROOT_IN_USE' })
    await first.release(); await first.release()
    const second = await acquireProjectRoot({ ...config, projectId: 2, projectDirectory: alias })
    await second.release()
  } finally { await rm(directory, { recursive: true, force: true }) }
})

test('a replacement host cannot steal a root merely because the old lease looks stale', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'll-root-stale-'))
  try {
    const config = { stateDirectory: path.join(directory, 'state'), identity: { deviceId: 'device', hostInstanceId: 'old-host' }, projectId: 1, projectDirectory: directory }
    const first = await acquireProjectRoot(config)
    await assert.rejects(() => acquireProjectRoot({ ...config, identity: { deviceId: 'device', hostInstanceId: 'new-host' } }), { code: 'ROOT_IN_USE' })
    await first.release()
  } finally { await rm(directory, { recursive: true, force: true }) }
})

test('recovery requires old process exit and successful stop confirmations before unlocking', async () => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'll-root-recover-'))
  try {
    const config = { stateDirectory: path.join(directory, 'state'), identity: { deviceId: 'device', hostInstanceId: 'old-host' }, projectId: 1, projectDirectory: directory }
    await acquireProjectRoot(config)
    await assert.rejects(() => recoverProjectRoot(config, async () => {}), { code: 'ROOT_IN_USE' })
    const child = spawn(process.execPath, ['-e', '']); const oldPid = child.pid; await once(child, 'exit')
    const locks = path.join(config.stateDirectory, 'roots')
    const filename = path.join(locks, (await readdir(locks))[0], 'owner.json')
    const owner = JSON.parse(await readFile(filename, 'utf8')); owner.processId = oldPid
    await writeFile(filename, JSON.stringify(owner))
    await assert.rejects(() => recoverProjectRoot(config, async () => { throw new Error('server unavailable') }))
    assert.equal((await readProjectRootOwner(config)).hostInstanceId, 'old-host')
    const result = await recoverProjectRoot(config, async stoppedOwner => { assert.equal(stoppedOwner.processId, oldPid) })
    assert.equal(result.recovered, true)
    assert.equal(await readProjectRootOwner(config), null)
  } finally { await rm(directory, { recursive: true, force: true }) }
})
