import test from 'node:test'
import assert from 'node:assert/strict'
import os from 'node:os'
import path from 'node:path'
import { chmod, mkdir, mkdtemp, realpath, rm, symlink, writeFile } from 'node:fs/promises'
import { createCodexReadOnlyProfile, verifyCodexReadOnlyConfig, verifyCodexReadOnlyThread } from '../src/codex-permissions.mjs'

const uuid = () => '12345678-1234-4abc-8def-123456789abc'

async function fixture(fn) {
  const base = await realpath(await mkdtemp(path.join(os.tmpdir(), 'linear-lite-permission-')))
  const projectDirectory = path.join(base, 'project " test')
  const codexBinary = path.join(base, 'codex')
  await mkdir(projectDirectory)
  await writeFile(codexBinary, 'fixture', { mode: 0o700 })
  try { await fn({ base, projectDirectory, codexBinary, baselineSet: {} }) } finally { await rm(base, { recursive: true, force: true }) }
}

function effective(profile) {
  return {
    default_permissions: profile.id, approval_policy: 'never', allow_login_shell: false,
    features: { shell_snapshot: false },
    permissions: { [profile.id]: {
      description: null, extends: null, workspace_roots: null,
      filesystem: { ...profile.filesystem, glob_scan_max_depth: null },
      network: { enabled: false, proxy_url: null, enable_socks5: null, socks_url: null,
        enable_socks5_udp: null, allow_upstream_proxy: null, dangerously_allow_non_loopback_proxy: null,
        dangerously_allow_all_unix_sockets: null, mode: null, domains: null, unix_sockets: null,
        allow_local_binding: null, mitm: null },
    } },
    shell_environment_policy: { inherit: 'none', experimental_use_profile: false, ignore_default_excludes: false,
      exclude: null, include_only: null, filters: { PATH: 'include', LANG: 'include' },
      set: { PATH: '/usr/bin:/bin', LANG: 'C.UTF-8' } },
  }
}

test('命名权限对项目和可信二进制只读，拒绝登录 shell 和网络', async () => fixture(async f => {
  const profile = await createCodexReadOnlyProfile(f, { randomUUID: uuid })
  assert.equal(profile.id, 'linear_lite_1234567812344abc8def123456789abc')
  assert.deepEqual(profile.filesystem, { ':root': 'deny', ':minimal': 'read',
    [f.projectDirectory]: 'read', [f.codexBinary]: 'read' })
  assert.ok(profile.args.includes(`permissions.${profile.id}.network.enabled=false`))
  assert.ok(profile.args.some(value => value.includes(JSON.stringify(f.projectDirectory))))
  assert.ok(profile.args.includes('allow_login_shell=false'))
  assert.ok(profile.args.includes('features.shell_snapshot=false'))
  assert.equal(profile.serverPath, `${f.base}:/usr/bin:/bin`)
  assert.doesNotThrow(() => verifyCodexReadOnlyConfig(effective(profile), profile))
}))

test('项目及二进制须为规范路径、不同位置且二进制可执行', async () => fixture(async f => {
  await assert.rejects(createCodexReadOnlyProfile({ ...f, projectDirectory: 'relative' }), /路径/)
  await assert.rejects(createCodexReadOnlyProfile({ ...f, codexBinary: path.join(f.projectDirectory, 'codex') }), /路径/)
  const alias = path.join(f.base, 'alias')
  await symlink(f.codexBinary, alias)
  await assert.rejects(createCodexReadOnlyProfile({ ...f, codexBinary: alias }), /无法确认/)
  await chmod(f.codexBinary, 0o600)
  await assert.rejects(createCodexReadOnlyProfile(f))
  await assert.rejects(createCodexReadOnlyProfile({ ...f, codexBinary: path.join(f.base, 'a:b') }), /路径/)
}))

test('基线变量按安全名称覆盖为空，未知非空变量拒绝', async () => fixture(async f => {
  const profile = await createCodexReadOnlyProfile({ ...f, baselineSet: { SAFE_A: 'ignored', SAFE_B: 'ignored', PATH: 'ignored' } }, { randomUUID: uuid })
  assert.ok(profile.args.includes('shell_environment_policy.set.SAFE_A=""'))
  assert.ok(profile.args.includes('shell_environment_policy.set.SAFE_B=""'))
  assert.equal(profile.args.some(value => value.includes('ignored')), false)
  const config = effective(profile)
  config.shell_environment_policy.set.SAFE_A = ''
  config.shell_environment_policy.set.SAFE_B = ''
  assert.doesNotThrow(() => verifyCodexReadOnlyConfig(config, profile))
  config.shell_environment_policy.set.NEW = 'fixture'
  assert.throws(() => verifyCodexReadOnlyConfig(config, profile), /无法确认/)
  config.shell_environment_policy.set.NEW = ''
  assert.doesNotThrow(() => verifyCodexReadOnlyConfig(config, profile))
  await assert.rejects(createCodexReadOnlyProfile({ ...f, baselineSet: { 'bad.name': 'ignored' } }), /名称无法覆盖/)
  await assert.rejects(createCodexReadOnlyProfile({ ...f, baselineSet: null }), /名称无法覆盖/)
}))

test('拒绝用户层合并的文件、网络、变量和 shell 覆盖', async () => fixture(async f => {
  const profile = await createCodexReadOnlyProfile(f, { randomUUID: uuid })
  const variants = [
    c => { c.permissions[profile.id].filesystem['/private'] = 'read' },
    c => { c.permissions[profile.id].filesystem[':root'] = 'read' },
    c => { c.permissions[profile.id].extends = ':workspace' },
    c => { c.permissions[profile.id].workspace_roots = { '/private': true } },
    c => { c.permissions[profile.id].network.enabled = true },
    c => { c.permissions[profile.id].network.unix_sockets = { '/private/socket': 'allow' } },
    c => { c.permissions[profile.id].extra = true },
    c => { c.shell_environment_policy.set.EXTRA = 'fixture' },
    c => { c.shell_environment_policy.filters.EXTRA = 'include' },
    c => { c.shell_environment_policy.inherit = 'all' },
    c => { c.shell_environment_policy.experimental_use_profile = true },
    c => { c.shell_environment_policy.ignore_default_excludes = true },
    c => { c.shell_environment_policy.include_only = ['PATH'] },
    c => { c.shell_environment_policy.set.PATH = '/tmp' },
    c => { c.features.shell_snapshot = true },
    c => { c.allow_login_shell = true },
    c => { c.approval_policy = 'on-request' },
    c => { c.default_permissions = 'other' },
  ]
  for (const mutate of variants) {
    const config = effective(profile)
    mutate(config)
    assert.throws(() => verifyCodexReadOnlyConfig(config, profile), /无法确认/)
  }
}))

test('线程回执须与命名 profile、沙箱和持久模式一致', async () => fixture(async f => {
  const profile = await createCodexReadOnlyProfile(f, { randomUUID: uuid })
  const result = { thread: { id: 'thread', ephemeral: false }, approvalPolicy: 'never',
    activePermissionProfile: { id: profile.id, extends: null }, sandbox: { type: 'readOnly', networkAccess: false } }
  assert.doesNotThrow(() => verifyCodexReadOnlyThread(result, profile, false))
  const variants = [
    r => { r.thread.ephemeral = true }, r => { r.thread.id = '' }, r => { r.approvalPolicy = 'on-request' },
    r => { r.activePermissionProfile.id = 'other' }, r => { r.activePermissionProfile.extends = ':workspace' },
    r => { r.sandbox.networkAccess = true }, r => { r.sandbox.type = 'workspaceWrite' },
  ]
  for (const mutate of variants) {
    const receipt = structuredClone(result)
    mutate(receipt)
    assert.throws(() => verifyCodexReadOnlyThread(receipt, profile, false), /无法确认/)
  }
}))
