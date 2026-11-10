import { test } from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, writeFile, rm, symlink, realpath } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import { loadHostIdentity, validateProjectDirectory } from '../src/device.mjs'

test('device persists per host user state and instance changes on restart', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'll-device-'))
  try {
    const a = await loadHostIdentity(root), b = await loadHostIdentity(root)
    assert.equal(a.deviceId, b.deviceId); assert.notEqual(a.hostInstanceId, b.hostInstanceId)
    const concurrent = await Promise.all(Array.from({ length: 5 }, () => loadHostIdentity(root)))
    assert(concurrent.every(value => value.deviceId === a.deviceId))
  } finally { await rm(root, { recursive: true, force: true }) }
})

test('directory identity validation resolves physical root and derives docs without storing mapping', async () => {
  const root = await mkdtemp(path.join(os.tmpdir(), 'll-directory-'))
  try {
    await mkdir(path.join(root, '项目')); await symlink(path.join(root, '项目'), path.join(root, 'alias'))
    const identity = { deviceId: 'device' }
    const output = await validateProjectDirectory(identity, { deviceId: 'device', projectDirectory: path.join(root, 'alias') })
    assert.equal(output.projectDirectory, await realpath(path.join(root, '项目')))
    assert.equal(output.docsDirectory, path.join(output.projectDirectory, 'docs'))
    await assert.rejects(() => validateProjectDirectory(identity, { deviceId: 'other', projectDirectory: root }), { code: 'DEVICE_MISMATCH' })
    await assert.rejects(() => validateProjectDirectory(identity, { deviceId: 'device', projectDirectory: path.join(root, 'missing') }), { code: 'DIRECTORY_INVALID' })
    await writeFile(path.join(root, 'file'), 'fixture')
    await assert.rejects(() => validateProjectDirectory(identity, { deviceId: 'device', projectDirectory: path.join(root, 'file') }), { code: 'DIRECTORY_INVALID' })
  } finally { await rm(root, { recursive: true, force: true }) }
})
