import { readFile } from 'node:fs/promises';
import { stat } from 'node:fs/promises';
import { Socket } from 'node:net';
import type { Readable } from 'node:stream';
import ssh2 from 'ssh2';
const { Client, OpenSSHAgent, utils } = ssh2;
import type { AuthMethod, Client as Ssh2Client, ClientChannel, ConnectConfig, GetStreamCallback, IdentityCallback, ParsedKey, PublicKeyAuthMethod, ServerHostKeyAlgorithm, SFTPWrapper, SignCallback, SigningRequestOptions } from 'ssh2';
import type { RemoteChannel, RemoteConnection, RemoteTransport, Target, Profile, TerminalOptions } from './transport.js';

const NETWORK_TIMEOUT_MS = 10_000;
const ERR_CONNECT = 'SSH connection failed';
const ERR_AUTH = 'SSH authentication failed';
const ERR_HOST_KEY = 'SSH host key verification failed';
const ERR_CHANNEL = 'SSH channel request failed';
const ERR_SFTP = 'SFTP request failed';
const ERR_CLOSED = 'SSH connection is closed';

function parsePublicKey(value: string): ParsedKey {
  const parsed = utils.parseKey(value.trim());
  if (parsed instanceof Error || Array.isArray(parsed) || parsed.isPrivateKey()) throw new Error('Invalid public key');
  return parsed;
}

async function publicKeyFor(path: string, signal?: AbortSignal): Promise<ParsedKey> {
  const details = await stat(path);
  if (!details.isFile() || details.size > 16 * 1024) throw new Error('Invalid public key file');
  return parsePublicKey(await readFile(path, { encoding: 'utf8', signal }));
}

async function privateKeyFor(path: string, signal?: AbortSignal): Promise<Buffer> {
  const details = await stat(path);
  if (!details.isFile() || details.size > 16 * 1024) throw new Error('Invalid private key file');
  const contents = await readFile(path, { signal });
  const parsed = utils.parseKey(contents);
  if (parsed instanceof Error || Array.isArray(parsed) || !parsed.isPrivateKey()) throw new Error('Invalid private key');
  return contents;
}

// @types/ssh2 types authHandler arrays as bare AuthMethod; ssh2 accepts full methods.
function certificateAuth(username: string, key: ParsedKey): AuthMethod[] {
  const method: PublicKeyAuthMethod = { type: 'publickey', username, key };
  return [method];
}

const CERT_SUFFIX = /-cert-v01@openssh\.com$/;
// Fields after the nonce that make up the certified key, per PROTOCOL.certkeys.
const CERT_KEY_FIELDS: Record<string, number> = { 'ssh-ed25519': 1, 'ecdsa-sha2-nistp256': 2, 'ecdsa-sha2-nistp384': 2, 'ecdsa-sha2-nistp521': 2 };

function sshStrings(blob: Buffer, count: number): Buffer[] {
  const out: Buffer[] = [];
  let offset = 0;
  for (let i = 0; i < count; i++) {
    if (offset + 4 > blob.length) throw new Error('Invalid certificate');
    const length = blob.readUInt32BE(offset);
    if (offset + 4 + length > blob.length) throw new Error('Invalid certificate');
    out.push(blob.subarray(offset + 4, offset + 4 + length));
    offset += 4 + length;
  }
  return out;
}

function sshString(value: Buffer): Buffer {
  const length = Buffer.alloc(4);
  length.writeUInt32BE(value.length);
  return Buffer.concat([length, value]);
}

// ssh2 misreads certificate lines (it takes the nonce as the key), so the
// certified key is decoded here. The returned key presents the certificate on
// the wire and signs with the private key it certifies.
async function certificateKeyFor(path: string, privateKey: Buffer, signal?: AbortSignal): Promise<ParsedKey> {
  const details = await stat(path);
  if (!details.isFile() || details.size > 16 * 1024) throw new Error('Invalid certificate file');
  const [type = '', encoded = ''] = (await readFile(path, { encoding: 'utf8', signal })).trim().split(/\s+/);
  const key = utils.parseKey(privateKey);
  if (key instanceof Error || Array.isArray(key)) throw new Error('Invalid private key');
  const baseType = type.replace(CERT_SUFFIX, '');
  // RSA certificates are excluded: ssh2 would sign them with SHA-1, which OpenSSH rejects.
  const fields = CERT_KEY_FIELDS[baseType];
  if (!CERT_SUFFIX.test(type) || fields === undefined || baseType !== key.type) throw new Error('Invalid certificate');
  const wire = Buffer.from(encoded, 'base64');
  const [wireType, , ...keyFields] = sshStrings(wire, 2 + fields);
  if (wireType?.toString('latin1') !== type) throw new Error('Invalid certificate');
  const certified = Buffer.concat([sshString(Buffer.from(baseType, 'latin1')), ...keyFields.map(sshString)]);
  if (!certified.equals(key.getPublicSSH())) throw new Error('Certificate does not match private key');
  return Object.create(key, { type: { value: type }, getPublicSSH: { value: () => wire } }) as ParsedKey;
}

class BoundedAgent extends OpenSSHAgent {
  private readonly sockets = new Set<Socket>();
  private stopped = false;
  private readonly configuredSocket: string;
  constructor(socketPath: string, private readonly allowed: ParsedKey) {
    super(socketPath);
    this.configuredSocket = socketPath;
  }

  override getStream(cb: GetStreamCallback): void {
    if (this.stopped) return cb(new Error('Agent closed'));
    const socket = new Socket();
    this.sockets.add(socket);
    let settled = false;
    const timer = setTimeout(() => finish(new Error('Agent timeout')), NETWORK_TIMEOUT_MS);
    const finish = (err?: Error) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      if (err) {
        socket.destroy();
        cb(err);
        return;
      }
      if (this.stopped) {
        socket.destroy();
        cb(new Error('Agent closed'));
        return;
      }
      cb(undefined, socket);
    };
    socket.once('close', () => {
      this.sockets.delete(socket);
      if (!settled) finish(new Error('Agent connection failed'));
    });
    socket.once('connect', () => finish());
    socket.once('error', () => { this.sockets.delete(socket); finish(new Error('Agent connection failed')); });
    socket.connect(this.configuredSocket);
  }

  override getIdentities(cb: IdentityCallback<ParsedKey>): void {
    super.getIdentities((err, keys) => {
      if (err) return cb(new Error('Agent identity request failed'));
      const matching = (keys ?? []).filter((key): key is ParsedKey => isParsedKey(key) && key.getPublicSSH().equals(this.allowed.getPublicSSH()));
      cb(undefined, matching.length ? [matching[0]!] : []);
    });
  }

  override sign(pubKey: ParsedKey | Buffer | string, data: Buffer, options?: SigningRequestOptions | SignCallback, callback?: SignCallback): boolean {
    const cb = typeof options === 'function' ? options : callback;
    if (!cb) return false;
    let parsed: ParsedKey;
    try {
      const result = utils.parseKey(pubKey);
      if (result instanceof Error || Array.isArray(result) || result.isPrivateKey() || !result.getPublicSSH().equals(this.allowed.getPublicSSH())) {
        cb(new Error('Agent identity is not permitted'));
        return false;
      }
      parsed = result;
    } catch {
      cb(new Error('Agent identity is not permitted'));
      return false;
    }
    const finish: SignCallback = (err, signature) => cb(err ? new Error('Agent signing failed') : undefined, signature);
    return typeof options === 'function' ? super.sign(parsed, data, finish) : super.sign(parsed, data, options, finish);
  }

  stop(): void {
    this.stopped = true;
    for (const socket of this.sockets) socket.destroy();
    this.sockets.clear();
  }
}

function isParsedKey(value: unknown): value is ParsedKey {
  return Boolean(value && typeof value === 'object' && 'getPublicSSH' in value && typeof (value as ParsedKey).getPublicSSH === 'function');
}

function networkRequest<T>(start: (done: (error: Error | undefined, value?: T) => void) => void, onTimeout: () => void, message: string, onLate?: (value: T) => void): Promise<T> {
  return new Promise((resolve, reject) => {
    let settled = false;
    const timer = setTimeout(() => {
      if (settled) return;
      settled = true;
      onTimeout();
      reject(new Error(message));
    }, NETWORK_TIMEOUT_MS);
    try {
      start((error, value) => {
        if (settled) {
          if (!error) {
            onLate?.(value as T);
            onTimeout();
          }
          return;
        }
        settled = true;
        clearTimeout(timer);
        if (error) reject(new Error(message));
        else resolve(value as T);
      });
    } catch {
      if (!settled) {
        settled = true;
        clearTimeout(timer);
        onTimeout();
        reject(new Error(message));
      }
    }
  });
}

class SshChannel implements RemoteChannel {
  readonly stdout: ClientChannel;
  readonly stderr: Readable;
  readonly stdin: ClientChannel;
  private open = true;
  private status: number | null = null;
  private signalName: string | null = null;

  constructor(private readonly channel: ClientChannel) {
    this.stdout = channel;
    this.stderr = channel.stderr;
    this.stdin = channel;
    channel.on('exit', (code: number | null | undefined, signal: string | null | undefined) => {
      if (typeof code === 'number') this.status = code;
      if (typeof signal === 'string') this.signalName = signal;
    });
    channel.on('close', (code: number | null | undefined, signal: string | null | undefined) => {
      this.open = false;
      if (typeof code === 'number') this.status = code;
      if (typeof signal === 'string') this.signalName = signal;
    });
  }
  isOpen(): boolean { return this.open; }
  exitStatus(): number | null { return this.status; }
  exitSignal(): string | null { return this.signalName; }
  eof(): Promise<void> {
    if (!this.open) return Promise.resolve();
    return networkRequest<void>((done) => {
      const onError = () => { this.channel.off('error', onError); done(new Error(ERR_CHANNEL)); };
      this.channel.once('error', onError);
      this.channel.end(() => { this.channel.off('error', onError); done(undefined); });
    }, () => this.close(), ERR_CHANNEL);
  }
  resize(rows: number, cols: number): Promise<void> {
    return networkRequest<void>((done) => {
      try { this.channel.setWindow(rows, cols, 0, 0); done(undefined); } catch { done(new Error(ERR_CHANNEL)); }
    }, () => this.close(), ERR_CHANNEL);
  }
  signal(name: string): Promise<void> {
    return networkRequest<void>((done) => {
      try { this.channel.signal(name); done(undefined); } catch { done(new Error(ERR_CHANNEL)); }
    }, () => this.close(), ERR_CHANNEL);
  }
  close(): void { this.channel.close(); this.open = false; }
}

class SshConnection implements RemoteConnection {
  private open = true;
  private readonly closeListeners = new Set<() => void>();
  constructor(private readonly client: Ssh2Client, private readonly agent?: BoundedAgent) {
    client.on('error', () => { this.disconnected(); try { client.destroy(); } catch { /* Socket is already closed. */ } });
    client.once('close', () => this.disconnected());
  }
  private disconnected(): void {
    if (!this.open) return;
    this.open = false;
    this.agent?.stop();
    for (const listener of this.closeListeners) {
      try { listener(); } catch { /* Lifecycle notifications must not break connection cleanup. */ }
    }
    this.closeListeners.clear();
  }
  isOpen(): boolean { return this.open; }
  onClose(listener: () => void): void {
    if (!this.open) {
      try { listener(); } catch { /* Lifecycle notifications must not break connection cleanup. */ }
      return;
    }
    this.closeListeners.add(listener);
  }

  exec(command: string, options: TerminalOptions): Promise<RemoteChannel> {
    if (!this.open) return Promise.reject(new Error(ERR_CLOSED));
    return networkRequest<RemoteChannel>((done) => {
      const cb = (error: Error | undefined, channel?: ClientChannel) => done(error, channel ? new SshChannel(channel) : undefined);
      try {
        if (options.pty) this.client.exec(command, { pty: { rows: options.rows ?? 24, cols: options.cols ?? 80 } }, cb);
        else this.client.exec(command, cb);
      } catch { done(new Error(ERR_CHANNEL)); }
    }, () => this.close(), ERR_CHANNEL, (channel) => channel.close());
  }

  async upload(path: string, offset: number, bytes: Buffer, truncate: boolean): Promise<number> {
    const sftp = await this.openSftp();
    let handle: Buffer | undefined;
    try {
      handle = await networkRequest<Buffer>((done) => sftp.open(path, truncate ? 'w' : 0x00000002 | 0x00000008, (err, value) => done(err ?? undefined, value)), () => { if (handle) sftp.close(handle, () => {}); this.close(); }, ERR_SFTP, (lateHandle) => sftp.close(lateHandle, () => {}));
      if (bytes.length) await networkRequest<void>((done) => sftp.write(handle!, bytes, 0, bytes.length, offset, (err) => done(err ?? undefined)), () => { sftp.close(handle!, () => {}); this.close(); }, ERR_SFTP);
      await this.closeHandle(sftp, handle);
      return bytes.length;
    } catch (error) {
      if (handle) sftp.close(handle, () => {});
      throw error instanceof Error && [ERR_SFTP, ERR_CLOSED].includes(error.message) ? error : new Error(ERR_SFTP);
    } finally { sftp.end(); }
  }

  async download(path: string, offset: number, maxBytes: number): Promise<{ bytes: Buffer; eof: boolean }> {
    const sftp = await this.openSftp();
    let handle: Buffer | undefined;
    try {
      handle = await networkRequest<Buffer>((done) => sftp.open(path, 'r', (err, value) => done(err, value)), () => { if (handle) sftp.close(handle, () => {}); this.close(); }, ERR_SFTP, (lateHandle) => sftp.close(lateHandle, () => {}));
      if (maxBytes === 0) {
        const probe = await this.readChunk(sftp, handle, offset, 1);
        await this.closeHandle(sftp, handle);
        return { bytes: Buffer.alloc(0), eof: probe.eof || probe.bytes.length === 0 };
      }
      const chunk = await this.readChunk(sftp, handle, offset, Math.max(0, maxBytes));
      let eof = chunk.eof;
      if (!eof && chunk.bytes.length < maxBytes) {
        const probe = await this.readChunk(sftp, handle, offset + chunk.bytes.length, 1);
        eof = probe.eof || probe.bytes.length === 0;
      } else if (!eof && maxBytes > 0 && chunk.bytes.length === maxBytes) {
        const probe = await this.readChunk(sftp, handle, offset + chunk.bytes.length, 1);
        eof = probe.eof || probe.bytes.length === 0;
      }
      await this.closeHandle(sftp, handle);
      return { bytes: chunk.bytes, eof };
    } catch (error) {
      if (handle) sftp.close(handle, () => {});
      throw error instanceof Error && [ERR_SFTP, ERR_CLOSED].includes(error.message) ? error : new Error(ERR_SFTP);
    } finally { sftp.end(); }
  }

  private openSftp(): Promise<SFTPWrapper> {
    if (!this.open) return Promise.reject(new Error(ERR_CLOSED));
    return networkRequest<SFTPWrapper>((done) => this.client.sftp((err: Error | undefined, sftp: SFTPWrapper) => done(err, sftp)), () => this.close(), ERR_SFTP, (lateSftp) => lateSftp.end());
  }
  private closeHandle(sftp: SFTPWrapper, handle: Buffer): Promise<void> {
    return networkRequest<void>((done) => sftp.close(handle, (err) => done(err ?? undefined)), () => { sftp.close(handle, () => {}); this.close(); }, ERR_SFTP);
  }
  private readChunk(sftp: SFTPWrapper, handle: Buffer, offset: number, length: number): Promise<{ bytes: Buffer; eof: boolean }> {
    if (length === 0) return Promise.resolve({ bytes: Buffer.alloc(0), eof: false });
    return networkRequest<{ bytes: Buffer; eof: boolean }>((done) => {
      const buffer = Buffer.alloc(length);
      sftp.read(handle, buffer, 0, length, offset, (error, count) => {
        if (error && (error as Error & { code?: number }).code === utils.sftp.STATUS_CODE.EOF) return done(undefined, { bytes: Buffer.alloc(0), eof: true });
        done(error ?? undefined, { bytes: buffer.subarray(0, count ?? 0), eof: false });
      });
    }, () => { sftp.close(handle, () => {}); this.close(); }, ERR_SFTP);
  }
  close(): void {
    if (!this.open) return;
    this.disconnected();
    try { this.client.destroy(); } catch { /* Closing a stale socket is idempotent. */ }
  }
}

export class SshTransport implements RemoteTransport {
  async connect(target: Target, profile: Profile, signal?: AbortSignal): Promise<RemoteConnection> {
    let selected: ParsedKey | undefined;
    let privateKey: Buffer | undefined;
    let certificateKey: ParsedKey | undefined;
    let hostKey: ParsedKey;
    try {
      hostKey = parsePublicKey(target.host_key);
      if (profile.private_key_path !== undefined) {
        privateKey = await privateKeyFor(profile.private_key_path, signal);
        if (profile.certificate_path !== undefined) certificateKey = await certificateKeyFor(profile.certificate_path, privateKey, signal);
      } else selected = await publicKeyFor(profile.public_key_path, signal);
    } catch { throw new Error(signal?.aborted ? ERR_CONNECT : 'SSH key configuration is invalid'); }
    if (signal?.aborted) throw new Error(ERR_CONNECT);
    const agent = selected && profile.agent_socket ? new BoundedAgent(profile.agent_socket, selected) : undefined;
    const client = new Client();
    const hostBlob = hostKey.getPublicSSH();
    const serverHostKey: ServerHostKeyAlgorithm[] = hostKey.type === 'ssh-rsa' ? ['rsa-sha2-512', 'rsa-sha2-256'] : [hostKey.type];
    return new Promise((resolve, reject) => {
      let settled = false;
      let hostPinMismatch = false;
      const timer = setTimeout(() => fail(ERR_CONNECT), NETWORK_TIMEOUT_MS);
      const onAbort = () => fail(ERR_CONNECT);
      const cleanup = () => {
        clearTimeout(timer);
        signal?.removeEventListener('abort', onAbort);
        client.removeListener('ready', onReady);
      };
      const fail = (message: string) => {
        if (settled) return;
        settled = true;
        cleanup();
        agent?.stop();
        client.destroy();
        reject(new Error(message));
      };
      const onError = (_error: Error) => fail(hostPinMismatch ? ERR_HOST_KEY : ERR_AUTH);
      const onReady = () => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve(new SshConnection(client, agent));
      };
      client.once('ready', onReady);
      client.on('error', onError);
      client.once('close', () => { client.removeListener('error', onError); if (!settled) fail(ERR_CONNECT); });
      signal?.addEventListener('abort', onAbort, { once: true });
      const config: ConnectConfig = {
        host: target.host,
        port: target.port,
        username: profile.login_user,
        ...(agent ? { agent, authHandler: ['agent' as const] } : certificateKey ? { authHandler: certificateAuth(profile.login_user, certificateKey) } : { privateKey, authHandler: ['publickey' as const] }),
        readyTimeout: NETWORK_TIMEOUT_MS,
        tryKeyboard: false,
        algorithms: { serverHostKey },
        hostVerifier: (key: Buffer) => { hostPinMismatch = !key.equals(hostBlob); return !hostPinMismatch; },
      };
      try { client.connect(config); } catch { fail(ERR_CONNECT); }
    });
  }
}
