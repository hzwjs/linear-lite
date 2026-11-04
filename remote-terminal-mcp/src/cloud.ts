import { join } from 'node:path';
import { z } from 'zod';
import { generateEphemeralKey } from './ephemeral.js';
import { CloudUnavailableError, ControlledError } from './errors.js';
import { HttpError, requestJson } from './http.js';
import { accessToken, normalizeServer } from './oauth.js';
import { checkPin, fingerprint, recordPin } from './pins.js';
import { readJson, stateDir, writeJsonPrivate } from './state.js';
import type { ConnectInfo, ListedTarget, TargetSource } from './transport.js';

const CATALOG_CACHE_MAX_AGE_MS = 24 * 60 * 60 * 1000;

// The server speaks camelCase JSON; the MCP's own types are snake_case, so the wire shape is mapped once here.
const wireCatalog = z.object({
  project: z.string(),
  targets: z.array(z.object({
    targetId: z.string(), alias: z.string(), environment: z.string(),
    host: z.string(), port: z.number().int(), hostKey: z.string(), hostKeyRev: z.number().int(),
    profiles: z.array(z.object({ profileId: z.string(), loginUser: z.string(), maxTtlSeconds: z.number().int() }).strict()),
  }).strict()),
}).strict();
const catalogSchema = wireCatalog.transform(wire => ({
  project: wire.project,
  targets: wire.targets.map(t => ({
    target_id: t.targetId, alias: t.alias, environment: t.environment, host: t.host, port: t.port,
    host_key: t.hostKey, host_key_rev: t.hostKeyRev,
    profiles: t.profiles.map(p => ({ profile_id: p.profileId, login_user: p.loginUser, max_ttl_seconds: p.maxTtlSeconds })),
  })),
}));
type Catalog = z.output<typeof catalogSchema>;
const envelope = <T extends z.ZodTypeAny>(data: T) => z.object({ code: z.number(), message: z.string().nullish(), data }).passthrough();
const certificateSchema = z.object({ certificate: z.string().min(1), serial: z.number() }).passthrough();

function toWire(catalog: Catalog) {
  return { project: catalog.project, targets: catalog.targets.map(t => ({
    targetId: t.target_id, alias: t.alias, environment: t.environment, host: t.host, port: t.port,
    hostKey: t.host_key, hostKeyRev: t.host_key_rev,
    profiles: t.profiles.map(p => ({ profileId: p.profile_id, loginUser: p.login_user, maxTtlSeconds: p.max_ttl_seconds })),
  })) };
}

type Fetch = typeof requestJson;
export type CloudOptions = { server: string; project: string; fetchJson?: Fetch; token?: (server: string, force?: boolean) => Promise<string> };

/** Team mode: the cloud owns the catalogue and issues short-lived certificates; SSH still goes straight to the server. */
export class CloudSource implements TargetSource {
  private readonly server: string;
  private readonly project: string;
  private readonly fetchJson: Fetch;
  private readonly token: (server: string, force?: boolean) => Promise<string>;

  constructor(options: CloudOptions) {
    this.server = normalizeServer(options.server);
    this.project = options.project.trim().toUpperCase();
    this.fetchJson = options.fetchJson ?? requestJson;
    this.token = options.token ?? accessToken;
  }

  private cachePath() { return join(stateDir(), 'catalog', `${new URL(this.server).host}__${this.project}.json`); }

  /** Authenticated call; one refresh-and-retry when the access token is rejected. */
  private async call(path: string, init: RequestInit = {}): Promise<unknown> {
    for (let attempt = 0; ; attempt++) {
      const token = await this.token(this.server, attempt > 0);
      try {
        return await this.fetchJson(`${this.server}${path}`, { ...init, headers: { ...init.headers, authorization: `Bearer ${token}` } });
      } catch (error) {
        if (error instanceof HttpError && error.status === 401 && attempt === 0) continue;
        throw this.translate(error);
      }
    }
  }

  private translate(error: unknown): Error {
    if (error instanceof ControlledError) return error;
    if (error instanceof HttpError) {
      if (error.status === 401) return new ControlledError(`Not signed in; run: remote-terminal-mcp login --server ${this.server}`);
      if (error.status === 403) return new ControlledError('Not authorized for this target');
      if (error.status === 404) return new ControlledError('Project not found or not accessible');
      if (error.status === 429) return new ControlledError('Too many certificate requests; try again shortly');
      if (error.status === 400 || error.status === 422) return new ControlledError('The cloud service rejected the request');
    }
    return new CloudUnavailableError();
  }

  private async catalog(): Promise<Catalog> {
    try {
      const body = envelope(catalogSchema).parse(await this.call(`/api/remote-terminal/catalog?project=${encodeURIComponent(this.project)}`));
      await writeJsonPrivate(this.cachePath(), { fetched_at: Date.now(), catalog: toWire(body.data) }).catch(() => {});
      return body.data;
    } catch (error) {
      // Only an unreachable cloud falls back to the cache, and the cache is for listing only; a revoked
      // grant or a signed-out user must not be answered with stale data.
      if (!(error instanceof CloudUnavailableError || error instanceof z.ZodError)) throw error;
      const cached = await readJson<{ fetched_at: number; catalog: unknown }>(this.cachePath()).catch(() => undefined);
      const parsed = catalogSchema.safeParse(cached?.catalog);
      if (parsed.success && cached && Date.now() - cached.fetched_at < CATALOG_CACHE_MAX_AGE_MS) return parsed.data;
      throw new CloudUnavailableError();
    }
  }

  async list(): Promise<ListedTarget[]> {
    return (await this.catalog()).targets.map(({ host_key_rev: _rev, ...target }) => target);
  }

  async connectInfo(targetId: string, profileId: string, ttlSeconds: number, signal: AbortSignal): Promise<ConnectInfo> {
    // Always read the live catalogue here: the cache must never authorise a connection.
    const live = envelope(catalogSchema).parse(await this.call(`/api/remote-terminal/catalog?project=${encodeURIComponent(this.project)}`)).data;
    const target = live.targets.find(item => item.target_id === targetId);
    const profile = target?.profiles.find(item => item.profile_id === profileId);
    if (!target || !profile) throw new Error('Configured server identity is unavailable');
    if (ttlSeconds > profile.max_ttl_seconds) throw new Error('Connection lifetime exceeds the configured identity limit');

    const pin = await checkPin(this.server, this.project, targetId, target.host_key);
    if (pin.result === 'changed') {
      throw new ControlledError(`Host key for ${targetId} changed (now ${fingerprint(target.host_key)}, pinned ${fingerprint(pin.pinned)}); `
        + `verify it with the project owner, then run: remote-terminal-mcp pins accept --server ${this.server} --project ${this.project} ${targetId}`);
    }
    signal.throwIfAborted();

    const key = generateEphemeralKey();
    const issued = envelope(certificateSchema).parse(await this.call('/api/remote-terminal/certificates', {
      method: 'POST', headers: { 'content-type': 'application/json' },
      body: JSON.stringify({ project: this.project, targetId, profileId, publicKey: key.publicKeyLine, ttlSeconds }),
    })).data;
    return {
      target: { target_id: target.target_id, alias: target.alias, environment: target.environment, host: target.host,
        port: target.port, host_key: target.host_key, profiles: [] },
      profile: { profile_id: profile.profile_id, login_user: profile.login_user, max_ttl_seconds: profile.max_ttl_seconds,
        ephemeral: { private_key: key.privateKey, certificate: issued.certificate } },
      // Trust on first use is recorded only after a successful login to that very key.
      afterConnect: pin.result === 'new' ? () => recordPin(this.server, this.project, targetId, target.host_key) : undefined,
    };
  }
}
