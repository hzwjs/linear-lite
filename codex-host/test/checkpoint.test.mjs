import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, mkdir, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { checkpointAvailable, createCheckpoint } from '../src/checkpoint.mjs'

test('checkpoint stores recoverable artifacts and detects changed shared files', async () => {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-checkpoint-')))
  try {
    const project = path.join(base, 'project'), state = path.join(base, 'state')
    await mkdir(project); await mkdir(state)
    await writeFile(path.join(project, 'a.txt'), 'content-a')
    await writeFile(path.join(project, 'b.txt'), 'content-b')
    await mkdir(path.join(project, 'empty'))
    const reference = await createCheckpoint({ stateDirectory: state, projectDirectory: project,
      artifactRefs: ['a.txt'], changedPaths: ['b.txt', 'empty', 'deleted.txt'], knownCredentials: ['fixture-claim'] })
    assert.equal(await checkpointAvailable({ stateDirectory: state, projectDirectory: project, projectCheckpoint: reference, artifactRefs: ['a.txt'] }), true)
    const directory = path.join(state, 'checkpoints', reference.split(':')[1])
    const manifest = JSON.parse(await readFile(path.join(directory, 'manifest.json'), 'utf8'))
    assert.equal(await readFile(path.join(directory, manifest.files.find(x => x.path === 'b.txt').blob), 'utf8'), 'content-b')
    assert.deepEqual(manifest.deletedPaths, ['deleted.txt'])
    await writeFile(path.join(project, 'deleted.txt'), 'resurrected')
    assert.equal(await checkpointAvailable({ stateDirectory: state, projectDirectory: project, projectCheckpoint: reference, artifactRefs: ['a.txt'] }), false)
    await rm(path.join(project, 'deleted.txt'))
    await rm(path.join(project, 'empty'), { recursive: true })
    assert.equal(await checkpointAvailable({ stateDirectory: state, projectDirectory: project, projectCheckpoint: reference, artifactRefs: ['a.txt'] }), false)
    await mkdir(path.join(project, 'empty'))
    await writeFile(path.join(project, 'b.txt'), 'new-content')
    assert.equal(await checkpointAvailable({ stateDirectory: state, projectDirectory: project, projectCheckpoint: reference, artifactRefs: ['a.txt'] }), false)
  } finally { await rm(base, { recursive: true, force: true }) }
})

test('checkpoint rejects known credentials crossing stream chunks and cleans incomplete snapshot', async () => {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-sensitive-')))
  try {
    const project = path.join(base, 'project'), state = path.join(base, 'state')
    await mkdir(project); await mkdir(state)
    await writeFile(path.join(project, 'a.txt'), 'x'.repeat(65530) + 'fixture-secret')
    await assert.rejects(createCheckpoint({ stateDirectory: state, projectDirectory: project,
      artifactRefs: ['a.txt'], changedPaths: [], knownCredentials: ['fixture-secret'] }))
    assert.deepEqual(await readdir(path.join(state, 'checkpoints')), [])
  } finally { await rm(base, { recursive: true, force: true }) }
})
