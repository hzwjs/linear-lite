import test from 'node:test';
import assert from 'node:assert/strict';
import { PassThrough, Writable } from 'node:stream';
import { setTimeout as delay } from 'node:timers/promises';
import { TerminalService } from '../src/service.js';
import { configurationSchema } from '../src/config.js';
import { toolSchemas } from '../src/schemas.js';
import type { Configuration, RemoteChannel, RemoteConnection, RemoteTransport, TerminalOptions } from '../src/transport.js';

function configuration(): Configuration { return { targets: [{ target_id: 'test', alias: '测试机', environment: 'test', host: 'private-host', port: 22, host_key: 'ssh-ed25519 public-host-key', profiles: [{ profile_id: 'ops', login_user: 'operator', public_key_path: '/private/identity.pub', agent_socket: '/private/agent.sock', max_ttl_seconds: 60 }] }] }; }
class Channel implements RemoteChannel {
  stdout = new PassThrough(); stderr = new PassThrough(); input: Buffer[] = [];
  stdin = new Writable({ write: (chunk, _encoding, callback) => { this.input.push(Buffer.from(chunk)); callback(); } });
  open = true; status: number | null = null; signalName: string | null = null;
  actions: unknown[] = [];
  isOpen() { return this.open; }
  exitStatus() { return this.status; }
  exitSignal() { return this.signalName; }
  async eof() { this.actions.push('eof'); }
  async resize(rows: number, cols: number) { this.actions.push({ rows, cols }); }
  async signal(name: string) { this.actions.push(name); }
  close() { this.open = false; this.stdout.destroy(); this.stderr.destroy(); this.stdin.destroy(); }
}
class Connection implements RemoteConnection {
  open = true; channels: Channel[] = []; commands: Array<{ command: string; options: TerminalOptions }> = [];
  private closeListeners: Array<() => void> = [];
  file = Buffer.from('0123456789');
  isOpen() { return this.open; }
  onClose(listener: () => void) { this.closeListeners.push(listener); if (!this.open) listener(); }
  async exec(command: string, options: TerminalOptions) { const channel = new Channel(); this.channels.push(channel); this.commands.push({ command, options }); return channel; }
  async upload(_path: string, offset: number, bytes: Buffer, truncate: boolean) {
    if (truncate) this.file = Buffer.alloc(0);
    const next = Buffer.alloc(Math.max(this.file.length, offset + bytes.length)); this.file.copy(next); bytes.copy(next, offset); this.file = next; return bytes.length;
  }
  async download(_path: string, offset: number, maxBytes: number) { const bytes = this.file.subarray(offset, offset + maxBytes); return { bytes, eof: offset + bytes.length >= this.file.length }; }
  close() { if (!this.open) return; this.open = false; this.channels.forEach(channel => channel.close()); this.closeListeners.forEach(listener => listener()); }
}
function fixture() {
  const connection = new Connection();
  const transport: RemoteTransport = { connect: async () => connection };
  const service = new TerminalService(configuration(), transport);
  const open = async (ttl_seconds = 30) => (await service.call('open_session', { target_id: 'test', profile_id: 'ops', ttl_seconds })).session_id as string;
  return { service, connection, open };
}

test('configuration is one strict local whitelist and public catalogue omits credential references', async () => {
  const config = configuration(); assert.equal(configurationSchema.safeParse(config).success, true);
  assert.equal(configurationSchema.safeParse({ ...config, issuer_url: 'https://unused.example' }).success, false);
  assert.equal(configurationSchema.safeParse({ targets: [config.targets[0], config.targets[0]] }).success, false);
  const target = config.targets[0]!;
  const keyProfile = { profile_id: 'key', login_user: 'operator', private_key_path: '/private/key', max_ttl_seconds: 60 };
  assert.equal(configurationSchema.safeParse({ targets: [{ ...target, profiles: [keyProfile] }] }).success, true);
  assert.equal(configurationSchema.safeParse({ targets: [{ ...target, profiles: [{ ...keyProfile, agent_socket: '/private/agent.sock' }] }] }).success, false);
  assert.equal(configurationSchema.safeParse({ targets: [{ ...target, profiles: [{ ...keyProfile, private_key_path: 'relative' }] }] }).success, false);
  const f = fixture();
  try {
    const output = JSON.stringify(await f.service.call('list_targets', {}));
    assert.ok(output.includes('operator')); assert.ok(!output.includes('private-host')); assert.ok(!output.includes('agent.sock')); assert.ok(!output.includes('identity.pub'));
    await assert.rejects(f.service.call('open_session', { target_id: 'other', profile_id: 'ops', ttl_seconds: 10 }), /unavailable/);
  } finally { f.service.close(); }
});

test('original command, nonzero exit and replayable memory output remain transport facts', async () => {
  const f = fixture();
  try {
    const session_id = await f.open(); const command = "printf '<>&中文'\nexit 7";
    const result = await f.service.call('exec', { session_id, command, pty: false });
    assert.deepEqual(f.connection.commands, [{ command, options: { pty: false } }]);
    const channel = f.connection.channels[0]!;
    channel.stdout.end('out'); channel.stderr.end('err'); channel.status = 7; channel.open = false;
    await delay(0);
    const args = { session_id, channel_id: result.channel_id, after_seq: 0, max_bytes: 16384, wait_ms: 1000 };
    const output = await f.service.call('read_output', args);
    assert.equal(output.exit_status, 7); assert.equal(output.eof, true); assert.equal(output.channel_open, false);
    assert.equal((output.chunks as unknown[]).length, 2);
    assert.deepEqual((await f.service.call('read_output', args)).chunks, output.chunks);
  } finally { f.service.close(); }
});

test('EOF before the exit packet waits without inferring an exit status', async () => {
  const f = fixture();
  try {
    const session_id = await f.open(); const result = await f.service.call('exec', { session_id, command: 'command', pty: false });
    const channel = f.connection.channels[0]!; channel.stdout.end(); channel.stderr.end(); await delay(0);
    let settled = false;
    const waiting = f.service.call('read_output', { session_id, channel_id: result.channel_id, after_seq: 0, max_bytes: 16384, wait_ms: 1000 }).then(value => { settled = true; return value; });
    await delay(80); assert.equal(settled, false);
    channel.status = 0; channel.open = false;
    const output = await waiting; assert.equal(output.eof, true); assert.equal(output.exit_status, 0); assert.equal(output.channel_open, false);
  } finally { f.service.close(); }
});

test('terminal input, EOF, resize, signal and close operate on the same channel', async () => {
  const f = fixture();
  try {
    const session_id = await f.open(); const { channel_id } = await f.service.call('exec', { session_id, command: '/bin/sh', pty: true, rows: 24, cols: 80 });
    for (const action of [{ kind: 'data', data_base64: 'aGk=' }, { kind: 'resize', rows: 30, cols: 100 }, { kind: 'signal', signal: 'INT' }, { kind: 'eof' }]) await f.service.call('write_stdin', { session_id, channel_id, action });
    assert.equal(Buffer.concat(f.connection.channels[0]!.input).toString(), 'hi');
    assert.deepEqual(f.connection.channels[0]!.actions, [{ rows: 30, cols: 100 }, 'INT', 'eof']);
    await f.service.call('write_stdin', { session_id, channel_id, action: { kind: 'close' } });
    await assert.rejects(f.service.call('read_output', { session_id, channel_id, after_seq: 0, max_bytes: 16384, wait_ms: 0 }), /unavailable/);
  } finally { f.service.close(); }
});

test('file chunks preserve offsets and support an empty truncated file', async () => {
  const f = fixture();
  try {
    const session_id = await f.open();
    const downloaded = await f.service.call('download', { session_id, remote_path: '/file', offset: 4, max_bytes: 3 });
    assert.deepEqual(downloaded, { data_base64: 'NDU2', next_offset: 7, eof: false });
    await f.service.call('upload', { session_id, remote_path: '/file', offset: 3, data_base64: 'QUJD', truncate: false });
    assert.equal(f.connection.file.toString(), '012ABC6789');
    assert.deepEqual(await f.service.call('upload', { session_id, remote_path: '/file', offset: 0, data_base64: '', truncate: true }), { bytes_written: 0, next_offset: 0 });
    assert.equal(f.connection.file.length, 0);
  } finally { f.service.close(); }
});

test('output capacity evicts old frames while keeping read progress possible', async () => {
  const f = fixture();
  try {
    const session_id = await f.open(); const { channel_id } = await f.service.call('exec', { session_id, command: 'output', pty: false });
    const channel = f.connection.channels[0]!; channel.stdout.end(Buffer.alloc(1024 * 1024 + 8192)); channel.stderr.end(); channel.open = false; await delay(0);
    const output = await f.service.call('read_output', { session_id, channel_id, after_seq: 0, max_bytes: 16384, wait_ms: 0 });
    assert.equal(output.truncated, true); assert.ok(Number(output.first_available_seq) > 1); assert.ok(Number(output.next_seq) >= Number(output.first_available_seq));
    await assert.rejects(f.service.call('read_output', { session_id, channel_id, after_seq: 10000, max_bytes: 16384, wait_ms: 0 }), /exceeds/);
  } finally { f.service.close(); }
});

test('connection expiry releases its channel and rejects the expired handle', async () => {
  const f = fixture();
  try {
    const session_id = await f.open(1); await f.service.call('exec', { session_id, command: 'id', pty: false });
    await delay(1100); assert.equal(f.connection.open, false); assert.equal(f.connection.channels[0]!.open, false);
    await assert.rejects(f.service.call('download', { session_id, remote_path: '/file', offset: 0, max_bytes: 1 }), /unavailable/);
  } finally { f.service.close(); }
});

test('shutdown aborts authentication and closes late connections without publishing a handle', async () => {
  let aborted = false;
  const service = new TerminalService(configuration(), { connect: async (_target, _profile, signal) => new Promise((_resolve, reject) => signal!.addEventListener('abort', () => { aborted = true; reject(new Error('/private/socket')); }, { once: true })) });
  const opening = service.call('open_session', { target_id: 'test', profile_id: 'ops', ttl_seconds: 30 });
  service.close(); await assert.rejects(opening, { message: 'SSH connection could not be opened' }); assert.equal(aborted, true);
});

test('closed channels release channel quota on the existing connection', async () => {
  const f = fixture();
  try {
    const session_id = await f.open(); const channels: string[] = [];
    for (let i = 0; i < 8; i++) channels.push((await f.service.call('exec', { session_id, command: 'id', pty: false })).channel_id as string);
    await assert.rejects(f.service.call('exec', { session_id, command: 'id', pty: false }), /limit reached/);
    await f.service.call('write_stdin', { session_id, channel_id: channels[0], action: { kind: 'close' } });
    assert.ok((await f.service.call('exec', { session_id, command: 'id', pty: false })).channel_id);
  } finally { f.service.close(); }
});

test('peer disconnect releases connection quotas without waiting for TTL or a new read', async () => {
  const connections: Connection[] = [];
  const service = new TerminalService(configuration(), { connect: async () => { const connection = new Connection(); connections.push(connection); return connection; } });
  const args = { target_id: 'test', profile_id: 'ops', ttl_seconds: 30 };
  try {
    for (let i = 0; i < 32; i++) await service.call('open_session', args);
    await assert.rejects(service.call('open_session', args), /limit reached/);
    connections.forEach(connection => connection.close());
    assert.ok((await service.call('open_session', args)).session_id);
  } finally { service.close(); }
});

test('single contract rejects injected identity, noncanonical bytes and invalid PTY fields', () => {
  const session_id = 'e84b4de6-5bd4-4111-9214-caaab9b36b75';
  assert.equal(toolSchemas.exec.safeParse({ session_id, command: 'id', pty: false, host: 'other' }).success, false);
  assert.equal(toolSchemas.exec.safeParse({ session_id, command: 'id', pty: true }).success, false);
  assert.equal(toolSchemas.exec.safeParse({ session_id, command: 'id', pty: false, rows: 24, cols: 80 }).success, false);
  assert.equal(toolSchemas.upload.safeParse({ session_id, remote_path: '/file', offset: 0, data_base64: 'aGk', truncate: false }).success, false);
  assert.equal(toolSchemas.upload.safeParse({ session_id, remote_path: '/file', offset: 1, data_base64: '', truncate: true }).success, false);
});
