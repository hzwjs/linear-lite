import { resolve } from 'node:path'
import type { RepositoryConfig } from './config.js'

/** 服务端只传 repositoryId，Runner 始终在自己的 repositoryKey 白名单中找仓库。 */
export class RepositoryRegistry {
  constructor(private readonly repositories: Record<string, RepositoryConfig>) {}
  get(repositoryKey: string): RepositoryConfig { const value = this.repositories[repositoryKey]; if (!value) throw new Error('REPOSITORY_NOT_REGISTERED'); return { ...value, path: resolve(value.path) } }
  registration() { return Object.entries(this.repositories).map(([repositoryKey, value]) => ({ repositoryKey, displayName: value.displayName ?? repositoryKey, remoteIdentity: value.remoteIdentity, defaultBranch: value.defaultBranch })) }
}
