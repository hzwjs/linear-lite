import { access, mkdir, readFile, readdir, rename, writeFile } from 'node:fs/promises'
import { homedir } from 'node:os'
import { join } from 'node:path'
import type { RepositoryConfig, RunnerConfig } from './config.js'
import { ServerClient, type Run } from './serverClient.js'
import { RepositoryRegistry } from './repositoryRegistry.js'
import { WorktreeManager } from './worktreeManager.js'
import { CodexDesktopDriver, type DesktopLaunchRequest } from './codexDesktopDriver.js'
import { activationMarker, buildDesktopTaskPrompt, visualActivationMarker } from './desktopPrompt.js'
import { runnerErrorCode } from './runnerError.js'
import { CodexSessionMonitor } from './codexSessionMonitor.js'

type RunnerClient = Pick<ServerClient, 'heartbeat' | 'claim' | 'lease' | 'started' | 'event' | 'complete'>
interface DesktopLauncher { launch(request: Omit<DesktopLaunchRequest, 'appBundleIdentifier' | 'timeoutSeconds'>): Promise<void> }
export interface RunLoopDependencies { client?: RunnerClient; desktopDriver?: DesktopLauncher; codexDirectory?: string }

export async function runOnce(config: RunnerConfig, dependencies: RunLoopDependencies = {}): Promise<boolean> {
  const client = dependencies.client ?? new ServerClient(config.serverUrl, config.runnerToken)
  const registry = new RepositoryRegistry(config.repositories)
  await client.heartbeat(registry.registration())
  await syncFinishedDesktopRuns(config.stateDirectory, dependencies.codexDirectory ?? join(homedir(), '.codex'), client)
  const run = await client.claim()
  if (!run) return false
  try {
    const shouldRecoverRunningRun = run.status === 'running'
      && await readDesktopRunState(config.stateDirectory, run) == null
    if (run.status !== 'claimed' && !shouldRecoverRunningRun) {
      await client.lease(run.id)
      return true
    }
    const repository = registry.get(run.repositoryKey)
    const worktreePath = await getWorktree(run, config.stateDirectory, repository, new WorktreeManager(config.stateDirectory))
    const marker = activationMarker(run)
    const desktopDriver = dependencies.desktopDriver
      ?? new CodexDesktopDriver(config.stateDirectory, config.codexDesktopAppBundleIdentifier, config.codexDesktopLaunchTimeoutSeconds)
    await desktopDriver.launch({
      projectDirectory: repository.path,
      worktreePath,
      branchName: run.branchName,
      prompt: buildDesktopTaskPrompt(run, worktreePath),
      activationMarker: marker,
      visualActivationMarker: visualActivationMarker(run),
    })
    await mkdir(join(config.stateDirectory, 'runs'), { recursive: true })
    await writeJsonAtomically(join(config.stateDirectory, 'runs', `${run.id}.json`), { runId: run.id, worktreePath, branchName: run.branchName, activationMarker: marker })
    await client.started(run.id)
    await client.event(run.id, 1, 'desktop_session_started', JSON.stringify({ activationMarker: marker, branchName: run.branchName }))
  } catch (error) {
    await client.complete(run.id, { status: 'failed', errorCode: runnerErrorCode(error), errorMessage: error instanceof Error ? error.message : String(error) })
  }
  return true
}

interface CompletionClient { complete(id: string, body: unknown): Promise<void> }
interface DesktopRunState { runId: string; worktreePath: string; branchName: string; activationMarker: string; resultSyncedAt?: string }

/** 在每次领取前收敛已完成的 Codex App 会话；服务端终态事务保证重试不重复评论。 */
export async function syncFinishedDesktopRuns(stateDirectory: string, codexDirectory: string, client: CompletionClient): Promise<void> {
  const runsDirectory = join(stateDirectory, 'runs')
  await mkdir(runsDirectory, { recursive: true })
  const monitor = new CodexSessionMonitor(codexDirectory)
  for (const entry of await readdir(runsDirectory, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith('.json')) continue
    const statePath = join(runsDirectory, entry.name)
    let state: DesktopRunState
    try { state = JSON.parse(await readFile(statePath, 'utf8')) as DesktopRunState } catch (error) {
      console.error(`Runner 状态文件损坏，已跳过: ${statePath}`, error)
      continue
    }
    if (state.resultSyncedAt) continue
    const outcome = await monitor.findOutcome(state.activationMarker)
    if (!outcome) continue
    if (outcome.status === 'completed') {
      await client.complete(state.runId, {
        status: 'completed',
        codexThreadId: outcome.threadId,
        resultSummary: outcome.result,
        resultPayload: JSON.stringify({ source: 'codex_desktop_session', threadId: outcome.threadId, turnId: outcome.turnId, durationMs: outcome.durationMs }),
        errorCode: null,
        errorMessage: null,
      })
    } else {
      await client.complete(state.runId, {
        status: 'failed',
        codexThreadId: outcome.threadId,
        resultSummary: null,
        resultPayload: null,
        errorCode: outcome.errorCode,
        errorMessage: outcome.errorMessage,
      })
    }
    state.resultSyncedAt = new Date().toISOString()
    await writeJsonAtomically(statePath, state)
  }
}

async function writeJsonAtomically(path: string, value: unknown): Promise<void> {
  const temporaryPath = `${path}.${process.pid}.tmp`
  await writeFile(temporaryPath, JSON.stringify(value))
  await rename(temporaryPath, path)
}

async function getWorktree(run: Run, stateDirectory: string, repository: RepositoryConfig, manager: WorktreeManager): Promise<string> {
  if (run.status === 'claimed') return manager.create(repository, run.branchName, run.baseBranch, run.id)
  const state = await readDesktopRunState(stateDirectory, run)
  if (state != null) return state.worktreePath
  const recoveredWorktreePath = join(stateDirectory, 'worktrees', run.id)
  try { await access(recoveredWorktreePath) } catch { throw new Error('WORKTREE_MISSING') }
  return recoveredWorktreePath
}

async function readDesktopRunState(stateDirectory: string, run: Run): Promise<DesktopRunState | null> {
  try {
    const state = JSON.parse(await readFile(join(stateDirectory, 'runs', `${run.id}.json`), 'utf8')) as Partial<DesktopRunState>
    if (state.runId !== run.id
      || typeof state.worktreePath !== 'string' || !state.worktreePath
      || state.branchName !== run.branchName
      || typeof state.activationMarker !== 'string' || !state.activationMarker) return null
    await access(state.worktreePath)
    return state as DesktopRunState
  } catch {
    return null
  }
}
