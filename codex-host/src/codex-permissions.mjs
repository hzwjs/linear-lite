import { access, realpath, stat } from 'node:fs/promises'
import { constants } from 'node:fs'
import { randomUUID } from 'node:crypto'
import path from 'node:path'

const ID_PATTERN = /^linear_lite_[a-f0-9]{32}$/
const ENV_NAME = /^[A-Za-z_][A-Za-z0-9_]*$/
const SHELL_PATH = '/usr/bin:/bin'
const SHELL_LANG = 'C.UTF-8'

function isObject(value) {
  return value !== null && typeof value === 'object' && !Array.isArray(value)
}

function exactEntries(actual, expected) {
  return isObject(actual) && Object.keys(actual).length === Object.keys(expected).length
    && Object.entries(expected).every(([key, value]) => Object.hasOwn(actual, key) && actual[key] === value)
}

function canonicalAbsolute(value) {
  return typeof value === 'string' && value.length > 1 && path.isAbsolute(value)
    && path.normalize(value) === value && !value.includes('\0') && !value.endsWith(path.sep)
}

function within(root, candidate) {
  const relative = path.relative(root, candidate)
  return relative === '' || (relative !== '..' && !relative.startsWith(`..${path.sep}`) && !path.isAbsolute(relative))
}

/** 仅构造权限参数；调用者必须先验证项目卷、进程及身份。 */
export async function createCodexReadOnlyProfile({ projectDirectory, codexBinary, baselineSet }, dependencies = {}) {
  const fs = dependencies.fs ?? { realpath, stat, access }
  const uuid = dependencies.randomUUID ?? randomUUID
  if (!canonicalAbsolute(projectDirectory) || !canonicalAbsolute(codexBinary) || codexBinary.includes(':') || within(projectDirectory, codexBinary))
    throw new Error('Codex 权限路径无效')
  if (!isObject(baselineSet) || Object.keys(baselineSet).some(key => !ENV_NAME.test(key)))
    throw new Error('Codex 环境变量名称无法覆盖')
  const [projectReal, binaryReal, projectStat, binaryStat] = await Promise.all([
    fs.realpath(projectDirectory), fs.realpath(codexBinary), fs.stat(projectDirectory), fs.stat(codexBinary),
  ])
  if (projectReal !== projectDirectory || binaryReal !== codexBinary || !projectStat.isDirectory() || !binaryStat.isFile())
    throw new Error('Codex 权限路径无法确认')
  await fs.access(codexBinary, constants.X_OK)
  const rawId = uuid()
  if (typeof rawId !== 'string' || !/^[a-f0-9]{8}-[a-f0-9]{4}-4[a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i.test(rawId))
    throw new Error('Codex 权限标识无效')
  const id = `linear_lite_${rawId.replaceAll('-', '').toLowerCase()}`
  const filesystem = { ':root': 'deny', ':minimal': 'read', [projectDirectory]: 'read', [codexBinary]: 'read' }
  const fsToml = `{${Object.entries(filesystem).map(([key, value]) => `${JSON.stringify(key)}=${JSON.stringify(value)}`).join(',')}}`
  const args = [
    '-c', `default_permissions=${JSON.stringify(id)}`,
    '-c', `permissions.${id}.filesystem=${fsToml}`,
    '-c', `permissions.${id}.network.enabled=false`,
    '-c', 'approval_policy="never"',
    '-c', 'allow_login_shell=false',
    '-c', 'features.shell_snapshot=false',
    '-c', 'shell_environment_policy.inherit="none"',
    '-c', 'shell_environment_policy.experimental_use_profile=false',
    '-c', 'shell_environment_policy.ignore_default_excludes=false',
    '-c', 'shell_environment_policy.filters={PATH="include",LANG="include"}',
    '-c', `shell_environment_policy.set={PATH=${JSON.stringify(SHELL_PATH)},LANG=${JSON.stringify(SHELL_LANG)}}`,
  ]
  // 表按键合并；覆盖已知变量后仍须检查新配置，禁止读取基线值。
  for (const key of Object.keys(baselineSet)) {
    if (key !== 'PATH' && key !== 'LANG') args.push('-c', `shell_environment_policy.set.${key}=""`)
  }
  return Object.freeze({ id, projectDirectory, codexBinary, filesystem: Object.freeze(filesystem), args: Object.freeze(args),
    serverPath: `${path.dirname(codexBinary)}:${SHELL_PATH}` })
}

/** 配置深合并可能保留用户变量；发现额外字段时拒绝，禁止读取其值或输出。 */
export function verifyCodexReadOnlyConfig(config, profile) {
  if (!ID_PATTERN.test(profile?.id ?? '') || !canonicalAbsolute(profile.projectDirectory)
      || !canonicalAbsolute(profile.codexBinary) || profile.codexBinary.includes(':') || within(profile.projectDirectory, profile.codexBinary))
    throw new Error('Codex 权限配置无效')
  const expectedFs = { ':root': 'deny', ':minimal': 'read', [profile.projectDirectory]: 'read',
    [profile.codexBinary]: 'read', glob_scan_max_depth: null }
  const definition = config?.permissions?.[profile.id]
  const expectedNetwork = { enabled: false, proxy_url: null, enable_socks5: null, socks_url: null,
    enable_socks5_udp: null, allow_upstream_proxy: null, dangerously_allow_non_loopback_proxy: null,
    dangerously_allow_all_unix_sockets: null, mode: null, domains: null, unix_sockets: null,
    allow_local_binding: null, mitm: null }
  const env = config?.shell_environment_policy
  if (!isObject(config) || config.default_permissions !== profile.id || config.approval_policy !== 'never'
      || config.allow_login_shell !== false || config.features?.shell_snapshot !== false
      || !isObject(definition) || Object.keys(definition).some(key => !['description', 'extends', 'workspace_roots', 'filesystem', 'network'].includes(key))
      || definition.description !== null || definition.extends !== null || definition.workspace_roots !== null
      || !exactEntries(definition.filesystem, expectedFs) || !exactEntries(definition.network, expectedNetwork)
      || !isObject(env) || Object.keys(env).some(key => !['inherit', 'experimental_use_profile', 'ignore_default_excludes', 'exclude', 'include_only', 'filters', 'set'].includes(key))
      || env.inherit !== 'none' || env.experimental_use_profile !== false || env.ignore_default_excludes !== false
      || env.exclude !== null || env.include_only !== null
      || !exactEntries(env.filters, { PATH: 'include', LANG: 'include' })
      || !isObject(env.set) || !Object.hasOwn(env.set, 'PATH') || !Object.hasOwn(env.set, 'LANG')
      || env.set.PATH !== SHELL_PATH || env.set.LANG !== SHELL_LANG
      || Object.entries(env.set).some(([key, value]) => !ENV_NAME.test(key)
        || (key !== 'PATH' && key !== 'LANG' && value !== ''))) {
    throw new Error('Codex 有效权限配置无法确认')
  }
}

/** 线程回执不替代卷、配置和 MCP 清单核验。 */
export function verifyCodexReadOnlyThread(receipt, profile, ephemeral) {
  if (!ID_PATTERN.test(profile?.id ?? '') || typeof ephemeral !== 'boolean'
      || typeof receipt?.thread?.id !== 'string' || !receipt.thread.id
      || receipt.thread.ephemeral !== ephemeral || receipt.approvalPolicy !== 'never'
      || !exactEntries(receipt.activePermissionProfile, { id: profile.id, extends: null })
      || receipt.sandbox?.type !== 'readOnly' || receipt.sandbox?.networkAccess !== false)
    throw new Error('Codex 线程权限回执无法确认')
}
