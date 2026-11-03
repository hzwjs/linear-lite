import type { Readable, Writable } from 'node:stream';

export type Profile = {
  profile_id: string;
  login_user: string;
  max_ttl_seconds: number;
} & ({ public_key_path: string; agent_socket: string; private_key_path?: never } | { private_key_path: string; certificate_path?: string; public_key_path?: never; agent_socket?: never });
export type Target = {
  target_id: string;
  alias: string;
  environment: string;
  host: string;
  port: number;
  host_key: string;
  profiles: Profile[];
};
export type Configuration = { targets: Target[] };
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
