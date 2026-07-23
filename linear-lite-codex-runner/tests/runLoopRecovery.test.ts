import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { runOnce, type RunLoopDependencies } from '../src/runLoop.js'
import type { RunnerConfig } from '../src/config.js'
import type { Run } from '../src/serverClient.js'

function runningRun(): Run {
  return {
    id: 'run-restore',
    taskKey: 'LINEAR-LITE-47',
    taskSnapshot: JSON.stringify({ taskKey: 'LINEAR-LITE-47', title: '恢复执行', description: '[]' }),
    dispatchInstruction: '',
    repositoryId: 1,
    repositoryKey: 'linear-lite',
    baseBranch: 'main',
    branchName: 'codex/linear-lite-47',
    status: 'running',
  }
}

async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'runner-recovery-'))
  const stateDirectory = join(root, 'state')
  const repositoryPath = join(root, 'repository')
  const codexDirectory = join(root, 'codex')
  await mkdir(repositoryPath, { recursive: true })
  await mkdir(join(codexDirectory, 'sessions'), { recursive: true })
  const config: RunnerConfig = {
    serverUrl: 'http://localhost:8080',
    runnerToken: 'token',
    stateDirectory,
    codexDesktopAppBundleIdentifier: 'com.openai.codex',
    codexDesktopLaunchTimeoutSeconds: 30,
    repositories: {
      'linear-lite': { path: repositoryPath, remoteIdentity: 'origin', defaultBranch: 'main' },
    },
  }
  const run = runningRun()
  const client = {
    heartbeat: vi.fn(async () => undefined),
    claim: vi.fn(async () => run),
    lease: vi.fn(async () => undefined),
    started: vi.fn(async () => undefined),
    event: vi.fn(async () => undefined),
    complete: vi.fn(async () => undefined),
  }
  const desktopDriver = { launch: vi.fn(async () => undefined) }
  const dependencies = { client, desktopDriver, codexDirectory } satisfies RunLoopDependencies
  return { config, run, client, desktopDriver, dependencies }
}

describe('runOnce running run recovery', () => {
  it('relaunches Codex and rebuilds state when worktree exists but local run state is missing', async () => {
    const { config, run, client, desktopDriver, dependencies } = await fixture()
    const worktreePath = join(config.stateDirectory, 'worktrees', run.id)
    await mkdir(worktreePath, { recursive: true })

    await runOnce(config, dependencies)

    expect(desktopDriver.launch).toHaveBeenCalledWith(expect.objectContaining({
      projectDirectory: config.repositories['linear-lite']!.path,
      worktreePath,
      prompt: expect.stringContaining(`执行目录（唯一）：${worktreePath}`),
    }))
    expect(client.started).toHaveBeenCalledWith(run.id)
    expect(client.event).toHaveBeenCalledWith(run.id, 1, 'desktop_session_started', expect.any(String))
    expect(client.lease).not.toHaveBeenCalled()
    expect(JSON.parse(await readFile(join(config.stateDirectory, 'runs', `${run.id}.json`), 'utf8'))).toMatchObject({
      runId: run.id,
      worktreePath,
      branchName: run.branchName,
    })
  })

  it('only renews the lease when a running run already has complete local state', async () => {
    const { config, run, client, desktopDriver, dependencies } = await fixture()
    const worktreePath = join(config.stateDirectory, 'worktrees', run.id)
    await mkdir(join(config.stateDirectory, 'runs'), { recursive: true })
    await mkdir(worktreePath, { recursive: true })
    await writeFile(join(config.stateDirectory, 'runs', `${run.id}.json`), JSON.stringify({
      runId: run.id,
      worktreePath,
      branchName: run.branchName,
      activationMarker: `linear-lite-run:${run.id}`,
    }))

    await runOnce(config, dependencies)

    expect(client.lease).toHaveBeenCalledWith(run.id)
    expect(desktopDriver.launch).not.toHaveBeenCalled()
    expect(client.started).not.toHaveBeenCalled()
    expect(client.event).not.toHaveBeenCalled()
  })
})
