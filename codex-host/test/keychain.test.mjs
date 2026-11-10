import test from 'node:test'
import assert from 'node:assert/strict'
import { MacKeychainVault } from '../src/keychain.mjs'

test('keychain secret uses stdin, not arguments', async () => {
  const calls = []
  const vault = new MacKeychainVault(async (args, stdin) => {
    calls.push({ args, stdin })
    return args[0] === 'find-generic-password' ? 'fixture-secret' : ''
  })
  await vault.put('attempt:one', 'fixture-secret')
  assert.equal(calls[0].args.includes('fixture-secret'), false)
  assert.ok(calls[0].stdin.includes('fixture-secret'))
  assert.equal(calls[1].args.includes('fixture-secret'), false)
  await assert.rejects(vault.put('attempt:one', 'bad\ncommand'))
})
