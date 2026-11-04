import { createHash, randomBytes } from 'node:crypto';
import { spawn } from 'node:child_process';
import { createServer } from 'node:http';
import { join } from 'node:path';
import { z } from 'zod';
import { CloudUnavailableError, ControlledError, notSignedIn } from './errors.js';
import { HttpError, requestJson } from './http.js';
import { readJson, stateDir, writeJsonPrivate } from './state.js';

export const SCOPE = 'remote-terminal';
const LOGIN_TIMEOUT_MS = 5 * 60_000;
const REFRESH_MARGIN_MS = 60_000;

export type ServerState = {
  client_id?: string; resource?: string; token_endpoint?: string;
  access_token?: string; refresh_token?: string; expires_at?: number;
};
type Store = { servers: Record<string, ServerState> };

const credentialsPath = () => join(stateDir(), 'credentials.json');

async function loadStore(): Promise<Store> {
  try {
    const stored = await readJson<Store>(credentialsPath());
    return stored && typeof stored.servers === 'object' && stored.servers !== null ? stored : { servers: {} };
  } catch { return { servers: {} }; }
}

async function saveState(server: string, state: ServerState): Promise<void> {
  const store = await loadStore();
  store.servers[server] = state;
  await writeJsonPrivate(credentialsPath(), store);
}

/** Only https, or http on a loopback host for local development; origin only. */
export function normalizeServer(value: string): string {
  let url: URL;
  try { url = new URL(value); } catch { throw new Error('Invalid server URL'); }
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname);
  const secure = url.protocol === 'https:' || (url.protocol === 'http:' && loopback);
  if (!secure || url.username || url.password || url.pathname !== '/' || url.search || url.hash) throw new Error('Invalid server URL');
  return url.origin;
}

const resourceMetadata = z.object({ resource: z.string().url(), authorization_servers: z.array(z.string().url()).min(1) }).passthrough();
const serverMetadata = z.object({
  authorization_endpoint: z.string().url(), token_endpoint: z.string().url(), registration_endpoint: z.string().url(),
}).passthrough();
const tokenResponse = z.object({
  access_token: z.string().min(1), refresh_token: z.string().min(1).optional(),
  expires_in: z.number().positive(), scope: z.string().optional(),
}).passthrough();

type Discovery = { resource: string; authorization_endpoint: string; token_endpoint: string; registration_endpoint: string };

async function discover(server: string): Promise<Discovery> {
  const protectedResource = resourceMetadata.parse(await requestJson(`${server}/.well-known/oauth-protected-resource/mcp`));
  const issuer = new URL(protectedResource.authorization_servers[0]!).origin;
  // Tokens and the authorisation code only ever travel to the server the user typed.
  if (issuer !== server) throw new Error('Authorization server differs from the configured server');
  const metadata = serverMetadata.parse(await requestJson(`${issuer}/.well-known/oauth-authorization-server`));
  for (const endpoint of [metadata.authorization_endpoint, metadata.token_endpoint, metadata.registration_endpoint]) {
    if (new URL(endpoint).origin !== server) throw new Error('Authorization endpoint differs from the configured server');
  }
  return { resource: protectedResource.resource, ...metadata };
}

async function tokenRequest(endpoint: string, form: Record<string, string>) {
  const body = new URLSearchParams(form).toString();
  const parsed = tokenResponse.parse(await requestJson(endpoint, {
    method: 'POST', body, headers: { 'content-type': 'application/x-www-form-urlencoded' },
  }));
  if (parsed.scope !== undefined && !parsed.scope.split(/\s+/).includes(SCOPE)) throw new Error('Token lacks the remote-terminal scope');
  return parsed;
}

function toState(base: ServerState, token: z.infer<typeof tokenResponse>): ServerState {
  return { ...base, access_token: token.access_token, refresh_token: token.refresh_token ?? base.refresh_token,
    expires_at: Date.now() + token.expires_in * 1000 };
}

export type LoginIo = { openBrowser(url: string): Promise<void> | void; print(message: string): void };

export const defaultLoginIo: LoginIo = {
  print: message => process.stdout.write(`${message}\n`),
  openBrowser: url => {
    const [command, args]: [string, string[]] = process.platform === 'darwin' ? ['open', [url]]
      : process.platform === 'win32' ? ['cmd', ['/c', 'start', '', url]] : ['xdg-open', [url]];
    try { spawn(command, args, { stdio: 'ignore', detached: true }).on('error', () => {}).unref(); } catch { /* the URL is printed as well */ }
  },
};

function listenForCallback(expectedState: string): Promise<{ port: number; code: Promise<string>; close(): void }> {
  return new Promise((resolveListen, rejectListen) => {
    let resolveCode!: (code: string) => void; let rejectCode!: (error: Error) => void;
    const code = new Promise<string>((resolve, reject) => { resolveCode = resolve; rejectCode = reject; });
    code.catch(() => {});
    const server = createServer((request, response) => {
      const url = new URL(request.url ?? '/', 'http://127.0.0.1');
      if (url.pathname !== '/callback') { response.writeHead(404).end(); return; }
      const failed = url.searchParams.get('error') !== null || url.searchParams.get('state') !== expectedState || !url.searchParams.get('code');
      response.writeHead(failed ? 400 : 200, { 'content-type': 'text/plain; charset=utf-8' });
      response.end(failed ? 'Authorization failed. You can close this window.' : 'Signed in. You can close this window.');
      if (failed) rejectCode(new Error('Authorization was denied or failed')); else resolveCode(url.searchParams.get('code')!);
    });
    const timer = setTimeout(() => rejectCode(new Error('Timed out waiting for authorization')), LOGIN_TIMEOUT_MS);
    timer.unref();
    server.once('error', rejectListen);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (!address || typeof address === 'string') return rejectListen(new Error('Could not listen for the callback'));
      resolveListen({ port: address.port, code, close: () => { clearTimeout(timer); server.close(); server.closeAllConnections(); } });
    });
  });
}

/** Authorization code + PKCE with a loopback redirect, as supported by the server's public-client registration. */
export async function login(serverInput: string, io: LoginIo = defaultLoginIo): Promise<void> {
  const server = normalizeServer(serverInput);
  const meta = await discover(server);
  const verifier = randomBytes(32).toString('base64url');
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  const state = randomBytes(16).toString('base64url');
  const callback = await listenForCallback(state);
  try {
    const redirectUri = `http://127.0.0.1:${callback.port}/callback`;
    const registration = z.object({ client_id: z.string().min(1) }).passthrough().parse(await requestJson(meta.registration_endpoint, {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ client_name: 'remote-terminal-mcp', redirect_uris: [redirectUri],
        grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'],
        token_endpoint_auth_method: 'none', scope: SCOPE }),
    }));
    const authorize = new URL(meta.authorization_endpoint);
    for (const [key, value] of Object.entries({ response_type: 'code', client_id: registration.client_id, redirect_uri: redirectUri,
      scope: SCOPE, state, code_challenge: challenge, code_challenge_method: 'S256', resource: meta.resource })) authorize.searchParams.set(key, value);
    io.print(`Open this URL to sign in if a browser does not open:\n${authorize.href}`);
    await io.openBrowser(authorize.href);
    const code = await callback.code;
    const token = await tokenRequest(meta.token_endpoint, { grant_type: 'authorization_code', code, redirect_uri: redirectUri,
      client_id: registration.client_id, code_verifier: verifier, resource: meta.resource });
    await saveState(server, toState({ client_id: registration.client_id, resource: meta.resource, token_endpoint: meta.token_endpoint }, token));
    io.print(`Signed in to ${server}`);
  } finally { callback.close(); }
}

export async function logout(serverInput: string): Promise<void> {
  const server = normalizeServer(serverInput);
  const store = await loadStore();
  delete store.servers[server];
  await writeJsonPrivate(credentialsPath(), store);
}

export async function accessToken(serverInput: string, forceRefresh = false): Promise<string> {
  const server = normalizeServer(serverInput);
  const state = (await loadStore()).servers[server];
  if (!state?.access_token) throw notSignedIn(server);
  if (!forceRefresh && (state.expires_at ?? 0) - Date.now() > REFRESH_MARGIN_MS) return state.access_token;
  return refresh(server, state);
}

async function refresh(server: string, state: ServerState): Promise<string> {
  if (!state.refresh_token || !state.client_id || !state.token_endpoint || !state.resource) throw notSignedIn(server);
  try {
    const token = await tokenRequest(state.token_endpoint, { grant_type: 'refresh_token', refresh_token: state.refresh_token,
      client_id: state.client_id, resource: state.resource });
    await saveState(server, toState(state, token));
    return token.access_token;
  } catch (error) {
    // Refresh tokens rotate; a sibling MCP process may have won the race and stored a newer pair.
    const latest = (await loadStore()).servers[server];
    if (latest?.access_token && latest.access_token !== state.access_token && (latest.expires_at ?? 0) > Date.now()) return latest.access_token;
    if (error instanceof HttpError && (error.status === 400 || error.status === 401)) throw notSignedIn(server);
    if (error instanceof ControlledError) throw error;
    throw new CloudUnavailableError();
  }
}
