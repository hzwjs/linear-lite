import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFileSync, spawn } from 'node:child_process';
import type { ChildProcess } from 'node:child_process';
import { existsSync } from 'node:fs';
import { mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { createServer, Socket } from 'node:net';
import { once } from 'node:events';
import { tmpdir, userInfo } from 'node:os';
import { join } from 'node:path';
import { SshTransport } from '../src/ssh.js';
import type { Profile, Target } from '../src/transport.js';

// Runs a real OpenSSH sshd as the current user, trusting a throwaway user CA.
const SSHD = ['/usr/sbin/sshd', '/usr/bin/sshd'].find(path => existsSync(path));
const USER = userInfo().username;
const PRINCIPAL = `rt.TEST.local.${USER}`;

function keygen(...args: string[]): void {
  execFileSync('ssh-keygen', ['-q', ...args], { stdio: 'ignore' });
}

async function freePort(): Promise<number> {
  const server = createServer();
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  assert(address && typeof address === 'object');
  server.close();
  await once(server, 'close');
  return address.port;
}

async function waitForPort(port: number, sshd: ChildProcess): Promise<void> {
  const deadline = Date.now() + 5000;
  while (Date.now() < deadline) {
    if (sshd.exitCode !== null) throw new Error('test sshd exited');
    const ok = await new Promise<boolean>(resolve => {
      const socket = new Socket();
      socket.once('connect', () => { socket.destroy(); resolve(true); });
      socket.once('error', () => resolve(false));
      socket.connect(port, '127.0.0.1');
    });
    if (ok) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw new Error('test sshd did not start');
}

async function withSshd(run: (fixture: { directory: string; target: Target; sign: (name: string, principal: string, validity?: string) => Promise<Profile> }) => Promise<void>) {
  const directory = await mkdtemp(join(tmpdir(), 'remote-terminal-cert-test-'));
  let sshd: ChildProcess | undefined;
  try {
    keygen('-t', 'ed25519', '-N', '', '-f', join(directory, 'host'));
    keygen('-t', 'ed25519', '-N', '', '-f', join(directory, 'ca'));
    await writeFile(join(directory, 'principals'), `${PRINCIPAL}\n`);
    const port = await freePort();
    await writeFile(join(directory, 'sshd_config'), [
      `Port ${port}`,
      'ListenAddress 127.0.0.1',
      `HostKey ${join(directory, 'host')}`,
      `PidFile ${join(directory, 'sshd.pid')}`,
      `TrustedUserCAKeys ${join(directory, 'ca.pub')}`,
      `AuthorizedPrincipalsFile ${join(directory, 'principals')}`,
      'AuthorizedKeysFile none',
      'PasswordAuthentication no',
      'KbdInteractiveAuthentication no',
      'UsePAM no',
      'StrictModes no',
      '',
    ].join('\n'));
    sshd = spawn(SSHD!, ['-D', '-f', join(directory, 'sshd_config'), '-E', join(directory, 'sshd.log')], { stdio: 'ignore' });
    await waitForPort(port, sshd);
    const target: Target = { target_id: 'local', alias: 'local', environment: 'test', host: '127.0.0.1', port, host_key: (await readFile(join(directory, 'host.pub'), 'utf8')).trim(), profiles: [] };
    const sign = async (name: string, principal: string, validity = '+5m') => {
      const key = join(directory, name);
      keygen('-t', 'ed25519', '-N', '', '-f', key);
      keygen('-s', join(directory, 'ca'), '-I', name, '-n', principal, '-V', validity, `${key}.pub`);
      return { profile_id: name, login_user: USER, private_key_path: key, certificate_path: `${key}-cert.pub`, max_ttl_seconds: 30 };
    };
    await run({ directory, target, sign });
  } finally {
    sshd?.kill();
    if (sshd && sshd.exitCode === null) await once(sshd, 'exit');
    await rm(directory, { recursive: true, force: true });
  }
}

async function runCommand(connection: Awaited<ReturnType<SshTransport['connect']>>, command: string): Promise<string> {
  const channel = await connection.exec(command, { pty: false });
  let output = '';
  channel.stdout.on('data', (chunk: Buffer) => { output += chunk.toString('utf8'); });
  await once(channel.stdout, 'close');
  return output;
}

test('authenticates to OpenSSH with a CA-signed user certificate', { skip: !SSHD }, async () => withSshd(async ({ target, sign }) => {
  const profile = await sign('member', PRINCIPAL);
  const connection = await new SshTransport().connect(target, profile);
  try {
    assert.equal(await runCommand(connection, 'echo certificate-ok'), 'certificate-ok\n');
  } finally {
    connection.close();
  }
}));

test('rejects a certificate for another principal', { skip: !SSHD }, async () => withSshd(async ({ target, sign }) => {
  const profile = await sign('other-host', `rt.TEST.other.${USER}`);
  await assert.rejects(new SshTransport().connect(target, profile), /SSH authentication failed/);
}));

test('rejects an expired certificate', { skip: !SSHD }, async () => withSshd(async ({ target, sign }) => {
  const profile = await sign('expired', PRINCIPAL, '20200101:20200102');
  await assert.rejects(new SshTransport().connect(target, profile), /SSH authentication failed/);
}));

test('rejects a certificate that does not certify the private key', { skip: !SSHD }, async () => withSshd(async ({ directory, target, sign }) => {
  const first = await sign('first', PRINCIPAL);
  const second = await sign('second', PRINCIPAL);
  assert(first.private_key_path !== undefined && second.private_key_path !== undefined);
  const mixed: Profile = { ...first, certificate_path: second.certificate_path };
  await assert.rejects(new SshTransport().connect(target, mixed), (error: Error) => error.message === 'SSH key configuration is invalid');
  const notCertificate: Profile = { ...first, certificate_path: `${join(directory, 'first')}.pub` };
  await assert.rejects(new SshTransport().connect(target, notCertificate), (error: Error) => error.message === 'SSH key configuration is invalid');
}));
