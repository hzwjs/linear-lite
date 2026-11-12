import { realpath, stat } from 'node:fs/promises'
import path from 'node:path'
import { writeExclusiveJournalFile, writeJournalFile } from './atomic-journal.mjs'
import { groupIsGone, stopProcessGroup } from './process-groups.mjs'

const safeId = /^[A-Za-z0-9_-]{1,128}$/
const outputLimit = 128 * 1024
const inside = (root, value) => value === root || value.startsWith(`${root}${path.sep}`)

/**
 * 实验接口：调用方必须提供已验证的沙箱启动器与卷验证器。
 * 未接入宿主运行链；测试注入的启动器不能作为生产隔离依据。
 */
export async function createManagedCommandGate({ projectDirectory, stateDirectory, attemptId, agentInstanceId,
  isAuthorized, knownCredentials, verifyBoundary, launchWorker, verifyProcessGroup }, dependencies = {}) {
  if (typeof launchWorker !== 'function' || typeof verifyBoundary !== 'function' || typeof verifyProcessGroup !== 'function')
    throw new Error('缺少已验证的命令沙箱')
  if (typeof projectDirectory !== 'string' || !path.isAbsolute(projectDirectory)
      || typeof stateDirectory !== 'string' || !path.isAbsolute(stateDirectory)
      || !Number.isSafeInteger(attemptId) || attemptId <= 0 || typeof agentInstanceId !== 'string' || !safeId.test(agentInstanceId)
      || typeof isAuthorized !== 'function' || typeof knownCredentials !== 'function')
    throw new Error('受管命令边界无效')
  const root = await realpath(projectDirectory)
  const state = await realpath(stateDirectory)
  if (root !== projectDirectory || state !== stateDirectory || inside(root, state) || inside(state, root)
      || !(await stat(root)).isDirectory() || !(await stat(state)).isDirectory())
    throw new Error('受管命令目录无效')
  const stopGroup = dependencies.stopGroup ?? stopProcessGroup
  const isGone = dependencies.isGone ?? groupIsGone
  let accepting = true, active = null, unresolvedStop = null

  function assertOpen() {
    if (!accepting || isAuthorized() !== true) throw new Error('命令能力已停止或租约失效')
  }
  function credentials() {
    const values = knownCredentials()
    if (!Array.isArray(values) || values.some(value => typeof value !== 'string' || !value))
      throw new Error('命令凭据校验不可用')
    return values
  }
  function rejectCredentials(values) {
    const secrets = credentials()
    if (values.some(value => secrets.some(secret => value.includes(secret)))) throw new Error('命令包含执行凭据')
  }
  async function verify(deadline) {
    assertOpen()
    if (active?.timedOut) throw new Error('命令执行超时')
    if (active?.cancelled) throw new Error('命令能力已停止')
    if (await Promise.race([Promise.resolve().then(verifyBoundary), deadline]) !== true)
      throw new Error('命令隔离边界不可用')
    assertOpen()
    if (active?.timedOut) throw new Error('命令执行超时')
    if (active?.cancelled) throw new Error('命令能力已停止')
  }
  async function run(input) {
    assertOpen()
    if (active) throw new Error('当前尝试已有运行中的命令')
    if (!input || typeof input.operationId !== 'string' || !safeId.test(input.operationId) || !Array.isArray(input.argv) || !input.argv.length
        || input.argv.length > 256 || input.argv.some((item, index) => typeof item !== 'string'
          || (index === 0 && !item) || item.includes('\0') || item.length > 32768)
        || typeof input.cwd !== 'string' || path.isAbsolute(input.cwd)
        || !Number.isSafeInteger(input.timeoutMs) || input.timeoutMs < 1 || input.timeoutMs > 3_600_000)
      throw new Error('受管命令参数无效')
    rejectCredentials([agentInstanceId, input.operationId, input.cwd, ...input.argv])
    const cwd = await realpath(path.resolve(root, input.cwd))
    if (!inside(root, cwd) || !(await stat(cwd)).isDirectory()) throw new Error('命令目录越界')
    const payload = JSON.stringify({ argv: input.argv, cwd })
    if (Buffer.byteLength(payload) > 128 * 1024) throw new Error('命令参数超过限制')
    assertOpen()
    if (active) throw new Error('当前尝试已有运行中的命令')
    let resolveDone
    const operation = { child: null, processGroupId: null, cancelled: false, timedOut: false, stopPromise: null,
      done: new Promise(resolve => { resolveDone = resolve }) }
    const stopOnce = () => {
      if (!operation.processGroupId) return Promise.resolve()
      if (!operation.stopPromise) operation.stopPromise = Promise.resolve().then(() => stopGroup(operation.processGroupId))
      return operation.stopPromise
    }
    operation.stopOnce = stopOnce
    active = operation
    const fact = { attemptId, agentInstanceId, operationId: input.operationId, status: 'pending',
      processGroupId: null, startedAt: new Date().toISOString(), finishedAt: null, exitCode: null, timedOut: false }
    const filename = path.join(state, 'command-operations', String(attemptId), `${input.operationId}.json`)
    let journalCreated = false, rejectDeadline
    const deadline = new Promise((resolve, reject) => { rejectDeadline = reject })
    operation.cancel = () => rejectDeadline(new Error('命令能力已停止'))
    void deadline.catch(() => {})
    let timer = setTimeout(() => {
      operation.timedOut = true
      void stopOnce().catch(() => {})
      rejectDeadline(new Error('命令执行超时'))
    }, input.timeoutMs)
    try {
      await writeExclusiveJournalFile(filename, fact, state)
      journalCreated = true
      await verify(deadline)
      const child = launchWorker({ root, cwd })
      let body = '', overflow = false, failed = false
      if (typeof child?.on === 'function') child.on('error', () => { failed = true })
      if (!child || !Number.isSafeInteger(child.pid) || child.pid <= 1 || !child.stdin || !child.stdout)
        throw new Error('命令进程组无效')
      operation.child = child
      child.stdout.setEncoding('utf8')
      child.stdout.on('data', chunk => {
        if (body.length + chunk.length > outputLimit) {
          overflow = true
          void stopOnce().catch(() => {})
        }
        body += chunk.slice(0, Math.max(0, outputLimit - body.length))
      })
      child.stdin.on('error', () => { failed = true })
      const closed = new Promise(resolve => child.once('close', (code, signal) => resolve({ code, signal })))
      if (await Promise.race([Promise.resolve().then(() => verifyProcessGroup(child)), deadline]) !== true) {
        child.stdin.destroy()
        throw new Error('命令进程组归属无法确认')
      }
      operation.processGroupId = child.pid
      fact.processGroupId = child.pid
      await writeJournalFile(filename, fact, state)
      await verify(deadline)
      if (operation.cancelled) throw new Error('命令能力已停止')
      child.stdin.end(payload)
      const termination = await Promise.race([closed, deadline])
      clearTimeout(timer)
      timer = null
      if (!isGone(child.pid)) await stopOnce()
      if (!isGone(child.pid)) throw new Error('命令进程组未确认退出')
      if (operation.cancelled || operation.timedOut || overflow || failed || termination.code !== 0)
        throw new Error('命令执行结果待确认')
      let result
      try { result = JSON.parse(body) } catch { throw new Error('命令执行结果无效') }
      if (!result || typeof result.stdout !== 'string' || typeof result.stderr !== 'string'
          || typeof result.truncated !== 'boolean' || typeof result.failed !== 'boolean'
          || !(result.exitCode === null || Number.isSafeInteger(result.exitCode)))
        throw new Error('命令执行结果无效')
      if (!result.truncated) rejectCredentials([result.stdout, result.stderr])
      fact.status = result.failed || result.exitCode !== 0 ? 'failed' : 'completed'
      fact.exitCode = result.exitCode
      fact.finishedAt = new Date().toISOString()
      await writeJournalFile(filename, fact, state)
      return { exitCode: result.exitCode, signal: typeof result.signal === 'string' ? result.signal : null,
        stdout: result.truncated ? '' : result.stdout, stderr: result.truncated ? '' : result.stderr,
        truncated: result.truncated, failed: result.failed }
    } catch (error) {
      clearTimeout(timer)
      if (operation.child && !operation.processGroupId) {
        operation.child.stdin.destroy()
        accepting = false
        unresolvedStop = new Error('命令进程组归属无法确认')
      }
      if (operation.processGroupId) {
        try { await stopOnce() } catch { accepting = false }
        try {
          if (!isGone(operation.processGroupId)) {
            accepting = false
            unresolvedStop = new Error('命令进程组未确认退出')
          }
        } catch {
          accepting = false
          unresolvedStop = new Error('命令进程组未确认退出')
        }
      }
      fact.status = operation.child ? 'unknown' : 'rejected'
      fact.finishedAt = new Date().toISOString()
      fact.timedOut = operation.timedOut
      if (journalCreated) {
        try { await writeJournalFile(filename, fact, state) } catch { accepting = false }
      }
      if (fact.status === 'unknown') accepting = false
      throw error
    } finally {
      active = null
      resolveDone()
    }
  }
  async function stop() {
    accepting = false
    const operation = active
    if (!operation) {
      if (unresolvedStop) throw unresolvedStop
      return
    }
    operation.cancelled = true
    operation.cancel()
    try { await operation.stopOnce() } catch { /* run verifies the group and preserves failure */ }
    await operation.done
    if (unresolvedStop) throw unresolvedStop
  }
  return { run, stop }
}
