import type { Readable, Writable } from 'node:stream';

export type ListedProfile = { profile_id: string; login_user: string; max_ttl_seconds: number };
export type Profile = ListedProfile & (
  | { public_key_path: string; agent_socket: string; private_key_path?: never; ephemeral?: never; password?: never; password_file?: never; permit_pty?: never }
  | { private_key_path: string; certificate_path?: string; public_key_path?: never; agent_socket?: never; ephemeral?: never; password?: never; password_file?: never; permit_pty?: never }
  /** Team mode: a session key and CA-signed certificate held only in memory. */
  | { ephemeral: { private_key: Buffer; certificate: string }; private_key_path?: never; public_key_path?: never; agent_socket?: never; password?: never; password_file?: never; permit_pty?: never }
  /** Team mode: the cloud credential exists only during connection setup. */
  | { password: string; permit_pty: boolean; ephemeral?: never; private_key_path?: never; public_key_path?: never; agent_socket?: never; password_file?: never }
  | { password_file: string; password?: never; permit_pty?: never; ephemeral?: never; private_key_path?: never; public_key_path?: never; agent_socket?: never }
);
export type Target = {
  target_id: string;
  alias: string;
  environment: string;
  host: string;
  port: number;
  host_key: string;
  profiles: Profile[];
};
export type ListedTarget = Omit<Target, 'profiles'> & { profiles: ListedProfile[] };
export type Configuration = { targets: Target[] };
export type ConnectInfo = { target: Target; profile: Profile; afterConnect?: () => Promise<void> };
/** Where the service learns which servers exist and how to log in to one. */
export interface TargetSource {
  list(): Promise<ListedTarget[]>;
  connectInfo(targetId: string, profileId: string, ttlSeconds: number, signal: AbortSignal): Promise<ConnectInfo>;
}
export type TerminalOptions = { pty: boolean; rows?: number; cols?: number };
export interface RemoteChannel {
  stdout: Readable;
  stderr: Readable;
  stdin: Writable;
  isOpen(): boolean;
  exitStatus(): number | null;
  exitSignal(): string | null;
  eof(): Promise<void>;
  resize(rows: number, cols: number): Promise<void>;
  signal(name: string): Promise<void>;
  close(): void;
}
export interface RemoteConnection {
  isOpen(): boolean;
  onClose(listener: () => void): void;
  exec(command: string, options: TerminalOptions): Promise<RemoteChannel>;
  upload(path: string, offset: number, bytes: Buffer, truncate: boolean): Promise<number>;
  download(path: string, offset: number, maxBytes: number): Promise<{ bytes: Buffer; eof: boolean }>;
  close(): void;
}
export interface RemoteTransport {
  connect(target: Target, profile: Profile, signal?: AbortSignal): Promise<RemoteConnection>;
}
