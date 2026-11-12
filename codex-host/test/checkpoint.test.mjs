import test from 'node:test'
import assert from 'node:assert/strict'
import { createHash } from 'node:crypto'
import { createReadStream } from 'node:fs'
import { mkdtemp, mkdir, open, readFile, readdir, realpath, rm, stat, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { createCheckpoint } from '../src/checkpoint.mjs'

test('checkpoint preserves historical content after shared files change', async () => {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll122-checkpoint-')))
  try {
    const project = path.join(base, 'project'), state = path.join(base, 'state')
    await mkdir(project); await mkdir(state)
    await writeFile(path.join(project, 'a.txt'), 'content-a')
    await writeFile(path.join(project, 'b.txt'), 'content-b')
    await mkdir(path.join(project, 'empty'))
    await mkdir(path.join(project, 'shared'))
    await writeFile(path.join(project, 'shared', 'other-agent.txt'), 'other contribution')
    const reference = await createCheckpoint({ stateDirectory: state, projectDirectory: project,
      artifactRefs: ['a.txt'], changedPaths: ['b.txt', 'empty', 'shared', 'deleted.txt'], knownCredentials: ['fixture-claim'] })
    const directory = path.join(state, 'checkpoints', reference.split(':')[1])
    const manifest = JSON.parse(await readFile(path.join(directory, 'manifest.json'), 'utf8'))
    assert.equal(await readFile(path.join(directory, manifest.files.find(x => x.path === 'b.txt').blob), 'utf8'), 'content-b')
    assert.ok(manifest.directories.includes('empty'))
    assert.ok(manifest.directories.includes('shared'))
    assert.deepEqual(manifest.files.map(file => file.path), ['a.txt', 'b.txt'])
    assert.deepEqual(manifest.deletedPaths, ['deleted.txt'])
    await writeFile(path.join(project, 'b.txt'), 'new-content')
    assert.equal(await readFile(path.join(directory, manifest.files.find(x => x.path === 'b.txt').blob), 'utf8'), 'content-b')
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

test('checkpoint streams a large changed file and removes its temporary spool', async () => {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll146-large-')))
  try {
    const project = path.join(base, 'project'), state = path.join(base, 'state')
    await mkdir(project); await mkdir(state)
    await writeFile(path.join(project, 'result.txt'), 'result')
    const size = 32 * 1024 * 1024
    const handle = await open(path.join(project, 'large.bin'), 'w')
    try { await handle.truncate(size) } finally { await handle.close() }
    const reference = await createCheckpoint({ stateDirectory: state, projectDirectory: project,
      artifactRefs: ['result.txt'], changedPaths: ['large.bin'], knownCredentials: ['fixture-secret'] })
    const directory = path.join(state, 'checkpoints', reference.split(':')[1])
    const manifest = JSON.parse(await readFile(path.join(directory, 'manifest.json'), 'utf8'))
    const entry = manifest.files.find(file => file.path === 'large.bin')
    assert.ok(entry)
    assert.equal((await stat(path.join(directory, entry.blob))).size, size)
    const hash = createHash('sha256'), block = Buffer.alloc(65536)
    for (let index = 0; index < size / block.length; index++) hash.update(block)
    assert.equal(entry.sha256, hash.digest('hex'))
    const storedHash = createHash('sha256')
    for await (const chunk of createReadStream(path.join(directory, entry.blob))) storedHash.update(chunk)
    assert.equal(storedHash.digest('hex'), entry.sha256)
    assert.ok(!(await readdir(directory)).includes('capture.tmp'))
  } finally { await rm(base, { recursive: true, force: true }) }
})

test('checkpoint records directory contents and links without following them', async () => {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll146-checkpoint-dir-')))
  try {
    const project = path.join(base, 'project'), state = path.join(base, 'state')
    await mkdir(project); await mkdir(state); await mkdir(path.join(project, 'docs'))
    await mkdir(path.join(project, 'docs', 'empty'))
    await writeFile(path.join(project, 'docs', 'note.bin'), Buffer.from([0, 255, 1]))
    await symlink('/outside', path.join(project, 'docs', 'link'))
    await assert.rejects(createCheckpoint({ stateDirectory: state, projectDirectory: project,
      artifactRefs: ['docs'], changedPaths: [], knownCredentials: [] }), /成果不存在/)
    const reference = await createCheckpoint({ stateDirectory: state, projectDirectory: project,
      artifactRefs: ['docs/'], changedPaths: [], knownCredentials: [] })
    const directory = path.join(state, 'checkpoints', reference.split(':')[1])
    const manifest = JSON.parse(await readFile(path.join(directory, 'manifest.json'), 'utf8'))
    assert.deepEqual(manifest.directories, ['docs', 'docs/empty'])
    assert.deepEqual(manifest.links, [{ path: 'docs/link', target: '/outside' }])
    assert.deepEqual(await readFile(path.join(directory, manifest.files[0].blob)), Buffer.from([0, 255, 1]))
  } finally { await rm(base, { recursive: true, force: true }) }
})
