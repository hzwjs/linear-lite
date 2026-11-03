import assert from 'node:assert/strict';
import { test } from 'node:test';
import { execFileSync, spawn } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { once } from 'node:events';
import ssh2 from 'ssh2';
import type { ServerChannel } from 'ssh2';
const { Server, utils } = ssh2;
import { SshTransport } from '../src/ssh.js';
import type { Profile, Target } from '../src/transport.js';

const sftpApi = utils.sftp;
const OPEN = sftpApi.OPEN_MODE;
const STATUS = sftpApi.STATUS_CODE;

async function generateKey(directory: string, name: string, type: 'ed25519' | 'rsa' = 'ed25519'): Promise<{ privatePath: string; publicPath: string; publicText: string }> {
  const privatePath = join(directory, name);
  execFileSync('ssh-keygen', ['-q', '-t', type, ...(type === 'rsa' ? ['-b', '2048'] : []), '-N', '', '-f', privatePath]);
  return { privatePath, publicPath: `${privatePath}.pub`, publicText: (await readFile(`${privatePath}.pub`, 'utf8')).trim() };
}

function pubBlob(line: string): Buffer {
  const key = utils.parseKey(line);
  assert(!(key instanceof Error) && !Array.isArray(key));
  return key.getPublicSSH();
}

async function createFixture(keyType: 'ed25519' | 'rsa' = 'ed25519') {
  const directory = await mkdtemp(join(tmpdir(), 'remote-terminal-ssh-test-'));
  const host = await generateKey(directory, 'host');
  const other = await generateKey(directory, 'other');
  const selected = await generateKey(directory, 'selected', keyType);
  const agentSocket = join(directory, 'agent.sock');
  const agent = spawn('ssh-agent', ['-D', '-a', agentSocket], { stdio: 'ignore' });
  await new Promise<void>((resolve, reject) => {
    const deadline = Date.now() + 4000;
    const check = () => {
      if (Date.now() > deadline) return reject(new Error('test ssh-agent did not start'));
      import('node:fs').then(({ existsSync }) => existsSync(agentSocket) ? resolve() : setTimeout(check, 25));
    };
    check();
  });
  execFileSync('ssh-add', [other.privatePath], { env: { ...process.env, SSH_AUTH_SOCK: agentSocket }, stdio: 'ignore' });
  execFileSync('ssh-add', [selected.privatePath], { env: { ...process.env, SSH_AUTH_SOCK: agentSocket }, stdio: 'ignore' });

  const files = new Map<string, Buffer>();
  files.set('/proc-data', Buffer.from('dynamic-content'));
  const handles = new Map<number, { path: string }>();
  let handleId = 0;
  let lastCommand: string | undefined;
  let sawPty = false;
  let dimensions: number[] | undefined;
  let sawEof = false;
  let sawSignal: string | undefined;
  let rejectedKeyAttempts = 0;
  const server = new Server({ hostKeys: [await readFile(host.privatePath)] }, (client) => {
    client.on('error', () => {});
    client.on('authentication', (ctx) => {
      if (ctx.method !== 'publickey') return ctx.reject();
      const selectedKey = utils.parseKey(selected.publicText);
      if (selectedKey instanceof Error || Array.isArray(selectedKey) || !ctx.key.data.equals(pubBlob(selected.publicText))) {
        rejectedKeyAttempts++;
        return ctx.reject();
      }
      if (!ctx.signature) return ctx.accept();
      if (!ctx.blob || !selectedKey.verify(ctx.blob, ctx.signature, ctx.hashAlgo)) return ctx.reject();
      ctx.accept();
    });
    client.on('ready', () => client.on('session', (accept) => {
      const session = accept();
      session.on('pty', (acceptPty, _reject, info) => {
        sawPty = true;
        dimensions = [info.rows, info.cols];
        acceptPty();
      });
      session.on('window-change', (_accept, _reject, info) => { dimensions = [info.rows, info.cols]; });
      session.on('signal', (_accept, _reject, signal) => { sawSignal = signal.name; });
      session.on('exec', (acceptExec, _reject, info) => {
        lastCommand = info.command;
        const channel = acceptExec() as ServerChannel;
        channel.on('data', (data: Buffer) => channel.write(data));
        channel.on('end', () => {
          sawEof = true;
          if (info.command.includes('no-exit')) channel.close();
          else { channel.exit(0); channel.end(); }
        });
      });
      session.on('sftp', (acceptSftp) => {
        const sftp = acceptSftp();
        sftp.on('OPEN', (id, path, flags) => {
          if (!(flags & OPEN.WRITE) && !(flags & OPEN.READ)) return sftp.status(id, STATUS.FAILURE);
          const filePath = String(path);
          if (flags & OPEN.TRUNC) files.set(filePath, Buffer.alloc(0));
          else if (!files.has(filePath) && flags & OPEN.CREAT) files.set(filePath, Buffer.alloc(0));
          else if (!files.has(filePath)) return sftp.status(id, STATUS.NO_SUCH_FILE);
          const handle = Buffer.alloc(4);
          handle.writeUInt32BE(handleId, 0);
          handles.set(handleId++, { path: filePath });
          sftp.handle(id, handle);
        }).on('WRITE', (id, handle, offset, data) => {
          const entry = handles.get(handle.readUInt32BE(0));
          if (!entry) return sftp.status(id, STATUS.FAILURE);
          const previous = files.get(entry.path) ?? Buffer.alloc(0);
          const next = Buffer.alloc(Math.max(previous.length, offset + data.length));
          previous.copy(next);
          data.copy(next, offset);
          files.set(entry.path, next);
          sftp.status(id, STATUS.OK);
        }).on('FSTAT', (id, handle) => {
          const entry = handles.get(handle.readUInt32BE(0));
          if (!entry) return sftp.status(id, STATUS.FAILURE);
          sftp.attrs(id, { mode: 0o100600, uid: 0, gid: 0, atime: 0, mtime: 0, size: entry.path === '/proc-data' ? 0 : files.get(entry.path)?.length ?? 0 });
        }).on('READ', (id, handle, offset, length) => {
          const entry = handles.get(handle.readUInt32BE(0));
          if (!entry) return sftp.status(id, STATUS.FAILURE);
          const file = files.get(entry.path) ?? Buffer.alloc(0);
          if (offset >= file.length) return sftp.status(id, STATUS.EOF);
          sftp.data(id, file.subarray(offset, offset + length));
        }).on('CLOSE', (id, handle) => {
          handles.delete(handle.readUInt32BE(0));
          sftp.status(id, STATUS.OK);
        });
      });
    }));
  });
  server.on('error', () => {});
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const address = server.address();
  assert(address && typeof address === 'object');
  const profile: Profile = { profile_id: 'test', login_user: 'tester', public_key_path: selected.publicPath, agent_socket: agentSocket, max_ttl_seconds: 30 };
  const target: Target = { target_id: 'local', alias: 'local', environment: 'test', host: '127.0.0.1', port: address.port, host_key: host.publicText, profiles: [profile] };
  return {
    directory, agent, server, profile, target, files, selected,
    state: () => ({ lastCommand, sawPty, dimensions, sawEof, sawSignal, rejectedKeyAttempts }),
    async cleanup() {
      server.close();
      agent.kill('SIGTERM');
      await rm(directory, { recursive: true, force: true });
    },
  };
}

async function withFixture(fn: (fixture: Awaited<ReturnType<typeof createFixture>>) => Promise<void>) {
  const fixture = await createFixture();
  try { await fn(fixture); } finally { await fixture.cleanup(); }
}

async function connect(fixture: Awaited<ReturnType<typeof createFixture>>, target = fixture.target) {
  return new SshTransport().connect(target, fixture.profile);
}

test('uses the selected ssh-agent identity and exact pinned host key', async () => withFixture(async (fixture) => {
  const connection = await connect(fixture);
  assert.equal(connection.isOpen(), true);
  assert.equal(fixture.state().rejectedKeyAttempts, 0);
  connection.close();
}));

test('authenticates with the configured private key file', async () => withFixture(async (fixture) => {
  const profile: Profile = { profile_id: 'key', login_user: 'tester', private_key_path: fixture.selected.privatePath, max_ttl_seconds: 30 };
  const connection = await new SshTransport().connect(fixture.target, profile);
  assert.equal(connection.isOpen(), true);
  assert.equal(fixture.state().rejectedKeyAttempts, 0);
  connection.close();
  await assert.rejects(new SshTransport().connect({ ...fixture.target, host_key: (await generateKey(fixture.directory, 'wrong-private-host')).publicText }, profile), /SSH host key verification failed/);
}));

test('authenticates with an RSA private key file', async () => {
  const fixture = await createFixture('rsa');
  try {
    const profile: Profile = { profile_id: 'key', login_user: 'tester', private_key_path: fixture.selected.privatePath, max_ttl_seconds: 30 };
    const connection = await new SshTransport().connect(fixture.target, profile);
    assert.equal(connection.isOpen(), true);
    connection.close();
  } finally { await fixture.cleanup(); }
});

test('rejects a public key or missing file as a private key without exposing paths', async () => withFixture(async (fixture) => {
  for (const private_key_path of [fixture.selected.publicPath, join(fixture.directory, 'missing')]) {
    const profile: Profile = { profile_id: 'key', login_user: 'tester', private_key_path, max_ttl_seconds: 30 };
    await assert.rejects(new SshTransport().connect(fixture.target, profile), (error: Error) => error.message === 'SSH key configuration is invalid');
  }
}));

test('rejects a host key that differs from the configured wire key', async () => withFixture(async (fixture) => {
  const other = await generateKey(fixture.directory, 'wrong-host');
  const target = { ...fixture.target, host_key: other.publicText };
  await assert.rejects(connect(fixture, target), (error: Error) => error.message === 'SSH host key verification failed');
}));

test('preserves command text, PTY dimensions, input, resize, signal and EOF', async () => withFixture(async (fixture) => {
  const connection = await connect(fixture);
  const command = "printf '  untouched $HOME ;'";
  const channel = await connection.exec(command, { pty: true, rows: 31, cols: 97 });
  assert.equal(fixture.state().lastCommand, command);
  assert.deepEqual(fixture.state().dimensions, [31, 97]);
  const output = new Promise<Buffer>((resolve) => channel.stdout.once('data', (data) => resolve(Buffer.from(data))));
  channel.stdin.write('input-check');
  assert.equal((await output).toString(), 'input-check');
  await channel.resize(42, 132);
  await new Promise((resolve) => setTimeout(resolve, 25));
  assert.deepEqual(fixture.state().dimensions, [42, 132]);
  await channel.signal('TERM');
  await new Promise((resolve) => setTimeout(resolve, 25));
  assert.equal(fixture.state().sawSignal, 'TERM');
  await channel.eof();
  await new Promise((resolve) => channel.stdout.once('close', resolve));
  assert.equal(fixture.state().sawEof, true);
  assert.equal(channel.exitStatus(), 0);
  assert.equal(channel.exitSignal(), null);
  connection.close();
}));

test('keeps exit status and signal nullable when the server omits them', async () => withFixture(async (fixture) => {
  const connection = await connect(fixture);
  const channel = await connection.exec('test no-exit', { pty: false });
  channel.close();
  assert.equal(channel.exitStatus(), null);
  assert.equal(channel.exitSignal(), null);
  connection.close();
}));

test('transfers SFTP chunks at offsets and handles empty files', async () => withFixture(async (fixture) => {
  const connection = await connect(fixture);
  assert.equal(await connection.upload('/data', 0, Buffer.from('abcd'), true), 4);
  assert.equal(await connection.upload('/data', 6, Buffer.from('xy'), false), 2);
  assert.deepEqual(fixture.files.get('/data'), Buffer.from([97, 98, 99, 100, 0, 0, 120, 121]));
  assert.deepEqual(await connection.download('/data', 2, 3), { bytes: Buffer.from([99, 100, 0]), eof: false });
  assert.deepEqual(await connection.download('/data', 7, 10), { bytes: Buffer.from('y'), eof: true });
  assert.equal(await connection.upload('/empty', 0, Buffer.alloc(0), true), 0);
  assert.deepEqual(await connection.download('/empty', 0, 20), { bytes: Buffer.alloc(0), eof: true });
  assert.deepEqual(await connection.download('/empty', 0, 0), { bytes: Buffer.alloc(0), eof: true });
  assert.deepEqual(await connection.download('/data', 0, 0), { bytes: Buffer.alloc(0), eof: false });
  assert.deepEqual(await connection.download('/proc-data', 0, 20), { bytes: Buffer.from('dynamic-content'), eof: true });
  connection.close();
}));

test('close is idempotent', async () => withFixture(async (fixture) => {
  const connection = await connect(fixture);
  let closeCount = 0;
  connection.onClose(() => closeCount++);
  connection.close();
  connection.close();
  assert.equal(connection.isOpen(), false);
  assert.equal(closeCount, 1);
  connection.onClose(() => closeCount++);
  assert.equal(closeCount, 2);
}));
