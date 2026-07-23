import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'
import type { RepositoryConfig } from './config.js'
const exec = promisify(execFile)
export class WorktreeManager {
  constructor(private readonly stateDirectory: string) {}
  async create(repository: RepositoryConfig, branch: string, baseBranch: string, runId: string): Promise<string> {
    const target = join(this.stateDirectory, 'worktrees', runId)
    await mkdir(join(this.stateDirectory, 'worktrees'), { recursive: true })
    await exec('git', ['-C', repository.path, 'remote', 'get-url', 'origin'])
    await exec('git', ['-C', repository.path, 'fetch', 'origin', baseBranch])
    await exec('git', ['-C', repository.path, 'worktree', 'add', '-b', branch, target, `origin/${baseBranch}`])
    return target
  }
}
