import test from 'node:test'
import assert from 'node:assert/strict'
import { readProjectVolumeIdentity, requireProjectVolumeIdentity } from '../src/project-volume.mjs'

const uuid = '2F379530-D916-4C74-AE85-6F64D1424864'
const options = { projectDirectory: '/work/base/project', volumeUUID: uuid, helperPath: '/tools/helper',
  stateDirectory: '/users/person/host', codexHome: '/users/person/codex' }

function fixture() {
  const entries = new Map([
    ['/', { device: 1n, inode: 1n }], ['/work', { device: 1n, inode: 12n }],
    ['/work/base', { device: 10n, inode: 15n }], ['/work/base/project', { device: 10n, inode: 11n }],
    ['/tools', { device: 2n, inode: 32n }],
    ['/tools/helper', { device: 2n, inode: 33n }], ['/users', { device: 2n, inode: 30n }],
    ['/users/person', { device: 2n, inode: 31n }], ['/users/person/host', { device: 2n, inode: 13n }],
    ['/users/person/codex', { device: 2n, inode: 14n }],
  ])
  let proof = { base: { device: '10', inode: '15', fsType: 'apfs', volumeUUID: uuid,
    mountPoint: '/work/base', mountFlags: 0 }, root: { device: '10', inode: '11', fsType: 'apfs', volumeUUID: uuid,
    mountPoint: '/work/base', mountFlags: 0 }, mounts: ['/work/base'] }
  let symlink = false
  let helperSymlink = false
  const calls = []
  const fs = {
    async realpath(p) { if (!entries.has(p)) throw new Error('ENOENT'); return p },
    async stat(p) {
      const entry = entries.get(p)
      if (!entry) throw new Error('ENOENT')
      return { dev: entry.device, ino: entry.inode, isDirectory: () => true }
    },
    async lstat(p) {
      const entry = entries.get(p)
      if (!entry) throw new Error('ENOENT')
      if (p.endsWith('/helper')) return { dev: entry.device, ino: entry.inode,
        isFile: () => !helperSymlink, isSymbolicLink: () => helperSymlink }
      return { dev: entry.device, ino: entry.inode, isDirectory: () => !symlink, isSymbolicLink: () => symlink }
    },
  }
  return { entries, fs, dependencies: { fs, platform: 'darwin', runHelper: async (...args) => { calls.push(args); return proof } },
    calls, setProof(value) { proof = value }, getProof() { return proof },
    setSymlink(value) { symlink = value }, setHelperSymlink(value) { helperSymlink = value } }
}

test('trusted helper proves project volume and fixed identity can be revalidated', async () => {
  const f = fixture()
  const identity = await readProjectVolumeIdentity(options, f.dependencies)
  assert.deepEqual(identity, { projectDirectory: options.projectDirectory,
    root: { device: '10', inode: '11' }, volumeDirectory: '/work/base', base: { device: '10', inode: '15' },
    volumeParentDirectory: '/work', volumeParent: { device: '1', inode: '12' },
    stateDirectory: options.stateDirectory, state: { device: '2', inode: '13' },
    codexHome: options.codexHome, codex: { device: '2', inode: '14' }, volumeUUID: uuid, mountFlags: 0,
    helperPath: '/tools/helper', helper: { device: '2', inode: '33' } })
  assert.deepEqual(f.calls, [['/tools/helper', '/work/base/project']])
  const reordered = { volumeUUID: identity.volumeUUID, mountFlags: identity.mountFlags, helper: identity.helper, helperPath: identity.helperPath,
    codex: identity.codex, codexHome: identity.codexHome,
    state: identity.state, stateDirectory: identity.stateDirectory, base: identity.base,
    volumeDirectory: identity.volumeDirectory, volumeParent: identity.volumeParent, volumeParentDirectory: identity.volumeParentDirectory,
    root: identity.root, projectDirectory: identity.projectDirectory }
  assert.deepEqual(await requireProjectVolumeIdentity(options, reordered, f.dependencies), identity)
})

test('missing and malformed configuration cannot enable execution', async () => {
  const f = fixture()
  for (const changes of [
    { volumeUUID: '' }, { volumeUUID: 'not-a-uuid' }, { projectDirectory: '/work/base/project/' },
    { projectDirectory: '/work/base/project/../project' }, { projectDirectory: '/' },
    { stateDirectory: '' }, { codexHome: 'codex' }, { helperPath: '' }, { helperPath: 'helper' },
  ]) await assert.rejects(readProjectVolumeIdentity({ ...options, ...changes }, f.dependencies), /项目卷配置无效/)
  await assert.rejects(requireProjectVolumeIdentity(options, null, f.dependencies), /缺少项目卷身份/)
  await assert.rejects(readProjectVolumeIdentity(options, { ...f.dependencies, platform: 'linux' }), /不支持此平台/)
})

test('helper must prove APFS, UUID, mount root and the same open-directory identity', async () => {
  for (const change of [
    p => { delete p.root }, p => { p.root.fsType = 'hfs' }, p => { delete p.root.fsType },
    p => { p.root.mountPoint = '/work' }, p => { p.root.volumeUUID = 'FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF' },
    p => { p.root.device = '20' }, p => { p.root.inode = '99' }, p => { p.root.device = 10 },
    p => { p.root.mountPoint = '../outside' }, p => { delete p.mounts }, p => { delete p.base },
    p => { p.base.device = '20' }, p => { p.base.inode = '99' }, p => { p.base.fsType = 'hfs' },
    p => { p.base.volumeUUID = 'FFFFFFFF-FFFF-FFFF-FFFF-FFFFFFFFFFFF' },
    p => { p.root.mountFlags = 2 }, p => { p.base.mountFlags = '0' },
    p => { p.base.mountFlags = p.root.mountFlags = -1 },
    p => { p.base.mountFlags = p.root.mountFlags = 0x100000000 },
    p => { p.base.mountFlags = p.root.mountFlags = 0x00200000 },
  ]) {
    const f = fixture(); const proof = f.getProof(); change(proof)
    await assert.rejects(readProjectVolumeIdentity(options, f.dependencies), /项目卷身份无法确认/)
  }
})

test('helper must be a trusted regular executable path outside the project', async () => {
  const symlink = fixture(); symlink.setHelperSymlink(true)
  await assert.rejects(readProjectVolumeIdentity(options, symlink.dependencies), /项目卷身份无法确认/)
  const inner = fixture(); inner.entries.set('/work/base/project/helper', { device: 10n, inode: 50n })
  await assert.rejects(readProjectVolumeIdentity({ ...options, helperPath: '/work/base/project/helper' }, inner.dependencies),
    /项目卷身份无法确认/)
  const failure = fixture(); failure.dependencies.runHelper = async () => { throw new Error('helper failed') }
  await assert.rejects(readProjectVolumeIdentity(options, failure.dependencies), /项目卷身份无法确认/)
  const absent = fixture(); delete absent.dependencies.runHelper
  await assert.rejects(readProjectVolumeIdentity(options, absent.dependencies), /项目卷身份无法确认/)
})

test('directory aliases and same-volume private directories are rejected', async () => {
  const symlink = fixture(); symlink.setSymlink(true)
  await assert.rejects(readProjectVolumeIdentity(options, symlink.dependencies), /项目卷身份无法确认/)
  for (const location of ['/work', '/users/person/host', '/users/person/codex']) {
    const f = fixture(); f.entries.get(location).device = 10n
    await assert.rejects(readProjectVolumeIdentity(options, f.dependencies), /项目卷身份无法确认/)
  }
  const alias = fixture(); const original = alias.fs.realpath
  alias.fs.realpath = async p => p === options.projectDirectory ? '/other/project' : original(p)
  await assert.rejects(readProjectVolumeIdentity(options, alias.dependencies), /项目卷身份无法确认/)
})

test('unmounted fallback, swapped mount and private directory replacement are rejected', async () => {
  for (const change of [
    f => { f.getProof().root.mountPoint = '/work' }, f => { f.entries.get('/work/base/project').inode = 99n },
    f => { f.entries.get('/work/base').inode = 99n },
    f => { f.entries.get('/work').inode = 99n }, f => { f.entries.get('/users/person/host').inode = 99n },
    f => { f.entries.get('/users/person/codex').device = 10n }, f => { f.entries.get('/tools/helper').inode = 99n },
    f => { f.getProof().base.mountFlags = f.getProof().root.mountFlags = 4 },
  ]) {
    const f = fixture(); const identity = await readProjectVolumeIdentity(options, f.dependencies)
    change(f)
    await assert.rejects(requireProjectVolumeIdentity(options, identity, f.dependencies), /项目卷身份/)
  }
})

test('a volume or helper change during inspection fails closed', async () => {
  const changedVolume = fixture()
  changedVolume.dependencies.runHelper = async () => {
    changedVolume.entries.get('/work/base/project').inode = 99n
    return changedVolume.getProof()
  }
  await assert.rejects(readProjectVolumeIdentity(options, changedVolume.dependencies), /项目卷身份无法确认/)
  const changedHelper = fixture()
  changedHelper.dependencies.runHelper = async () => {
    changedHelper.entries.get('/tools/helper').inode = 99n
    return changedHelper.getProof()
  }
  await assert.rejects(readProjectVolumeIdentity(options, changedHelper.dependencies), /项目卷身份无法确认/)
})

test('private paths inside the project are rejected even on another device', async () => {
  for (const field of ['stateDirectory', 'codexHome']) {
    const f = fixture(); f.entries.set('/work/base/project/private', { device: 20n, inode: 18n })
    await assert.rejects(readProjectVolumeIdentity({ ...options, [field]: '/work/base/project/private' }, f.dependencies),
      /项目卷身份无法确认/)
  }
})

test('project must be a direct child and cannot be the volume mount root', async () => {
  const atRoot = fixture()
  atRoot.getProof().root = { ...atRoot.getProof().base }
  await assert.rejects(readProjectVolumeIdentity({ ...options, projectDirectory: '/work/base' }, atRoot.dependencies),
    /项目卷身份无法确认/)
  const deep = fixture()
  deep.entries.set('/work/base/deep', { device: 10n, inode: 50n })
  deep.entries.set('/work/base/deep/project', { device: 10n, inode: 51n })
  deep.getProof().base.inode = '50'
  deep.getProof().root.inode = '51'
  await assert.rejects(readProjectVolumeIdentity({ ...options, projectDirectory: '/work/base/deep/project' }, deep.dependencies),
    /项目卷身份无法确认/)
})

test('nested, missing, duplicated and malformed structured mounts are rejected', async () => {
  const nested = fixture(); nested.entries.set('/work/base/project/child', { device: 21n, inode: 22n })
  nested.getProof().mounts.push('/work/base/project/child')
  await assert.rejects(readProjectVolumeIdentity(options, nested.dependencies), /项目卷身份无法确认/)
  const sibling = fixture(); sibling.entries.set('/work/base/sibling', { device: 21n, inode: 23n })
  sibling.getProof().mounts.push('/work/base/sibling')
  await assert.rejects(readProjectVolumeIdentity(options, sibling.dependencies), /项目卷身份无法确认/)
  for (const mounts of [[], ['/work'], ['/work/base', '/work/base'], ['/not-real'], [null], ['/bad/../path'], ['/work/base/project']]) {
    const f = fixture(); f.getProof().mounts = mounts
    await assert.rejects(readProjectVolumeIdentity(options, f.dependencies), /项目卷身份无法确认/)
  }
})

test('firmlink aliases cannot hide nested mounts or private directories', async () => {
  const f = fixture()
  for (const [p, device, inode] of [
    ['/System', 1n, 32n], ['/System/Volumes', 1n, 33n], ['/System/Volumes/Data', 1n, 34n],
    ['/System/Volumes/Data/work', 1n, 12n], ['/System/Volumes/Data/work/base', 10n, 15n],
    ['/System/Volumes/Data/work/base/project', 10n, 11n], ['/System/Volumes/Data/work/base/project/child', 21n, 22n],
    ['/System/Volumes/Data/work/base/sibling', 21n, 23n],
  ]) f.entries.set(p, { device, inode })
  f.getProof().mounts.push('/System/Volumes/Data/work/base/project/child')
  await assert.rejects(readProjectVolumeIdentity(options, f.dependencies), /项目卷身份无法确认/)
  f.getProof().mounts = ['/work/base', '/System/Volumes/Data/work/base/sibling']
  await assert.rejects(readProjectVolumeIdentity(options, f.dependencies), /项目卷身份无法确认/)
  f.getProof().mounts = ['/work/base']
  await assert.rejects(readProjectVolumeIdentity({ ...options,
    stateDirectory: '/System/Volumes/Data/work/base/project/child' }, f.dependencies), /项目卷身份无法确认/)
})
