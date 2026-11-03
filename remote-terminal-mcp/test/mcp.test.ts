import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { PassThrough, type Readable, type Writable } from 'node:stream';
import { test } from 'node:test';
import type { TerminalService } from '../src/service.js';
import { serveTerminalService } from '../src/main.js';

const MODERN_META = {
  'io.modelcontextprotocol/protocolVersion': '2026-07-28',
  'io.modelcontextprotocol/clientCapabilities': {},
};

class WireClient {
  private buffer = '';
  private readonly pending = new Map<number, (value: Record<string, any>) => void>();
  private nextId = 1;

  readonly messages: Array<Record<string, any>> = [];

  constructor(private readonly input: Writable, private readonly output: Readable) {
    output.setEncoding('utf8');
    output.on('data', (text: string) => {
      this.buffer += text;
      while (true) {
        const newline = this.buffer.indexOf('\n');
        if (newline < 0) return;
        const line = this.buffer.slice(0, newline);
        this.buffer = this.buffer.slice(newline + 1);
        const message = JSON.parse(line) as Record<string, any>;
        this.messages.push(message);
        if (typeof message.id === 'number') this.pending.get(message.id)?.(message);
      }
    });
  }

  request(method: string, params: Record<string, unknown>): Promise<Record<string, any>> {
    const id = this.nextId++;
    return new Promise(resolve => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve({ testTimeout: true, method });
      }, 5000);
      this.pending.set(id, value => {
        clearTimeout(timer);
        this.pending.delete(id);
        resolve(value);
      });
      this.input.write(`${JSON.stringify({ jsonrpc: '2.0', id, method, params })}\n`);
    });
  }

  notify(method: string, params: Record<string, unknown> = {}): void {
    this.input.write(`${JSON.stringify({ jsonrpc: '2.0', method, params })}\n`);
  }
}

function mockService(onClose: () => void = () => {}, failures: Partial<Record<string, string>> = {}) {
  const calls: Array<{ name: string; raw: unknown }> = [];
  let closed = false;
  const service = {
    async call(name: string, raw: unknown) {
      if (closed) throw new Error('Local terminal service is closed');
      calls.push({ name, raw });
      if (failures[name]) throw new Error(failures[name]);
      if (name === 'list_targets') return { targets: [{
        target_id: 'staging-east', alias: 'staging-east', environment: 'staging',
        profiles: [{ profile_id: 'readonly', login_user: 'ops', max_ttl_seconds: 900 }],
      }] };
      return {};
    },
    close() { if (!closed) { closed = true; onClose(); } },
  };
  return { service: service as unknown as TerminalService, calls };
}

function start(service: TerminalService) {
  const input = new PassThrough();
  const output = new PassThrough();
  const stderr: string[] = [];
  const handle = serveTerminalService(service, input, output, message => stderr.push(message));
  return { client: new WireClient(input, output), input, output, stderr, handle };
}

async function initializeLegacy(client: WireClient, version: string) {
  const initialized = await client.request('initialize', {
    protocolVersion: version,
    capabilities: {},
    clientInfo: { name: 'codex-test', version: '1.0.0' },
  });
  assert.ok(initialized.result, JSON.stringify(initialized));
  assert.equal(typeof initialized.result.protocolVersion, 'string');
  client.notify('notifications/initialized');
  return initialized.result.protocolVersion as string;
}

function spawnCli(args: string[]) {
  const cwd = fileURLToPath(new URL('../', import.meta.url));
  const child = spawn(process.execPath, ['--import', 'tsx', 'src/main.ts', ...args], { cwd });
  let stdout = '';
  let stderr = '';
  child.stdout.setEncoding('utf8').on('data', value => { stdout += value; });
  child.stderr.setEncoding('utf8').on('data', value => { stderr += value; });
  const closed = new Promise<{ code: number | null; signal: NodeJS.Signals | null }>(resolve => {
    child.once('close', (code, signal) => resolve({ code, signal }));
  });
  return { child, closed, get stdout() { return stdout; }, get stderr() { return stderr; } };
}

test('modern stdio discovers eight tools, invokes a typed tool, rejects invalid arguments, and closes on EOF', async () => {
  let closed = 0;
  const { service, calls } = mockService(() => { closed++; });
  const { client, input, handle } = start(service);

  const discover = await client.request('server/discover', { _meta: MODERN_META });
  assert.ok(discover.result, JSON.stringify(discover));
  assert.ok(discover.result.supportedVersions.includes('2026-07-28'));

  const list = await client.request('tools/list', { _meta: MODERN_META });
  const tools = list.result.tools as Array<{ name: string; inputSchema: unknown; outputSchema: unknown }>;
  assert.deepEqual(tools.map(tool => tool.name).sort(), [
    'close_session', 'download', 'exec', 'list_targets', 'open_session', 'read_output', 'upload', 'write_stdin',
  ]);
  assert.ok(tools.every(tool => tool.inputSchema && tool.outputSchema));
  // Claude Code skips tools whose input schema has a top-level union.
  assert.ok(tools.every(tool => { const schema = tool.inputSchema as Record<string, unknown>; return schema.type === 'object' && !('oneOf' in schema || 'anyOf' in schema || 'allOf' in schema); }));

  const listed = await client.request('tools/call', {
    _meta: MODERN_META,
    name: 'list_targets',
    arguments: {},
  });
  assert.deepEqual(listed.result.structuredContent.targets[0], {
    target_id: 'staging-east', alias: 'staging-east', environment: 'staging',
    profiles: [{ profile_id: 'readonly', login_user: 'ops', max_ttl_seconds: 900 }],
  });

  const invalid = await client.request('tools/call', {
    _meta: MODERN_META,
    name: 'open_session',
    arguments: { target_id: 'staging-east', extra: 'must be rejected' },
  });
  assert.equal(invalid.result.isError, true);
  assert.deepEqual(calls.map(call => call.name), ['list_targets']);

  input.end();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(closed, 1);
  await handle.close();
  assert.equal(closed, 1);
});

test('standard initialize negotiates legacy protocol versions and serves tools', async t => {
  for (const version of ['2025-06-18', '2025-11-25']) {
    await t.test(version, async () => {
      let closed = 0;
      const { service, calls } = mockService(() => { closed++; });
      const { client, input, handle } = start(service);
      const negotiated = await initializeLegacy(client, version);
      assert.equal(negotiated, version);

      const list = await client.request('tools/list', {});
      const tools = list.result.tools as Array<{ name: string }>;
      assert.deepEqual(tools.map(tool => tool.name).sort(), [
        'close_session', 'download', 'exec', 'list_targets', 'open_session', 'read_output', 'upload', 'write_stdin',
      ]);
      const listed = await client.request('tools/call', { name: 'list_targets', arguments: {} });
      assert.deepEqual(listed.result.structuredContent.targets[0].target_id, 'staging-east');
      const invalid = await client.request('tools/call', {
        name: 'open_session', arguments: { target_id: 'staging-east', extra: 'reject' },
      });
      assert.equal(invalid.result.isError, true);
      assert.deepEqual(calls.map(call => call.name), ['list_targets']);

      input.end();
      await handle.close();
      assert.equal(closed, 1);
    });
  }
});

test('legacy initialization negotiates the latest legacy version for a modern version claim', async () => {
  const { service } = mockService();
  const { client, input, handle } = start(service);
  const negotiated = await initializeLegacy(client, '2026-07-28');
  assert.equal(negotiated, '2025-11-25');
  input.end();
  await handle.close();
});

test('modern discovery probe can transition to legacy initialize without closing the service', async () => {
  let closed = 0;
  const { service } = mockService(() => { closed++; });
  const { client, input, handle } = start(service);
  const discover = await client.request('server/discover', { _meta: MODERN_META });
  assert.ok(discover.result, JSON.stringify(discover));
  const negotiated = await initializeLegacy(client, '2025-06-18');
  assert.equal(negotiated, '2025-06-18');
  const listed = await client.request('tools/call', { name: 'list_targets', arguments: {} });
  assert.equal(listed.result.structuredContent.targets[0].target_id, 'staging-east');
  assert.equal(closed, 0);
  input.end();
  await handle.close();
  assert.equal(closed, 1);
});

test('modern pinned connection rejects a standard initialize request', async () => {
  const { service } = mockService();
  const { client, input, handle } = start(service);
  const discover = await client.request('server/discover', { _meta: MODERN_META });
  assert.ok(discover.result);
  const tools = await client.request('tools/list', { _meta: MODERN_META });
  assert.equal(tools.result.tools.length, 8);
  const response = await client.request('initialize', {
    protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'codex-test', version: '1.0.0' },
  });
  assert.equal(response.error?.code, -32022, JSON.stringify(response));
  input.end();
  await handle.close();
});

test('legacy initialization errors and stdio diagnostics omit sensitive values', async () => {
  const secret = 'PRIVATE_KEY_DO_NOT_LOG';
  const { service } = mockService();
  const { client, input, stderr, handle } = start(service);
  const response = await client.request('initialize', {
    protocolVersion: '2025-11-25',
    capabilities: {},
    clientInfo: { name: secret, version: '1.0' },
  });
  assert.ok(response.result, JSON.stringify(response));
  assert.equal(JSON.stringify(response).includes(secret), false);
  input.end();
  await handle.close();
  assert.equal(stderr.join('\n').includes(secret), false);
});

test('explicit handle close releases an unnegotiated stdio service once', async () => {
  let closed = 0;
  const { service } = mockService(() => { closed++; });
  const { input, handle } = start(service);
  await handle.close();
  await handle.close();
  input.destroy();
  assert.equal(closed, 1);
});

test('stdin EOF releases a service before any handshake', async () => {
  let closed = 0;
  const { service } = mockService(() => { closed++; });
  const { input, handle } = start(service);
  input.end();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(closed, 1);
  await handle.close();
  assert.equal(closed, 1);
});

test('tool errors preserve controlled delivery uncertainty and redact arbitrary failures', async () => {
  const secret = 'SSH_SOCKET_SECRET_DO_NOT_LOG';
  const { service } = mockService(() => {}, {
    write_stdin: 'SSH terminal operation failed; delivery is unknown',
    download: `SSH failure at ${secret}`,
  });
  const { client, input, handle } = start(service);
  await client.request('server/discover', { _meta: MODERN_META });

  const uncertain = await client.request('tools/call', {
    _meta: MODERN_META,
    name: 'write_stdin',
    arguments: {
      session_id: '00000000-0000-4000-8000-000000000001',
      channel_id: '00000000-0000-4000-8000-000000000002',
      action: { kind: 'eof' },
    },
  });
  assert.equal(uncertain.result.isError, true);
  assert.match(uncertain.result.content[0].text, /delivery is unknown/);

  const redacted = await client.request('tools/call', {
    _meta: MODERN_META,
    name: 'download',
    arguments: {
      session_id: '00000000-0000-4000-8000-000000000001',
      remote_path: '/tmp/diagnostic.log', offset: 0, max_bytes: 1024,
    },
  });
  assert.equal(redacted.result.isError, true);
  assert.equal(redacted.result.content[0].text.includes(secret), false);

  input.end();
  await handle.close();
});

test('CLI serves only MCP JSON-RPC, exits on stdin EOF, and keeps startup errors private', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'remote-terminal-mcp-'));
  try {
    const configPath = join(directory, 'targets.json');
    await writeFile(configPath, JSON.stringify({ targets: [] }));
    const cli = spawnCli(['--config', configPath]);
    const client = new WireClient(cli.child.stdin, cli.child.stdout);

    const initialized = await client.request('initialize', {
      protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'codex-cli-test', version: '1.0.0' },
    });
    assert.equal(initialized.result.protocolVersion, '2025-06-18');
    client.notify('notifications/initialized');
    const list = await client.request('tools/list', {});
    assert.deepEqual(list.result.tools.map((tool: { name: string }) => tool.name).sort(), [
      'close_session', 'download', 'exec', 'list_targets', 'open_session', 'read_output', 'upload', 'write_stdin',
    ]);
    const listed = await client.request('tools/call', { name: 'list_targets', arguments: {} });
    assert.deepEqual(listed.result.structuredContent, { targets: [] });

    cli.child.stdin.end();
    const result = await cli.closed;
    assert.equal(result.code, 0);
    assert.equal(result.signal, null);
    const lines = cli.stdout.trimEnd().split('\n').filter(Boolean);
    assert.ok(lines.length >= 3);
    for (const line of lines) assert.equal(JSON.parse(line).jsonrpc, '2.0');
    assert.equal(cli.stderr.includes('targets.json'), false);

    const missing = spawnCli([]);
    const missingResult = await missing.closed;
    assert.equal(missingResult.code, 2);
    assert.equal(missing.stdout, '');
    assert.match(missing.stderr, /^Usage:/);

    const help = spawnCli(['--help']);
    const helpResult = await help.closed;
    assert.equal(helpResult.code, 0);
    assert.equal(help.stdout, '');
    assert.match(help.stderr, /^Usage:/);

    const secret = 'CONFIG_SECRET_DO_NOT_LOG';
    await writeFile(configPath, JSON.stringify({ secret, targets: 'invalid' }));
    const invalid = spawnCli(['--config', configPath]);
    const invalidResult = await invalid.closed;
    assert.equal(invalidResult.code, 1);
    assert.equal(invalid.stdout, '');
    assert.match(invalid.stderr, /Remote terminal MCP could not start/);
    assert.equal(invalid.stderr.includes(secret), false);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
