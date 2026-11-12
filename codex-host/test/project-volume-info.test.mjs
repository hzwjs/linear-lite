import test from 'node:test'
import assert from 'node:assert/strict'
import { execFileSync, spawn } from 'node:child_process'
import { once } from 'node:events'
import { chmodSync, lstatSync, mkdtempSync, mkdirSync, realpathSync, rmSync, statSync, symlinkSync, writeFileSync } from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const source = fileURLToPath(new URL('../native/project-volume-info.c', import.meta.url))
const invoke = (binary, args) => execFileSync(binary, args, { encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] })

test('native mount information identifies one opened APFS project directory', { skip: process.platform !== 'darwin' }, async () => {
  const base = realpathSync(mkdtempSync(path.join(os.tmpdir(), 'linear-lite-volume-info-')))
  const binary = path.join(base, 'volume-info')
  const image = path.join(base, 'fixture.sparseimage')
  const mount = path.join(base, 'mount "quoted" \\ and space\nline')
  const project = path.join(mount, '项目 "quoted"')
  let attached = false
  let sleeper = null
  let sleeperExit = null
  mkdirSync(mount)
  try {
    invoke('/usr/bin/clang', ['-std=c11', '-Wall', '-Wextra', '-Werror', source, '-o', binary])
    invoke('/usr/bin/hdiutil', ['create', '-size', '64m', '-type', 'SPARSE', '-fs', 'APFS', '-volname', 'LinearLiteInfoProbe', '-quiet', image])
    invoke('/usr/bin/hdiutil', ['attach', '-nobrowse', '-noautoopen', '-mountpoint', mount, '-quiet', image])
    attached = true
    mkdirSync(project)
    const result = JSON.parse(invoke(binary, [project]))
    assert.throws(() => invoke(binary, ['check-base', mount]))
    const identity = statSync(project, { bigint: true })
    const baseIdentity = statSync(mount, { bigint: true })
    const plist = execFileSync('/usr/sbin/diskutil', ['info', '-plist', mount], { timeout: 30000 })
    const info = JSON.parse(execFileSync('/usr/bin/plutil', ['-convert', 'json', '-o', '-', '-'], { input: plist, encoding: 'utf8', timeout: 30000 }))
    assert.equal(result.root.device, identity.dev.toString())
    assert.equal(result.root.inode, identity.ino.toString())
    assert.equal(result.root.volumeUUID, info.VolumeUUID.toUpperCase())
    assert.equal(result.root.fsType, 'apfs')
    assert.equal(result.root.mountPoint, mount)
    assert.equal(result.base.device, baseIdentity.dev.toString())
    assert.equal(result.base.inode, baseIdentity.ino.toString())
    assert.equal(result.base.fsType, 'apfs')
    assert.equal(result.base.volumeUUID, info.VolumeUUID.toUpperCase())
    assert.equal(result.base.mountPoint, mount)
    assert.ok(Number.isInteger(result.base.mountFlags) && result.base.mountFlags >= 0 && result.base.mountFlags <= 0xffffffff)
    assert.equal(result.root.mountFlags, result.base.mountFlags)
    assert.equal(result.base.ignoreOwnership, (BigInt(result.base.mountFlags) & 0x00200000n) !== 0n)
    assert.equal(result.root.ignoreOwnership, result.base.ignoreOwnership)
    assert.ok(result.mounts.includes(mount))
    assert.ok(result.mounts.every(value => typeof value === 'string' && path.isAbsolute(value)))
    const alias = path.join(base, 'alias')
    symlinkSync(mount, alias)
    assert.throws(() => invoke(binary, [path.join(alias, path.basename(project))]))
    const projectAlias = path.join(mount, 'project-alias')
    symlinkSync(project, projectAlias)
    assert.throws(() => invoke(binary, [projectAlias]))
    const file = path.join(mount, 'regular-file')
    writeFileSync(file, 'fixture')
    assert.throws(() => invoke(binary, [file]))
    mkdirSync(path.join(project, 'nested'))
    assert.throws(() => invoke(binary, [path.join(project, 'nested')]))
    assert.throws(() => invoke(binary, [mount]))
    assert.throws(() => invoke(binary, ['relative']))
    assert.throws(() => invoke(binary, [path.join(mount, 'missing')]))
    sleeper = spawn('/bin/sleep', ['60'], { cwd: project, stdio: 'ignore' })
    sleeperExit = once(sleeper, 'exit')
    await once(sleeper, 'spawn')
    invoke('/usr/bin/hdiutil', ['detach', '-force', '-quiet', mount])
    attached = false
    assert.throws(() => invoke(binary, [project]))
    if (sleeper.exitCode === null && sleeper.signalCode === null) sleeper.kill('SIGTERM')
    await sleeperExit
    sleeper = null
    sleeperExit = null
    invoke('/usr/bin/hdiutil', ['attach', '-nobrowse', '-noautoopen', '-mountpoint', mount, '-owners', 'on', '-quiet', image])
    attached = true
    const ownershipEnabled = JSON.parse(invoke(binary, [project]))
    assert.equal(ownershipEnabled.base.ignoreOwnership, false)
    assert.equal(ownershipEnabled.root.ignoreOwnership, false)
  } finally {
    if (sleeper && sleeper.exitCode === null && sleeper.signalCode === null) sleeper.kill('SIGTERM')
    if (sleeperExit) await sleeperExit
    if (attached || statSync(mount, { bigint: true }).dev !== statSync(base, { bigint: true }).dev)
      invoke('/usr/bin/hdiutil', ['detach', '-quiet', mount])
    rmSync(base, { recursive: true, force: true })
  }
})

test('unmounted base is empty, mode 000 and has no ACL without changing it', { skip: process.platform !== 'darwin' }, () => {
  const base = realpathSync(mkdtempSync(path.join(os.tmpdir(), 'linear-lite-base-info-')))
  const binary = path.join(base, 'volume-info')
  const empty = path.join(base, 'empty')
  const withAcl = path.join(base, 'with-acl')
  const nonempty = path.join(base, 'nonempty')
  try {
    invoke('/usr/bin/clang', ['-std=c11', '-Wall', '-Wextra', '-Werror', source, '-o', binary])
    mkdirSync(empty, { mode: 0 })
    const before = lstatSync(empty, { bigint: true })
    const result = JSON.parse(invoke(binary, ['check-base', empty]))
    const after = lstatSync(empty, { bigint: true })
    assert.deepEqual(result.base, { device: before.dev.toString(), inode: before.ino.toString(),
      uid: Number(before.uid), mode: 0, entries: 0, hasACL: false })
    assert.equal(after.mode, before.mode)
    assert.equal(result.parent.device, statSync(base, { bigint: true }).dev.toString())
    assert.equal(result.parent.inode, statSync(base, { bigint: true }).ino.toString())
    for (const [name, corruption] of [
      ['missing-attribute', 'entry->returned.commonattr &= ~ATTR_CMN_FILEID;'],
      ['truncated-response', 'entry->length = (uint32_t)(size + 1);'],
    ]) {
      const wrapper = path.join(base, `${name}.c`)
      const brokenBinary = path.join(base, name)
      writeFileSync(wrapper, `#define getattrlistat intercepted_getattrlistat\n#include ${JSON.stringify(source)}\n#undef getattrlistat\nextern int getattrlistat(int, const char *, void *, void *, size_t, unsigned long);\nint intercepted_getattrlistat(int fd, const char *name, void *attrs, void *buffer, size_t size, unsigned long options) { int status = getattrlistat(fd, name, attrs, buffer, size, options); if (status == 0) { struct base_attributes *entry = buffer; ${corruption} } return status; }\n`)
      invoke('/usr/bin/clang', ['-std=c11', '-Wall', '-Wextra', '-Werror', wrapper, '-o', brokenBinary])
      assert.throws(() => invoke(brokenBinary, ['check-base', empty]))
    }

    mkdirSync(withAcl, { mode: 0 })
    invoke('/bin/chmod', ['+a', `${os.userInfo().username} allow read`, withAcl])
    assert.equal(lstatSync(withAcl).mode & 0o7777, 0)
    assert.throws(() => invoke(binary, ['check-base', withAcl]))
    mkdirSync(nonempty)
    writeFileSync(path.join(nonempty, 'fixture'), 'fixture')
    chmodSync(nonempty, 0)
    assert.throws(() => invoke(binary, ['check-base', nonempty]))
    const alias = path.join(base, 'alias')
    symlinkSync(empty, alias)
    assert.throws(() => invoke(binary, ['check-base', alias]))
    const readable = path.join(base, 'readable')
    mkdirSync(readable, { mode: 0o700 })
    assert.throws(() => invoke(binary, ['check-base', readable]))
    assert.throws(() => invoke(binary, ['check-base', binary]))
    assert.throws(() => invoke(binary, ['check-base', path.join(base, 'missing')]))
    assert.throws(() => invoke(binary, ['check-base', 'relative']))
  } finally {
    for (const target of [empty, withAcl, nonempty]) {
      try { chmodSync(target, 0o700) } catch (error) { if (error.code !== 'ENOENT') throw error }
    }
    rmSync(base, { recursive: true, force: true })
  }
})
