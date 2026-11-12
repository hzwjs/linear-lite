import { spawn } from 'node:child_process'
import { randomUUID } from 'node:crypto'
import { realpath, stat } from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { setTimeout as delay } from 'node:timers/promises'
import { pipeline } from 'node:stream/promises'
import { groupIsGone, stopProcessGroup } from './process-groups.mjs'
import { writeExclusiveJournalFile, writeJournalFile } from './atomic-journal.mjs'

// A queue coordinates only the duration of a filesystem operation, not task ownership.
const rootOperations = new Map()
const safeIdentifier = /^[A-Za-z0-9_-]{1,128}$/
const operationTimeoutMs = 30_000

function containsPath(root, candidate) {
  return candidate === root || candidate.startsWith(root + path.sep)
}

export async function readProjectRootIdentity(projectDirectory) {
  if (typeof projectDirectory !== 'string' || !path.isAbsolute(projectDirectory)
      || projectDirectory === path.parse(projectDirectory).root) throw new Error('项目目录无效')
  const root = await realpath(projectDirectory)
  const info = await stat(root, { bigint: true })
  if (root !== projectDirectory || !info.isDirectory() || await realpath(projectDirectory) !== root)
    throw new Error('项目物理目录已变化')
  return { rootDevice: info.dev.toString(), rootInode: info.ino.toString() }
}

export async function requireProjectRootIdentity(projectDirectory, identity) {
  if (!identity || typeof identity.rootDevice !== 'string' || typeof identity.rootInode !== 'string'
      || !/^\d+$/.test(identity.rootDevice) || !/^\d+$/.test(identity.rootInode)) throw new Error('项目身份无效')
  const current = await readProjectRootIdentity(projectDirectory)
  if (current.rootDevice !== identity.rootDevice || current.rootInode !== identity.rootInode)
    throw new Error('项目物理目录已变化')
  return current
}

function profile(root, privateState, node, helper, readOnly = false) {
  const quoted = value => JSON.stringify(value)
  return `(version 1)
(allow default)
(deny network*)
(deny process-exec)
(allow process-exec (literal ${quoted(node)}) (literal "/bin/cp"))
(deny file-read-data)
(deny file-write*)
(allow file-read-data (literal "/") (literal "/dev/null") (literal "/dev/fd/3") (literal "/dev/fd/4") (literal "/bin/cp") (literal ${quoted(node)}) (literal ${quoted(helper)}) (subpath ${quoted(root)}))
(allow file-write* (literal "/dev/fd/4")${readOnly ? '' : ` (subpath ${quoted(root)})`})
(deny file-read* file-write* (subpath ${quoted(privateState)}))`
}

async function ensureGroupStopped(pid) {
  if (!Number.isSafeInteger(pid) || pid <= 1) return
  // An unconfirmed group retains the operation queue and the owning attempt.
  for (;;) {
    try {
      if (groupIsGone(pid)) return
      await stopProcessGroup(pid)
      if (groupIsGone(pid)) return
    } catch { /* Process state remains unknown until the group is gone. */ }
    await delay(1000)
  }
}

function killGroup(pid) {
  if (!Number.isSafeInteger(pid) || pid <= 1) return
  try { process.kill(-pid, 'SIGKILL') } catch { /* close and group verification remain authoritative */ }
}

function runWorker({ root, privateState, rootDevice, rootInode, node, helper, credentials, action, payload, onStarted, readOnly, output }) {
  return new Promise((resolve, reject) => {
    const child = spawn('/usr/bin/sandbox-exec', ['-p', profile(root, privateState, node, helper, readOnly), node, helper], {
      cwd: root, env: { OPENSSL_CONF: '/dev/null' }, stdio: ['pipe', 'pipe', 'ignore', 'ignore', output ? 'pipe' : 'ignore'], detached: true,
    })
    let body = '', settled = false, timedOut = false, processError = false
    let starting = Promise.resolve()
    const pumping = output ? pipeline(child.stdio[4], output).catch(() => {
      processError = true
      killGroup(child.pid)
    }) : Promise.resolve()
    const finish = (error, result) => {
      if (settled) return
      settled = true
      if (error) reject(error); else resolve(result)
    }
    child.stdout.setEncoding('utf8')
    child.stdout.on('data', chunk => { body += chunk })
    // A timeout cannot release the operation queue until the helper has exited.
    const deadline = setTimeout(() => {
      timedOut = true
      killGroup(child.pid)
    }, operationTimeoutMs)
    child.once('error', () => { processError = true })
    child.once('spawn', () => {
      starting = (async () => {
        try {
          if (onStarted) await onStarted(child.pid)
          if (timedOut) return
          child.stdin.end(JSON.stringify({ root, privateState, rootDevice, rootInode, credentials, action, payload }))
        } catch {
          processError = true
          killGroup(child.pid)
          child.stdin.end()
        }
      })()
    })
    child.once('close', async code => {
      clearTimeout(deadline)
      await starting
      await pumping
      await ensureGroupStopped(child.pid)
      if (timedOut || processError || code !== 0) return finish(new Error('受管编辑结果待确认'))
      try {
        const result = JSON.parse(body)
        if (!result || typeof result.ok !== 'boolean') throw new Error('invalid')
        finish(null, result)
      } catch { finish(new Error('受管编辑结果待确认')) }
    })
    child.stdin.on('error', () => {})
  })
}

async function prepareBoundary({ projectDirectory, stateDirectory, knownCredentials, projectRootIdentity }) {
  if (process.platform !== 'darwin') throw new Error('受管编辑沙箱不支持此平台')
  if (typeof projectDirectory !== 'string' || !path.isAbsolute(projectDirectory)
      || typeof stateDirectory !== 'string' || !path.isAbsolute(stateDirectory)
      || !Array.isArray(knownCredentials) || knownCredentials.some(value => typeof value !== 'string' || !value))
    throw new Error('写入边界无效')
  if (knownCredentials.some(secret => projectDirectory.includes(secret) || stateDirectory.includes(secret))) throw new Error('写入边界无效')
  let root, privateState
  try { root = await realpath(projectDirectory); privateState = await realpath(stateDirectory) }
  catch { throw new Error('项目或宿主私有目录无效') }
  if (root !== projectDirectory || root === path.parse(root).root || root === privateState)
    throw new Error('项目或宿主私有目录无效')
  const rootStat = await stat(root, { bigint: true })
  if (!rootStat.isDirectory()) throw new Error('项目目录无效')
  const identity = { rootDevice: rootStat.dev.toString(), rootInode: rootStat.ino.toString() }
  if (projectRootIdentity !== undefined) {
    await requireProjectRootIdentity(projectDirectory, projectRootIdentity)
    if (identity.rootDevice !== projectRootIdentity.rootDevice || identity.rootInode !== projectRootIdentity.rootInode)
      throw new Error('项目物理目录已变化')
  }
  const node = await realpath(process.execPath)
  const helper = await realpath(fileURLToPath(new URL('./executor-worker.mjs', import.meta.url)))
  if (containsPath(root, node) || containsPath(root, helper)
      || containsPath(privateState, node) || containsPath(privateState, helper))
    throw new Error('受管编辑组件位于项目或宿主私有目录内')
  try { if (!(await stat('/usr/bin/sandbox-exec')).isFile()) throw new Error('missing') }
  catch { throw new Error('受管编辑沙箱不可用') }
  return { root, privateState, rootDevice: rootStat.dev.toString(), rootInode: rootStat.ino.toString(),
    node, helper, credentials: [...knownCredentials] }
}

export async function createArtifactObserver(options) {
  const base = await prepareBoundary(options)
  async function execute(action, input, output) {
    if (!input || !Array.isArray(input.paths)) throw new Error('成果路径无效')
    const result = await runWorker({ ...base, readOnly: true, action, payload: input, output })
    if (!result.ok) throw new Error(result.message || '成果观察失败')
    return result.result
  }
  return {
    async observeArtifacts(input) { return execute('observe', input) },
    async captureArtifacts(input, output) {
      if (!output || typeof output.write !== 'function') throw new Error('快照目标无效')
      return execute('capture', input, output)
    },
  }
}

/** The gate authenticates one attempt; it does not own a path or a task-wide filesystem lock. */
export async function createWriteGate({ projectDirectory, stateDirectory, attemptId, agentInstanceId, isAuthorized, knownCredentials, projectRootIdentity }) {
  if (process.platform !== 'darwin') throw new Error('受管编辑沙箱不支持此平台')
  if (!Number.isSafeInteger(attemptId) || attemptId <= 0 || typeof agentInstanceId !== 'string'
      || !safeIdentifier.test(agentInstanceId) || typeof isAuthorized !== 'function'
      || !Array.isArray(knownCredentials) || knownCredentials.some(secret => typeof secret !== 'string' || !secret || agentInstanceId.includes(secret)))
    throw new Error('写入边界无效')
  const base = await prepareBoundary({ projectDirectory, stateDirectory, knownCredentials, projectRootIdentity })
  const { privateState } = base
  const rootKey = `${base.rootDevice}:${base.rootInode}`
  let accepting = true
  const pending = new Set(), changed = new Set(), facts = []

  function requireOpen() {
    let authorized = false
    try { authorized = isAuthorized() === true } catch { /* Authorization failure is closed. */ }
    if (!accepting || !authorized) throw new Error('写入能力已停止或租约失效')
  }
  function safeStrings(...values) {
    if (values.some(value => typeof value !== 'string' || knownCredentials.some(secret => value.includes(secret))))
      throw new Error('操作参数无效或包含执行凭据')
  }
  function enqueue(action) {
    requireOpen()
    const previous = rootOperations.get(rootKey) ?? Promise.resolve()
    const work = previous.then(() => { requireOpen(); return action() })
    const tail = work.catch(() => {})
    rootOperations.set(rootKey, tail)
    pending.add(work)
    void tail.then(() => { if (rootOperations.get(rootKey) === tail) rootOperations.delete(rootKey) })
    void work.then(() => pending.delete(work), () => pending.delete(work))
    return work
  }
  async function journalStart(fact) {
    const filename = path.join(privateState, 'file-operations', String(attemptId), `${fact.operationId}.json`)
    try { await writeExclusiveJournalFile(filename, fact, privateState) }
    catch (error) {
      if (error.code === 'EEXIST') throw new Error('操作标识已存在')
      throw new Error('操作记录保存失败')
    }
    return filename
  }
  async function journalFinish(filename, fact) {
    await writeJournalFile(filename, fact, privateState)
  }
  async function invoke(action, payload) {
    let result
    try { result = await runWorker({ ...base, readOnly: action === 'read' || action === 'list', action, payload }) }
    catch { accepting = false; throw new Error('受管编辑结果待确认') }
    if (!result.ok) throw new Error(result.message || '文件操作失败')
    return result.result
  }
  function mutation(action, input, payload) {
    if (!input || typeof input !== 'object' || Array.isArray(input)
        || typeof input.operationId !== 'string' || !safeIdentifier.test(input.operationId))
      return Promise.reject(new Error('操作标识无效'))
    safeStrings(input.operationId)
    return enqueue(async () => {
      const fact = { attemptId, agentInstanceId, operationId: input.operationId, action,
        status: 'pending', processGroupId: null, changedPaths: [], artifactObservations: [], previousContentVersion: null,
        occurredAt: new Date().toISOString() }
      if (['create', 'patch', 'replace', 'rename'].includes(action)) fact.traceOperationId = `trace-${randomUUID()}`
      const filename = await journalStart(fact)
      let response
      try { requireOpen() }
      catch {
        try { await journalFinish(filename, { ...fact, status: 'rejected' }) }
        catch { accepting = false; throw new Error('操作记录结果待确认') }
        throw new Error('写入能力已停止或租约失效')
      }
      try {
        response = await runWorker({ ...base, action, payload, onStarted: async pid => {
          if (!Number.isSafeInteger(pid) || pid <= 1) throw new Error('执行进程归属无效')
          fact.processGroupId = pid
          await journalFinish(filename, fact)
        } })
      } catch {
        accepting = false
        throw new Error('受管编辑结果待确认')
      }
      if (!response.ok) {
        const unknown = response.committed === true
        if (unknown) accepting = false
        const cleanupPaths = Array.isArray(response.cleanupPaths) && response.cleanupPaths.every(value => typeof value === 'string'
          && !knownCredentials.some(secret => value.includes(secret))) ? response.cleanupPaths : []
        const recorded = { ...fact, status: unknown ? 'unknown' : 'rejected', cleanupPaths }
        try { await journalFinish(filename, recorded) }
        catch { accepting = false; throw new Error('操作记录结果待确认') }
        if (unknown) facts.push(recorded)
        throw new Error(unknown ? '受管编辑结果待确认' : response.message || '文件操作失败')
      }
      const changedPaths = response.result?.changedPaths
      const artifactObservations = response.result?.artifactObservations
      const previousContentVersion = response.result?.previousContentVersion
      const previousPath = action === 'rename' ? response.result?.previousPath : null
      const publishesFile = ['create', 'patch', 'replace', 'rename'].includes(action) && changedPaths?.length > 0
      const tracePath = action === 'rename' ? changedPaths?.[1] : changedPaths?.[0]
      if (!Array.isArray(changedPaths) || changedPaths.some(value => typeof value !== 'string')
          || !Array.isArray(artifactObservations)
          || (publishesFile && (changedPaths.length !== (action === 'rename' ? 2 : 1) || artifactObservations.length !== 1))
          || (!publishesFile && artifactObservations.length !== 0)
          || (publishesFile && ['patch', 'replace', 'rename'].includes(action) && !/^sha256:[a-f0-9]{64}$/.test(previousContentVersion))
          || ((!publishesFile || action === 'create') && previousContentVersion !== null)
          || (action === 'rename' && previousPath !== changedPaths[0])
          || (action === 'rename' && artifactObservations[0]?.contentVersion !== previousContentVersion)
          || artifactObservations.some(item => !item || item.path !== tracePath || item.state !== 'PRESENT'
            || !/^sha256:[a-f0-9]{64}$/.test(item.contentVersion))) {
        accepting = false
        throw new Error('受管编辑结果待确认')
      }
      for (const value of changedPaths) changed.add(value)
      const completed = { ...fact, status: 'completed', changedPaths, artifactObservations, previousContentVersion, previousPath }
      try { await journalFinish(filename, completed) }
      catch { accepting = false; throw new Error('操作记录结果待确认') }
      facts.push(completed)
      return { ...completed, changedPaths: [...completed.changedPaths],
        artifactObservations: completed.artifactObservations.map(item => ({ ...item })) }
    })
  }

  return {
    async observeArtifacts(input) {
      requireOpen()
      if (!input || !Array.isArray(input.paths)) throw new Error('成果路径无效')
      const work = (async () => {
        const result = await runWorker({ ...base, readOnly: true, action: 'observe', payload: { paths: input.paths } })
        if (!result.ok) throw new Error(result.message || '成果观察失败')
        requireOpen()
        return result.result
      })()
      pending.add(work)
      void work.then(() => pending.delete(work), () => pending.delete(work))
      return work
    },
    async readFile(input) {
      if (!input || typeof input !== 'object' || Array.isArray(input)) return Promise.reject(new Error('文件参数无效'))
      return enqueue(() => invoke('read', { path: input.path }))
    },
    async listFiles(input) {
      return enqueue(() => invoke('list', { path: input.path, after: input.after }))
    },
    async createFile(input) { return mutation('create', input, { path: input?.path, content: input?.content }) },
    async applyPatch(input) { return mutation('patch', input, { path: input?.path, version: input?.version,
      oldText: input?.oldText, newText: input?.newText }) },
    async replaceFile(input) { return mutation('replace', input, { path: input?.path, version: input?.version, content: input?.content }) },
    async removeFile(input) { return mutation('remove', input, { path: input?.path, version: input?.version }) },
    async renameFile(input) { return mutation('rename', input, { from: input?.from, to: input?.to, version: input?.version }) },
    async makeDirectory(input) { return mutation('mkdir', input, { path: input?.path }) },
    async sealAndDrain() { accepting = false; await Promise.allSettled([...pending]) },
    changedPaths() { return [...changed] },
    operationFacts() { return facts.map(fact => ({ ...fact, changedPaths: [...fact.changedPaths],
      artifactObservations: fact.artifactObservations.map(item => ({ ...item })),
      ...(fact.cleanupPaths ? { cleanupPaths: [...fact.cleanupPaths] } : {}) })) },
    get accepting() { return accepting },
  }
}
