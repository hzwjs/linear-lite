import assert from 'node:assert/strict';
import test from 'node:test';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import type { IncomingMessage, Server } from 'node:http';
import { mkdtemp, readFile, rm, stat, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { parseArgs } from '../src/main.js';
import { CloudSource } from '../src/cloud.js';
import { accessToken, login, logout, normalizeServer } from '../src/oauth.js';
import { checkPin, fingerprint, recordPin } from '../src/pins.js';
import { ControlledError } from '../src/errors.js';
import { HttpError } from '../src/http.js';

const HOST_KEY = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGJhc2UgaG9zdCBrZXkgZm9yIHRlc3Rpbmc= comment-is-ignored';
const OTHER_KEY = 'ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGFub3RoZXIgaG9zdCBrZXkgZm9yIHRlc3Q=';

async function withHome(run: (home: string) => Promise<void>) {
  const home = await mkdtemp(join(tmpdir(), 'remote-terminal-team-'));
  const previous = process.env.REMOTE_TERMINAL_MCP_HOME;
  process.env.REMOTE_TERMINAL_MCP_HOME = home;
  try { await run(home); } finally {
    if (previous === undefined) delete process.env.REMOTE_TERMINAL_MCP_HOME; else process.env.REMOTE_TERMINAL_MCP_HOME = previous;
    await rm(home, { recursive: true, force: true });
  }
}

test('command line accepts exactly one mode and rejects mixed or incomplete forms', () => {
  assert.equal(parseArgs(['--config', '/abs/targets.json']).command, 'serve');
  assert.deepEqual(parseArgs(['--server', 'https://x.example', '--project', 'ENG']).project, 'ENG');
  assert.equal(parseArgs(['login', '--server', 'https://x.example']).command, 'login');
  assert.equal(parseArgs(['logout', '--server', 'https://x.example']).command, 'logout');
  const accept = parseArgs(['pins', 'accept', '--server', 'https://x.example', '--project', 'ENG', 'prod']);
  assert.equal(accept.command, 'pins-accept'); assert.equal(accept.targetId, 'prod');
  for (const bad of [[], ['--config', 'relative.json'], ['--config', '/a.json', '--server', 'https://x.example', '--project', 'E'],
    ['--server', 'https://x.example'], ['login'], ['login', '--server', 'https://x.example', '--project', 'E'],
    ['pins', 'accept', '--server', 'https://x.example', '--project', 'E'], ['--bogus']]) {
    assert.throws(() => parseArgs(bad), /Invalid command line/, JSON.stringify(bad));
  }
});

test('server URLs must be https, or loopback http, and origin only', () => {
  assert.equal(normalizeServer('https://linear.example.com'), 'https://linear.example.com');
  assert.equal(normalizeServer('http://127.0.0.1:9080'), 'http://127.0.0.1:9080');
  for (const bad of ['http://linear.example.com', 'https://user:pw@linear.example.com', 'https://linear.example.com/path', 'https://linear.example.com/?q=1', 'ftp://x', 'nope']) {
    assert.throws(() => normalizeServer(bad), /Invalid server URL/, bad);
  }
});

test('host key pins: first use is new, same key is same, a different key is changed, comments are ignored', async () => withHome(async home => {
  assert.deepEqual(await checkPin('https://s', 'eng', 'prod', HOST_KEY), { result: 'new' });
  await recordPin('https://s', 'eng', 'prod', HOST_KEY);
  assert.deepEqual(await checkPin('https://s', 'ENG', 'prod', HOST_KEY.split(' ').slice(0, 2).join(' ')), { result: 'same' });
  const changed = await checkPin('https://s', 'ENG', 'prod', OTHER_KEY);
  assert.equal(changed.result, 'changed');
  assert.match(fingerprint(OTHER_KEY), /^SHA256:[A-Za-z0-9+/]+$/);
  assert.deepEqual(await checkPin('https://s', 'ENG', 'other', OTHER_KEY), { result: 'new' });
  assert.equal(((await stat(join(home, 'pins.json'))).mode & 0o777), 0o600);
}));

test('a corrupt pin file blocks connections instead of resetting trust', async () => withHome(async home => {
  await writeFile(join(home, 'pins.json'), '{not json');
  await assert.rejects(checkPin('https://s', 'ENG', 'prod', HOST_KEY), (error: Error) => error instanceof ControlledError && /unreadable/.test(error.message));
}));

type Stub = { origin: string; close(): Promise<void>; tokenRequests: Array<Record<string, string>>; registrations: unknown[]; authorizeQuery?: URLSearchParams };
async function stubServer(options: { refreshFails?: boolean } = {}): Promise<Stub> {
  const stub = { tokenRequests: [], registrations: [] } as unknown as Stub;
  let origin = '';
  let counter = 0;
  const readBody = (request: IncomingMessage) => new Promise<string>(resolve => { let text = ''; request.on('data', c => { text += c; }); request.on('end', () => resolve(text)); });
  const server: Server = createServer(async (request, response) => {
    const url = new URL(request.url ?? '/', origin);
    const json = (status: number, body: unknown) => { response.writeHead(status, { 'content-type': 'application/json' }); response.end(JSON.stringify(body)); };
    if (url.pathname === '/.well-known/oauth-protected-resource/mcp') return json(200, { resource: `${origin}/mcp`, authorization_servers: [origin] });
    if (url.pathname === '/.well-known/oauth-authorization-server') return json(200, { issuer: origin, authorization_endpoint: `${origin}/oauth2/authorize`, token_endpoint: `${origin}/oauth2/token`, registration_endpoint: `${origin}/oauth2/register` });
    if (url.pathname === '/oauth2/register') { stub.registrations.push(JSON.parse(await readBody(request))); return json(201, { client_id: 'client-1' }); }
    if (url.pathname === '/oauth2/authorize') {
      stub.authorizeQuery = url.searchParams;
      const redirect = new URL(url.searchParams.get('redirect_uri')!);
      redirect.searchParams.set('code', 'the-code'); redirect.searchParams.set('state', url.searchParams.get('state')!);
      response.writeHead(302, { location: redirect.href }); return void response.end();
    }
    if (url.pathname === '/oauth2/token') {
      const form = Object.fromEntries(new URLSearchParams(await readBody(request)));
      stub.tokenRequests.push(form);
      if (form.grant_type === 'refresh_token' && options.refreshFails) return json(400, { error: 'invalid_grant' });
      counter++;
      return json(200, { access_token: `access-${counter}`, refresh_token: `refresh-${counter}`, expires_in: 3600, scope: 'remote-terminal' });
    }
    json(404, {});
  });
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve));
  origin = `http://127.0.0.1:${(server.address() as { port: number }).port}`;
  stub.origin = origin;
  stub.close = () => new Promise<void>(resolve => { server.close(() => resolve()); server.closeAllConnections(); });
  return stub;
}

test('login runs authorization code with PKCE against loopback and stores tokens privately', async () => withHome(async home => {
  const stub = await stubServer();
  try {
    await login(stub.origin, { print: () => {}, openBrowser: async url => { await fetch(url, { redirect: 'follow' }); } });
    const query = stub.authorizeQuery!;
    assert.equal(query.get('scope'), 'remote-terminal'); assert.equal(query.get('code_challenge_method'), 'S256');
    assert.equal(query.get('resource'), `${stub.origin}/mcp`);
    assert.match(query.get('redirect_uri')!, /^http:\/\/127\.0\.0\.1:\d+\/callback$/);
    const exchange = stub.tokenRequests[0]!;
    assert.equal(createHash('sha256').update(exchange.code_verifier!).digest('base64url'), query.get('code_challenge'));
    assert.equal(exchange.code, 'the-code'); assert.equal(exchange.client_id, 'client-1');
    assert.equal(await accessToken(stub.origin), 'access-1');
    assert.equal(((await stat(join(home, 'credentials.json'))).mode & 0o777), 0o600);
    await logout(stub.origin);
    await assert.rejects(accessToken(stub.origin), /Not signed in/);
  } finally { await stub.close(); }
}));

test('login refuses an authorization server on another origin', async () => withHome(async () => {
  const stub = await stubServer();
  const evil = createServer((_request, response) => { response.writeHead(200, { 'content-type': 'application/json' }); response.end(JSON.stringify({ resource: 'https://a.example/mcp', authorization_servers: ['https://evil.example'] })); });
  await new Promise<void>(resolve => evil.listen(0, '127.0.0.1', resolve));
  try {
    await assert.rejects(login(`http://127.0.0.1:${(evil.address() as { port: number }).port}`, { print: () => {}, openBrowser: () => {} }), /differs from the configured server/);
  } finally { evil.close(); await stub.close(); }
}));

test('expired access tokens refresh and rotate; a rejected refresh asks the user to sign in again', async () => withHome(async home => {
  const stub = await stubServer();
  try {
    await login(stub.origin, { print: () => {}, openBrowser: async url => { await fetch(url); } });
    const file = join(home, 'credentials.json');
    const store = JSON.parse(await readFile(file, 'utf8'));
    store.servers[stub.origin].expires_at = Date.now() - 1000;
    await writeFile(file, JSON.stringify(store));
    assert.equal(await accessToken(stub.origin), 'access-2');
    assert.equal(stub.tokenRequests.at(-1)!.grant_type, 'refresh_token'); assert.equal(stub.tokenRequests.at(-1)!.refresh_token, 'refresh-1');
    assert.equal(JSON.parse(await readFile(file, 'utf8')).servers[stub.origin].refresh_token, 'refresh-2');
  } finally { await stub.close(); }
  const failing = await stubServer({ refreshFails: true });
  try {
    await login(failing.origin, { print: () => {}, openBrowser: async url => { await fetch(url); } });
    const file = join(home, 'credentials.json');
    const store = JSON.parse(await readFile(file, 'utf8'));
    store.servers[failing.origin].expires_at = Date.now() - 1000;
    await writeFile(file, JSON.stringify(store));
    await assert.rejects(accessToken(failing.origin), /Not signed in/);
  } finally { await failing.close(); }
}));

// Exactly what the server sends: camelCase, wrapped in ApiResponse.
const catalogBody = (hostKey = HOST_KEY, authType: 'CERTIFICATE' | 'PASSWORD' = 'CERTIFICATE') => ({ code: 200, data: { project: 'ENG', targets: [{ targetId: 'prod', alias: 'Prod', environment: 'prod', host: '10.0.0.5', port: 22, hostKey, hostKeyRev: 1,
  profiles: [{ profileId: 'deploy', loginUser: 'deploy', maxTtlSeconds: 600, authType }] }] } });

function cloud(handler: (path: string, init: RequestInit) => unknown | Promise<unknown>) {
  const calls: Array<{ path: string; init: RequestInit }> = [];
  const source = new CloudSource({ server: 'https://linear.example.com', project: 'eng', token: async (_s, force) => (force ? 'fresh' : 'stale'),
    fetchJson: async (url, init = {}) => { const path = url.replace('https://linear.example.com', ''); calls.push({ path, init }); return handler(path, init); } });
  return { source, calls };
}

test('cloud source lists the granted catalogue and hides host_key_rev', async () => withHome(async () => {
  const { source, calls } = cloud(() => catalogBody());
  const targets = await source.list();
  assert.equal(targets[0]!.target_id, 'prod'); assert.ok(!('host_key_rev' in targets[0]!));
  assert.equal(calls[0]!.path, '/api/remote-terminal/catalog?project=ENG');
  assert.equal((calls[0]!.init.headers as Record<string, string>).authorization, 'Bearer stale');
}));

test('a rejected access token is refreshed once and retried', async () => withHome(async () => {
  let attempts = 0;
  const { source, calls } = cloud(() => { if (attempts++ === 0) throw new HttpError(401); return catalogBody(); });
  assert.equal((await source.list()).length, 1);
  assert.equal(calls.length, 2); assert.equal((calls[1]!.init.headers as Record<string, string>).authorization, 'Bearer fresh');
}));

test('listing falls back to the cache only when the cloud is unreachable, never after a revocation', async () => withHome(async () => {
  let mode: 'ok' | 'down' | 'forbidden' = 'ok';
  const { source } = cloud(() => { if (mode === 'down') throw new TypeError('fetch failed'); if (mode === 'forbidden') throw new HttpError(403); return catalogBody(); });
  assert.equal((await source.list()).length, 1);
  mode = 'down'; assert.equal((await source.list()).length, 1);
  mode = 'forbidden'; await assert.rejects(source.list(), /Not authorized for this target/);
}));

test('connecting issues a certificate for an in-memory key, pins on first use, and never trusts the cache', async () => withHome(async () => {
  let certificateBody: Record<string, unknown> | undefined;
  const { source } = cloud((path, init) => {
    if (path.startsWith('/api/remote-terminal/certificates')) { certificateBody = JSON.parse(String(init.body)); return { code: 200, data: { certificate: 'ssh-ed25519-cert-v01@openssh.com AAAA', serial: 5 } }; }
    return catalogBody();
  });
  const info = await source.connectInfo('prod', 'deploy', 300, new AbortController().signal);
  assert.equal(certificateBody!.targetId, 'prod'); assert.equal(certificateBody!.profileId, 'deploy'); assert.equal(certificateBody!.ttlSeconds, 300);
  assert.match(String(certificateBody!.publicKey), /^ssh-ed25519 /);
  assert.ok(info.profile.ephemeral && Buffer.isBuffer(info.profile.ephemeral.private_key));
  assert.equal(info.target.host, '10.0.0.5');
  assert.deepEqual(await checkPin('https://linear.example.com', 'ENG', 'prod', HOST_KEY), { result: 'new' });
  await info.afterConnect!();
  assert.deepEqual(await checkPin('https://linear.example.com', 'ENG', 'prod', HOST_KEY), { result: 'same' });
  await assert.rejects(source.connectInfo('prod', 'deploy', 601, new AbortController().signal), /lifetime exceeds/);
  await assert.rejects(source.connectInfo('ghost', 'deploy', 10, new AbortController().signal), /identity is unavailable/);
}));

test('a changed host key blocks the connection before any certificate is requested', async () => withHome(async () => {
  await recordPin('https://linear.example.com', 'ENG', 'prod', HOST_KEY);
  let issued = 0;
  const { source } = cloud(path => { if (path.startsWith('/api/remote-terminal/certificates')) issued++; return path.startsWith('/api/remote-terminal/catalog') ? catalogBody(OTHER_KEY) : { code: 200, data: { certificate: 'x', serial: 1 } }; });
  await assert.rejects(source.connectInfo('prod', 'deploy', 60, new AbortController().signal), (error: Error) =>
    error instanceof ControlledError && /changed/.test(error.message) && /pins accept/.test(error.message));
  assert.equal(issued, 0);
}));

test('password identity retrieves the secret only for a live, pinned target and never caches it', async () => withHome(async home => {
  const secret = 'test-password-do-not-persist';
  let request: Record<string, unknown> | undefined;
  const { source, calls } = cloud((path, init) => {
    if (path === '/api/remote-terminal/passwords') {
      request = JSON.parse(String(init.body));
      return { code: 200, data: { password: secret, permitPty: false } };
    }
    return catalogBody(HOST_KEY, 'PASSWORD');
  });
  await source.list();
  const info = await source.connectInfo('prod', 'deploy', 300, new AbortController().signal);
  assert.deepEqual(request, { project: 'ENG', targetId: 'prod', profileId: 'deploy', ttlSeconds: 300, expectedHostKeyRev: 1 });
  assert.equal(info.profile.password, secret);
  assert.equal(info.profile.permit_pty, false);
  assert.ok(!calls.some(call => call.path === '/api/remote-terminal/certificates'));
  const cache = await readFile(join(home, 'catalog', 'linear.example.com__ENG.json'), 'utf8');
  assert.ok(!cache.includes(secret));
}));

test('password retrieval rejects revoked access and changed host keys without trying a certificate', async () => withHome(async () => {
  let refused = false;
  const { source, calls } = cloud((path) => {
    if (path === '/api/remote-terminal/passwords') { refused = true; throw new HttpError(403); }
    return catalogBody(HOST_KEY, 'PASSWORD');
  });
  await assert.rejects(source.connectInfo('prod', 'deploy', 60, new AbortController().signal), /Not authorized/);
  assert.equal(refused, true);
  assert.ok(!calls.some(call => call.path === '/api/remote-terminal/certificates'));
  await recordPin('https://linear.example.com', 'ENG', 'prod', OTHER_KEY);
  refused = false;
  await assert.rejects(source.connectInfo('prod', 'deploy', 60, new AbortController().signal), /Host key.*changed/);
  assert.equal(refused, false);
}));
