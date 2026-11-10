import test from 'node:test'
import assert from 'node:assert/strict'
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { spawn } from 'node:child_process'
import path from 'node:path'
import { RecoveryJournal, recoverAttempts } from '../src/recovery.mjs'

function record(processId) {
  return { projectId: 1, bindingId: 2, batchId: 'batch', deviceId: 'device', projectDirectory: '/project',
    hostInstanceId: 'host-123', agentInstanceId: 'agent-123', taskId: 3, inputRevision: 1,
    attemptId: 4, claimOperationId: 'claim-1', processId }
}

test('journal records only execution association and rejects credentials', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'll122-journal-'))
  try {
    const journal = new RecoveryJournal(dir)
    await assert.rejects(journal.save({ ...record(process.pid), claimToken: 'fixture-secret' }))
    await journal.save(record(process.pid))
    const files = await readdir(path.join(dir, 'attempts'))
    assert.equal(files.length, 1)
    const raw = await readFile(path.join(dir, 'attempts', files[0]), 'utf8')
    assert.equal(raw.includes('fixture-secret'), false)
    assert.deepEqual(await journal.list('host-123'), [record(process.pid)])
  } finally { await rm(dir, { recursive: true, force: true }) }
})

test('recovery requires previous host exit and confirms stop before cleanup', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'll122-recover-'))
  try {
    const journal = new RecoveryJournal(dir)
    const alive = record(process.pid)
    await journal.save(alive)
    const routes = []
    const client = { async call(route) { routes.push(route); return { result: route === 'attempts/recovery' ? {} : { accepted: true } } } }
    const vault = { async get() { return 'fixture-claim' }, async remove() { routes.push('vault-remove') } }
    await assert.rejects(recoverAttempts({ records: [alive], identity: { deviceId: 'device' }, client, vault, journal }))
    assert.deepEqual(routes, [])

    const old = spawn(process.execPath, ['-e', ''])
    await new Promise(resolve => old.once('exit', resolve))
    const stopped = record(old.pid)
    await journal.save(stopped)
    await recoverAttempts({ records: [stopped], identity: { deviceId: 'device' }, client, vault, journal })
    assert.deepEqual(routes, ['attempts/recovery', 'attempts/request-stop', 'attempts/confirm-stop', 'vault-remove'])
    assert.deepEqual(await journal.list('host-123'), [])
  } finally { await rm(dir, { recursive: true, force: true }) }
})

test('unknown claim outcome uses the batch credential and preserves the record on failure', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'll122-unknown-'))
  try {
    const old = spawn(process.execPath, ['-e', ''])
    await new Promise(resolve => old.once('exit', resolve))
    const journal = new RecoveryJournal(dir)
    const pending = { ...record(old.pid), attemptId: null }
    await journal.save(pending)
    const vault = { async get(account) { assert.equal(account, 'batch:batch'); return 'batch-secret' } }
    await assert.rejects(recoverAttempts({ records: [pending], identity: { deviceId: 'device' },
      client: { async call() { throw new Error('offline') } }, vault, journal }))
    assert.equal((await journal.list('host-123')).length, 1)
    let submitted
    const client = { async call(route, body) { assert.equal(route, 'attempts/recover-claim'); submitted = body; return { result: { accepted: true } } } }
    await recoverAttempts({ records: [pending], identity: { deviceId: 'device' }, client, vault, journal })
    assert.equal(submitted.batchToken, 'batch-secret')
    assert.equal(submitted.claimOperationId, 'claim-1')
    assert.equal(Object.hasOwn(submitted, 'claimToken'), false)
    assert.equal((await journal.list('host-123')).length, 0)
  } finally { await rm(dir, { recursive: true, force: true }) }
})
