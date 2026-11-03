import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';
import type { Configuration, Profile, RemoteChannel, RemoteConnection, RemoteTransport, Target } from './transport.js';
import { toolSchemas, type ToolName } from './schemas.js';

type Frame = { seq: number; stream: 'stdout' | 'stderr' | 'pty'; bytes: Buffer };
type Channel = { id: string; remote: RemoteChannel; pty: boolean; frames: Frame[]; next: number; size: number; ended: Set<string>; error: boolean; closed: boolean };
type Session = { id: string; targetId: string; profileId: string; loginUser: string; expires: number; remote: RemoteConnection; channels: Map<string, Channel>; pendingChannels: number; timer: NodeJS.Timeout };
const BUFFER_BYTES = 1024 * 1024;

/** The process owns live SSH handles, not remote operation history or retry policy. */
export class TerminalService {
  private readonly sessions = new Map<string, Session>();
  private readonly connecting = new Set<AbortController>();
  private pendingChannels = 0;
  private closed = false;
  constructor(private readonly config: Configuration, private readonly transport: RemoteTransport) {}

  async call(name: ToolName, raw: unknown): Promise<Record<string, unknown>> {
    if (this.closed) throw new Error('Local terminal service is closed');
    switch (name) {
      case 'list_targets': {
        toolSchemas[name].parse(raw);
        return { targets: this.config.targets.map(target => ({ target_id: target.target_id, alias: target.alias, environment: target.environment,
          profiles: target.profiles.map(profile => ({ profile_id: profile.profile_id, login_user: profile.login_user, max_ttl_seconds: profile.max_ttl_seconds })) })) };
      }
      case 'open_session': {
        const args = toolSchemas[name].parse(raw);
        const { target, profile } = this.lookup(args.target_id, args.profile_id);
        if (args.ttl_seconds > profile.max_ttl_seconds) throw new Error('Connection lifetime exceeds the configured identity limit');
        if (this.sessions.size + this.connecting.size >= 32) throw new Error('Connection limit reached');
        const controller = new AbortController();
        this.connecting.add(controller);
        const expires = Date.now() + args.ttl_seconds * 1000;
        let connection: RemoteConnection | undefined;
        try {
          connection = await this.transport.connect(target, profile, controller.signal);
          if (this.closed || controller.signal.aborted || Date.now() >= expires || !connection.isOpen()) throw new Error('Connection was closed during authentication');
          const id = randomUUID();
          const timer = setTimeout(() => this.release(id), expires - Date.now());
          timer.unref();
          this.sessions.set(id, { id, targetId: target.target_id, profileId: profile.profile_id, loginUser: profile.login_user, expires, remote: connection, channels: new Map(), pendingChannels: 0, timer });
          connection.onClose(() => this.release(id));
          if (!this.sessions.has(id)) throw new Error('Connection closed before its handle was returned');
          return { session_id: id, target_id: target.target_id, profile_id: profile.profile_id, login_user: profile.login_user, expires_at: new Date(expires).toISOString() };
        } catch { connection?.close(); throw new Error('SSH connection could not be opened'); }
        finally { this.connecting.delete(controller); }
      }
      case 'close_session': {
        const args = toolSchemas[name].parse(raw);
        this.session(args.session_id);
        this.release(args.session_id);
        return { closed: true };
      }
      case 'exec': {
        const args = toolSchemas[name].parse(raw);
        const session = this.session(args.session_id);
        let activeChannels = this.pendingChannels;
        for (const connection of this.sessions.values()) activeChannels += connection.channels.size;
        if (session.channels.size + session.pendingChannels >= 8 || activeChannels >= 32) throw new Error('Terminal channel limit reached; close an unused channel');
        session.pendingChannels++; this.pendingChannels++;
        let remote: RemoteChannel | undefined;
        try {
          remote = await session.remote.exec(args.command, args.pty ? { pty: true, rows: args.rows, cols: args.cols } : { pty: false });
          this.session(session.id);
          const channel: Channel = { id: randomUUID(), remote, pty: args.pty, frames: [], next: 1, size: 0, ended: new Set(), error: false, closed: false };
          session.channels.set(channel.id, channel);
          this.capture(channel, remote.stdout, args.pty ? 'pty' : 'stdout', 'stdout');
          this.capture(channel, remote.stderr, args.pty ? 'pty' : 'stderr', 'stderr');
          return { session_id: session.id, channel_id: channel.id };
        } catch { remote?.close(); throw new Error('SSH command channel failed; remote execution is unknown'); }
        finally { session.pendingChannels--; this.pendingChannels--; }
      }
      case 'read_output': {
        const args = toolSchemas[name].parse(raw);
        const session = this.session(args.session_id);
        const channel = this.channel(session, args.channel_id);
        const until = Date.now() + args.wait_ms;
        while (true) {
          this.session(session.id);
          if (channel.closed) throw new Error('Terminal channel is unavailable');
          if (args.after_seq >= channel.next) throw new Error('Output position exceeds the channel stream');
          const first = channel.frames[0]?.seq ?? channel.next;
          const truncated = args.after_seq < first - 1;
          let next = truncated ? first - 1 : args.after_seq;
          let size = 0;
          const chunks: Array<{ seq: number; stream: string; data_base64: string }> = [];
          for (const frame of channel.frames) {
            if (frame.seq <= args.after_seq) continue;
            if (size + frame.bytes.length > args.max_bytes) break;
            chunks.push({ seq: frame.seq, stream: frame.stream, data_base64: frame.bytes.toString('base64') });
            size += frame.bytes.length; next = frame.seq;
          }
          const eof = channel.ended.size === 2 && next === channel.next - 1;
          const channelOpen = channel.remote.isOpen();
          const status = channel.remote.exitStatus();
          const signal = channel.remote.exitSignal();
          if (chunks.length || truncated || Date.now() >= until || (eof && (!channelOpen || status !== null || signal !== null))) {
            return { session_id: session.id, channel_id: channel.id, chunks, next_seq: next, first_available_seq: first, truncated, eof,
              exit_status: status, exit_signal: signal, channel_open: channelOpen, transport_error: channel.error };
          }
          await delay(Math.min(50, Math.max(1, until - Date.now())));
        }
      }
      case 'write_stdin': {
        const args = toolSchemas[name].parse(raw);
        const session = this.session(args.session_id);
        const channel = this.channel(session, args.channel_id);
        try {
          switch (args.action.kind) {
            case 'close': this.releaseChannel(session, channel); break;
            case 'data': {
              const bytes = Buffer.from(args.action.data_base64, 'base64');
              await this.inputDeadline(session, channel, () => new Promise<void>((resolve, reject) => {
                channel.remote.stdin.write(bytes, error => error ? reject(error) : resolve());
              }));
              break;
            }
            case 'eof': await this.inputDeadline(session, channel, () => channel.remote.eof()); break;
            case 'resize': {
              if (!channel.pty) throw new Error('Resize requires a PTY channel');
              await channel.remote.resize(args.action.rows, args.action.cols); break;
            }
            case 'signal': await channel.remote.signal(args.action.signal); break;
          }
          return { sent: true };
        } catch { throw new Error('SSH terminal operation failed; delivery is unknown'); }
      }
      case 'upload': {
        const args = toolSchemas[name].parse(raw);
        const session = this.session(args.session_id);
        try {
          const written = await session.remote.upload(args.remote_path, args.offset, Buffer.from(args.data_base64, 'base64'), args.truncate);
          return { bytes_written: written, next_offset: args.offset + written };
        } catch { throw new Error('SFTP upload failed; remote file changes are unknown'); }
      }
      case 'download': {
        const args = toolSchemas[name].parse(raw);
        const session = this.session(args.session_id);
        try {
          const result = await session.remote.download(args.remote_path, args.offset, args.max_bytes);
          return { data_base64: result.bytes.toString('base64'), next_offset: args.offset + result.bytes.length, eof: result.eof };
        } catch { throw new Error('SFTP download failed'); }
      }
    }
  }

  private capture(channel: Channel, input: NodeJS.ReadableStream, stream: Frame['stream'], source: string): void {
    input.on('data', (value: Buffer | string) => {
      if (channel.closed) return;
      const bytes = Buffer.isBuffer(value) ? value : Buffer.from(value);
      // Bound each frame so the minimum read size can always make progress.
      for (let start = 0; start < bytes.length; start += 4096) {
        const part = Buffer.from(bytes.subarray(start, start + 4096));
        while (channel.size + part.length > BUFFER_BYTES) channel.size -= channel.frames.shift()!.bytes.length;
        channel.frames.push({ seq: channel.next++, stream, bytes: part }); channel.size += part.length;
      }
    });
    input.on('end', () => channel.ended.add(source));
    input.on('close', () => channel.ended.add(source));
    input.on('error', () => { channel.error = true; channel.ended.add(source); });
  }
  private lookup(targetId: string, profileId: string): { target: Target; profile: Profile } {
    const target = this.config.targets.find(item => item.target_id === targetId);
    const profile = target?.profiles.find(item => item.profile_id === profileId);
    if (!target || !profile) throw new Error('Configured server identity is unavailable');
    return { target, profile };
  }
  private async inputDeadline(session: Session, channel: Channel, operation: () => Promise<void>): Promise<void> {
    let timer: NodeJS.Timeout | undefined;
    const timeout = new Promise<never>((_resolve, reject) => {
      timer = setTimeout(() => { this.releaseChannel(session, channel); reject(new Error('Terminal input timeout')); }, 10000);
      timer.unref();
    });
    try { await Promise.race([operation(), timeout]); }
    finally { clearTimeout(timer); }
  }
  private session(id: string): Session {
    const session = this.sessions.get(id);
    if (!session) throw new Error('Connection handle is unavailable');
    if (this.closed || Date.now() >= session.expires || !session.remote.isOpen()) { this.release(id); throw new Error('SSH connection expired or disconnected'); }
    return session;
  }
  private channel(session: Session, id: string): Channel {
    const channel = session.channels.get(id);
    if (!channel) throw new Error('Terminal channel is unavailable');
    return channel;
  }
  private releaseChannel(session: Session, channel: Channel): void {
    if (channel.closed) return;
    channel.closed = true;
    session.channels.delete(channel.id);
    channel.remote.close();
    channel.frames.length = 0;
  }
  private release(id: string): void {
    const session = this.sessions.get(id);
    if (!session) return;
    this.sessions.delete(id);
    clearTimeout(session.timer);
    for (const channel of session.channels.values()) this.releaseChannel(session, channel);
    session.remote.close();
  }
  close(): void {
    this.closed = true;
    for (const controller of this.connecting) controller.abort();
    for (const session of this.sessions.values()) this.release(session.id);
  }
}
