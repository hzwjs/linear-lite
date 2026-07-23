import { readFile } from 'node:fs/promises'

export interface RepositoryConfig { path: string; remoteIdentity: string; defaultBranch: string; displayName?: string }
export interface RunnerConfig { serverUrl: string; runnerToken: string; stateDirectory: string; repositories: Record<string, RepositoryConfig> }

export async function loadConfig(path: string): Promise<RunnerConfig> {
  const raw = JSON.parse(await readFile(path, 'utf8')) as RunnerConfig
  if (!raw.serverUrl || !raw.runnerToken || !raw.stateDirectory || !raw.repositories) throw new Error('Runner 配置不完整')
  return raw
}
