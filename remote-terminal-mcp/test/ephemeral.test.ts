import assert from 'node:assert/strict';
import test from 'node:test';
import { execFileSync } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import ssh2 from 'ssh2';
import { generateEphemeralKey } from '../src/ephemeral.js';

test('ephemeral key is readable by ssh2 and matches the public line it advertises', () => {
  const key = generateEphemeralKey();
  const parsed = ssh2.utils.parseKey(key.privateKey);
  assert(!(parsed instanceof Error) && !Array.isArray(parsed));
  assert.equal(parsed.isPrivateKey(), true);
  assert.equal(parsed.type, 'ssh-ed25519');
  const advertised = ssh2.utils.parseKey(key.publicKeyLine);
  assert(!(advertised instanceof Error) && !Array.isArray(advertised));
  assert.deepEqual(advertised.getPublicSSH(), parsed.getPublicSSH());
});

test('ephemeral key is a valid OpenSSH private key for ssh-keygen', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'remote-terminal-ephemeral-'));
  try {
    const key = generateEphemeralKey();
    const file = join(directory, 'key');
    await writeFile(file, key.privateKey, { mode: 0o600 });
    const derived = execFileSync('ssh-keygen', ['-y', '-f', file]).toString().trim().split(' ').slice(0, 2).join(' ');
    assert.equal(derived, key.publicKeyLine.split(' ').slice(0, 2).join(' '));
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});

test('every call produces a different key', () => {
  assert.notEqual(generateEphemeralKey().publicKeyLine, generateEphemeralKey().publicKeyLine);
});
