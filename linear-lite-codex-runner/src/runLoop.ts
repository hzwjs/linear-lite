import { readFile } from 'node:fs/promises'
import { join } from 'node:path'
import type { RepositoryConfig, RunnerConfig } from './config.js'
import { ServerClient, type Run } from './serverClient.js'
import { RepositoryRegistry } from './repositoryRegistry.js'
import { WorktreeManager } from './worktreeManager.js'
import { CodexExecutor } from './codexExecutor.js'

export async function runOnce(config: RunnerConfig): Promise<boolean> {
  const client = new ServerClient(config.serverUrl, config.runnerToken)
  const registry = new RepositoryRegistry(config.repositories)
  await client.heartbeat(registry.registration())
  const run = await client.claim()
  if (!run) return false
  try {
    const repository = registry.get(run.repositoryKey)
    const worktreePath = await getWorktree(run, config.stateDirectory, repository, new WorktreeManager(config.stateDirectory))
    await new CodexExecutor(client, config.stateDirectory).execute(run, worktreePath)
  } catch (error) {
    await client.complete(run.id, { status: 'failed', errorCode: error instanceof Error ? error.message : 'WORKTREE_CREATE_FAILED', errorMessage: error instanceof Error ? error.message : String(error) })
  }
  return true
}

async function getWorktree(run: Run, stateDirectory: string, repository: RepositoryConfig, manager: WorktreeManager): Promise<string> {
  if (!run.codexThreadId) return manager.create(repository, run.branchName, run.baseBranch, run.id)
  try {
    const state = JSON.parse(await readFile(join(stateDirectory, 'runs', `${run.id}.json`), 'utf8')) as { worktreePath?: string; codexThreadId?: string }
    if (!state.worktreePath || state.codexThreadId !== run.codexThreadId) throw new Error('THREAD_IDENTITY_LOST')
    return state.worktreePath
  } catch (error) { if (error instanceof Error && error.message === 'THREAD_IDENTITY_LOST') throw error; throw new Error('WORKTREE_MISSING') }
}
