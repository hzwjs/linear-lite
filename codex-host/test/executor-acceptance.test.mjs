import test from 'node:test'
import assert from 'node:assert/strict'
import { chmod, mkdtemp, mkdir, readFile, readdir, realpath, rm, stat, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import path from 'node:path'
import { pathToFileURL } from 'node:url'
import { execFile } from 'node:child_process'
import { promisify } from 'node:util'
import { createWriteGate } from '../src/executor.mjs'

const macOS = { skip: process.platform !== 'darwin' }
const execute = promisify(execFile)

async function fixture() {
  const base = await realpath(await mkdtemp(path.join(tmpdir(), 'll143-acceptance-')))
  const root = path.join(base, 'project')
  const stateA = path.join(base, 'host-a')
  const stateB = path.join(base, 'host-b')
  await Promise.all([root, stateA, stateB].map(directory => mkdir(directory)))
  return {
    base, root, stateA, stateB,
    gate: (attemptId, stateDirectory = stateA) => createWriteGate({
      projectDirectory: root, stateDirectory, attemptId, agentInstanceId: `agent-${attemptId}`,
      isAuthorized: () => true, knownCredentials: [],
    }),
    clean: () => rm(base, { recursive: true, force: true }),
  }
}

test('sealing one attempt leaves another attempt on the same project writable', macOS, async () => {
  const f = await fixture()
  try {
    const a = await f.gate(901), b = await f.gate(902)
    await a.createFile({ operationId: 'a-create', path: 'shared.txt', content: 'initial' })
    const before = await b.readFile({ path: 'shared.txt' })
    await a.sealAndDrain()
    await assert.rejects(a.replaceFile({ operationId: 'a-after-seal', path: before.path,
      version: before.version, content: 'forbidden' }))
    await b.replaceFile({ operationId: 'b-replace', path: before.path, version: before.version, content: 'updated' })
    assert.equal(await readFile(path.join(f.root, 'shared.txt'), 'utf8'), 'updated')
    assert.equal(b.accepting, true)
    assert.deepEqual(b.changedPaths(), ['shared.txt'])
    const facts = b.operationFacts()
    assert.ok(facts.some(fact => fact.operationId === 'b-replace' && fact.attemptId === 902
      && fact.agentInstanceId === 'agent-902' && fact.changedPaths.includes('shared.txt')))
  } finally { await f.clean() }
})

test('same physical project shares edit consistency even with distinct private state directories', macOS, async () => {
  const f = await fixture()
  try {
    await writeFile(path.join(f.root, 'shared.txt'), 'original')
    const a = await f.gate(903, f.stateA), b = await f.gate(904, f.stateB)
    const version = (await a.readFile({ path: 'shared.txt' })).version
    const writes = await Promise.allSettled([
      a.replaceFile({ operationId: 'left', path: 'shared.txt', version, content: 'left' }),
      b.replaceFile({ operationId: 'right', path: 'shared.txt', version, content: 'right' }),
    ])
    assert.equal(writes.filter(result => result.status === 'fulfilled').length, 1)
    assert.equal(writes.filter(result => result.status === 'rejected').length, 1)
    const winner = writes[0].status === 'fulfilled' ? 'left' : 'right'
    assert.equal(await readFile(path.join(f.root, 'shared.txt'), 'utf8'), winner)
    assert.equal(a.operationFacts().length + b.operationFacts().length, 1)
  } finally { await f.clean() }
})

test('atomic content replacement preserves executable permissions', macOS, async () => {
  const f = await fixture()
  try {
    const filename = path.join(f.root, 'build.sh')
    await writeFile(filename, '#!/bin/sh\nexit 0\n')
    await chmod(filename, 0o755)
    const gate = await f.gate(905)
    const read = await gate.readFile({ path: 'build.sh' })
    await gate.applyPatch({ operationId: 'change-script', path: read.path, version: read.version,
      oldText: 'exit 0', newText: 'exit 1' })
    assert.equal((await stat(filename)).mode & 0o777, 0o755)
    assert.equal(await readFile(filename, 'utf8'), '#!/bin/sh\nexit 1\n')
  } finally { await f.clean() }
})

test('atomic content replacement preserves macOS extended attributes and ACL entries', macOS, async () => {
  const f = await fixture()
  try {
    const filename = path.join(f.root, 'metadata.txt')
    await writeFile(filename, 'before')
    await execute('/usr/bin/xattr', ['-w', 'com.linearlite.fixture', 'preserve-this-value', filename])
    await execute('/bin/chmod', ['+a', 'everyone allow read,readattr,readextattr,readsecurity', filename])
    const acl = async () => (await execute('/bin/ls', ['-le', filename])).stdout.match(/^\s*\d+:.*$/gm)
    const before = await acl()
    assert.ok(before?.length)
    const gate = await f.gate(907)
    const read = await gate.readFile({ path: 'metadata.txt' })
    await gate.replaceFile({ operationId: 'preserve-metadata', path: read.path, version: read.version, content: 'after' })
    assert.equal(await readFile(filename, 'utf8'), 'after')
    assert.equal((await execute('/usr/bin/xattr', ['-p', 'com.linearlite.fixture', filename])).stdout.trim(), 'preserve-this-value')
    assert.deepEqual(await acl(), before)
  } finally { await f.clean() }
})

test('atomic replacement cannot bypass the original file deny-write ACL', macOS, async () => {
  const f = await fixture()
  try {
    const filename = path.join(f.root, 'restricted.txt')
    await writeFile(filename, 'protected')
    await execute('/bin/chmod', ['+a', 'everyone deny write,append', filename])
    const gate = await f.gate(908)
    const read = await gate.readFile({ path: 'restricted.txt' })
    await assert.rejects(gate.replaceFile({ operationId: 'denied-write', path: read.path,
      version: read.version, content: 'forbidden' }))
    assert.equal(await readFile(filename, 'utf8'), 'protected')
    assert.deepEqual(gate.changedPaths(), [])
  } finally { await f.clean() }
})

test('failed temporary cleanup is recorded as unknown and seals the owning attempt', macOS, async () => {
  const f = await fixture()
  try {
    const filename = path.join(f.root, 'no-delete.txt')
    await writeFile(filename, 'protected')
    await execute('/bin/chmod', ['+a', 'everyone deny delete', filename])
    const gate = await f.gate(909)
    const read = await gate.readFile({ path: 'no-delete.txt' })
    await assert.rejects(gate.replaceFile({ operationId: 'cleanup-denied', path: read.path,
      version: read.version, content: 'forbidden' }))
    assert.equal(await readFile(filename, 'utf8'), 'protected')
    assert.equal(gate.accepting, false)
    const record = JSON.parse(await readFile(path.join(f.stateA, 'file-operations', '909', 'cleanup-denied.json'), 'utf8'))
    assert.equal(record.status, 'unknown')
    const temporaryFiles = (await readdir(f.root)).filter(name => name.startsWith('.linear-lite-edit-'))
    assert.ok(temporaryFiles.length)
    for (const name of temporaryFiles) assert.ok(JSON.stringify(record).includes(name))
  } finally {
    await execute('/bin/chmod', ['-RN', f.base])
    await f.clean()
  }
})

test('editor helpers do not inherit NODE_OPTIONS injection from the parent environment', macOS, async () => {
  const f = await fixture()
  const previous = process.env.NODE_OPTIONS
  try {
    const marker = path.join(f.root, 'injected.txt')
    const injected = path.join(f.base, 'injected.mjs')
    await writeFile(injected, `import {writeFileSync} from 'node:fs';writeFileSync(${JSON.stringify(marker)},'injected')`)
    process.env.NODE_OPTIONS = `--import=${pathToFileURL(injected).href}`
    const gate = await f.gate(906)
    await gate.createFile({ operationId: 'clean-environment', path: 'valid.txt', content: 'valid' })
    assert.equal(await readFile(path.join(f.root, 'valid.txt'), 'utf8'), 'valid')
    await assert.rejects(stat(marker), { code: 'ENOENT' })
  } finally {
    if (previous === undefined) delete process.env.NODE_OPTIONS
    else process.env.NODE_OPTIONS = previous
    await f.clean()
  }
})
