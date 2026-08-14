import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, realpath } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { ProjectConfigStore } from '../src/workspace-config.mjs'
import { buildPiArgs, resolveTaskDirectory } from '../src/index.mjs'

test('Pi RPC keeps the same skills, extensions and context as an interactive terminal', () => {
  const args = buildPiArgs({ executionId: 'execution-1' }, '/tmp/pi-session')

  assert.deepEqual(args, [
    '--mode', 'rpc',
    '--session-dir', '/tmp/pi-session',
    '--session-id', 'execution-1',
  ])
  assert.equal(args.some((arg) => arg.startsWith('--no-')), false)
})

test('task directory resolves directly from projectId mapping without Git', async () => {
  const root = await mkdtemp(join(tmpdir(), 'pi-bridge-directory-'))
  const directoryPath = join(root, 'plain-directory')
  await mkdir(directoryPath)
  const directory = await realpath(directoryPath)
  try {
    const store = new ProjectConfigStore(join(root, 'projects.json'))
    await store.save(42, 'Engineering', directory)

    assert.equal(
      await resolveTaskDirectory({ projectId: 42, taskKey: 'LL-1' }, { configStore: store }),
      directory,
    )
  } finally {
    await rm(root, { recursive: true, force: true })
  }
})
