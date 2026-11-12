import test from 'node:test'
import assert from 'node:assert/strict'
import { KeychainError, MacKeychainVault } from '../src/keychain.mjs'

test('keychain secret uses stdin, not arguments', async () => {
  const calls = []
  const vault = new MacKeychainVault(async (args, stdin) => {
    calls.push({ args, stdin })
    return args[0] === 'find-generic-password' ? 'fixture-secret' : ''
  })
  await vault.put('attempt:one', 'fixture-secret')
  assert.equal(calls[0].args.includes('fixture-secret'), false)
  assert.ok(calls[0].stdin.includes('fixture-secret'))
  assert.equal(calls[0].stdin.endsWith('\n'), true)
  assert.equal(calls[0].stdin.endsWith('\\n'), false)
  assert.equal(calls[1].args.includes('fixture-secret'), false)
  await assert.rejects(vault.put('attempt:one', 'bad\ncommand'))
})

test('missing Keychain item can be removed without changing another item', async () => {
  const vault = new MacKeychainVault(async () => { throw new KeychainError(44) })
  assert.equal(await vault.removeIfPresent('hostoauth:fixture'), false)
  const locked = new MacKeychainVault(async () => { throw new KeychainError(1) })
  await assert.rejects(locked.removeIfPresent('hostoauth:fixture'), { code: 1 })
})

test('oversized command is rejected before invoking security', async () => {
  let invoked = false
  const vault = new MacKeychainVault(async () => { invoked = true; return '' })
  await assert.rejects(vault.put('hostoauth:fixture', 'x'.repeat(4096)), /凭据过长/)
  assert.equal(invoked, false)
})
