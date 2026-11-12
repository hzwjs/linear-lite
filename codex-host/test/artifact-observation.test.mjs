import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { link, mkdir, mkdtemp, realpath, rename, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createArtifactObserver, readProjectRootIdentity, requireProjectRootIdentity } from '../src/artifact-observation.mjs'
import { createCheckpoint } from '../src/checkpoint.mjs'
import { createWriteGate } from '../src/executor.mjs'

async function fixture(run) {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll146-observe-')))
  try {
    const projectDirectory = path.join(base, 'project'), stateDirectory = path.join(base, 'state')
    await mkdir(projectDirectory); await mkdir(stateDirectory)
    await run({ base, projectDirectory, stateDirectory, knownCredentials: ['fixture-secret'] })
  } finally { await rm(base, { recursive: true, force: true }) }
}

test('observes bytes, missing paths and directory changes in stable path order', async () => fixture(async options => {
  const { projectDirectory: project } = options
  await mkdir(path.join(project, 'docs')); await mkdir(path.join(project, 'docs', 'empty'))
  const bytes = Buffer.from([0, 255, 1])
  await writeFile(path.join(project, 'docs', 'file'), bytes)
  await symlink('/outside', path.join(project, 'docs', 'link'))
  const observer = await createArtifactObserver(options)
  const first = await observer.observeArtifacts({ paths: ['missing/sub', 'docs/file', 'docs/'] })
  assert.deepEqual(first.map(x => x.path), ['docs/', 'docs/file', 'missing/sub'])
  assert.deepEqual(first[2], { path: 'missing/sub', state: 'MISSING' })
  assert.equal(first[1].contentVersion, `sha256:${createHash('sha256').update(bytes).digest('hex')}`)
  assert.match(first[0].contentVersion, /^sha256:[0-9a-f]{64}$/)
  assert.deepEqual(await observer.observeArtifacts({ paths: ['docs/'] }), [first[0]])
  await writeFile(path.join(project, 'docs', 'file'), 'changed')
  assert.notEqual((await observer.observeArtifacts({ paths: ['docs/'] }))[0].contentVersion, first[0].contentVersion)
  assert.deepEqual(await observer.observeArtifacts({ paths: [] }), [])
  assert.deepEqual(await observer.observeArtifacts({ paths: ['docs', 'docs/file/', 'docs/file/child'] }), [
    { path: 'docs', state: 'MISSING' }, { path: 'docs/file/', state: 'MISSING' },
    { path: 'docs/file/child', state: 'MISSING' },
  ])
}))

test('rejects private state, hard links, direct symlinks, credentials and invalid paths', async () => fixture(async options => {
  const { projectDirectory: project, stateDirectory: state } = options
  await writeFile(path.join(state, 'secret'), 'private')
  await link(path.join(state, 'secret'), path.join(project, 'hardlink'))
  await symlink(state, path.join(project, 'alias'))
  await writeFile(path.join(project, 'sensitive'), 'x'.repeat(65530) + 'fixture-secret')
  const observer = await createArtifactObserver(options)
  for (const value of ['hardlink', 'alias/secret', 'sensitive', '../state/secret', '.git/config'])
    await assert.rejects(observer.observeArtifacts({ paths: [value] }), value)
  await assert.rejects(observer.observeArtifacts({ paths: ['sensitive', 'sensitive'] }))
  await assert.rejects(observer.observeArtifacts({ paths: ['hardlink/'] }))
  await writeFile(path.join(project, 'CaseName'), 'value')
  try {
    await realpath(path.join(project, 'casename'))
    await assert.rejects(observer.observeArtifacts({ paths: ['casename'] }))
  } catch (error) { if (error.code !== 'ENOENT') throw error }
}))

test('gate observes while authorized and closes with attempt', async () => fixture(async options => {
  await writeFile(path.join(options.projectDirectory, 'file'), 'ok')
  const gate = await createWriteGate({ ...options, attemptId: 1, agentInstanceId: 'agent', isAuthorized: () => true })
  assert.equal((await gate.observeArtifacts({ paths: ['file'] }))[0].state, 'PRESENT')
  await gate.sealAndDrain()
  await assert.rejects(gate.observeArtifacts({ paths: ['file'] }))
}))

test('root identity prevents observers, gates and checkpoints from switching projects', async () => fixture(async options => {
  const { projectDirectory: project, base } = options
  await writeFile(path.join(project, 'file'), 'old')
  const projectRootIdentity = await readProjectRootIdentity(project)
  assert.deepEqual(await requireProjectRootIdentity(project, projectRootIdentity), projectRootIdentity)
  const observer = await createArtifactObserver({ ...options, projectRootIdentity })
  await rename(project, path.join(base, 'original'))
  await mkdir(project)
  await writeFile(path.join(project, 'file'), 'new')
  await assert.rejects(requireProjectRootIdentity(project, projectRootIdentity), /项目物理目录已变化/)
  await assert.rejects(observer.observeArtifacts({ paths: ['file'] }))
  await assert.rejects(createArtifactObserver({ ...options, projectRootIdentity }), /项目物理目录已变化/)
  await assert.rejects(createWriteGate({ ...options, projectRootIdentity, attemptId: 1,
    agentInstanceId: 'agent', isAuthorized: () => true }), /项目物理目录已变化/)
  await assert.rejects(createCheckpoint({ ...options, projectRootIdentity, artifactRefs: ['file'], changedPaths: [] }),
    /项目物理目录已变化/)
}))
