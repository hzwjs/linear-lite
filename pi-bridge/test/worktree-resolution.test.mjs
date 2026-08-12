import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn } from 'node:child_process'
import { ProjectConfigStore } from '../src/workspace-config.mjs'
import { ensureWorktree } from '../src/index.mjs'

function command(command, args) {
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ['ignore', 'pipe', 'pipe'] })
    let stderr = ''
    child.stderr.on('data', (chunk) => { stderr += chunk.toString() })
    child.on('error', reject)
    child.on('close', (code) => code === 0 ? resolve() : reject(new Error(stderr || `${command} exited ${code}`)))
  })
}

test('task worktree resolves repository only through projectId mapping', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-worktree-'))
  const repository = join(root, 'repository')
  const otherRepository = join(root, 'other-repository')
  const workspaceRoot = join(root, 'worktrees')
  try {
    await command('git', ['init', '--quiet', repository])
    await command('git', ['-C', repository, 'config', 'user.email', 'pi-bridge-test@example.test'])
    await command('git', ['-C', repository, 'config', 'user.name', 'Pi Bridge Test'])
    await writeFile(join(repository, 'README.md'), 'test\n')
    await command('git', ['-C', repository, 'add', 'README.md'])
    await command('git', ['-C', repository, 'commit', '--quiet', '-m', 'test'])

    const store = new ProjectConfigStore(join(root, 'projects.json'))
    await store.save(42, 'Engineering', repository)
    const worktree = await ensureWorktree(
      { projectId: 42, taskKey: 'LL-1' },
      { configStore: store, workspaceRoot },
    )

    assert.equal(worktree, join(workspaceRoot, '42', 'LL-1'))
    assert.equal(await command('git', ['-C', worktree, 'rev-parse', '--show-toplevel']), undefined)

    await command('git', ['init', '--quiet', otherRepository])
    await command('git', ['-C', otherRepository, 'config', 'user.email', 'pi-bridge-test@example.test'])
    await command('git', ['-C', otherRepository, 'config', 'user.name', 'Pi Bridge Test'])
    await writeFile(join(otherRepository, 'README.md'), 'other\n')
    await command('git', ['-C', otherRepository, 'add', 'README.md'])
    await command('git', ['-C', otherRepository, 'commit', '--quiet', '-m', 'other'])
    await store.save(42, 'Engineering', otherRepository)
    await assert.rejects(
      () => ensureWorktree({ projectId: 42, taskKey: 'LL-1' }, { configStore: store, workspaceRoot }),
      /任务 worktree 与 projectId 对应仓库不一致/,
    )

    await assert.rejects(
      () => ensureWorktree({ projectId: 99, taskKey: 'LL-2' }, { configStore: store, workspaceRoot }),
      /projectId 未配置本地仓库映射：99/,
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
