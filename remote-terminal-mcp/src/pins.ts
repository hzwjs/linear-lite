import { createHash } from 'node:crypto';
import { join } from 'node:path';
import { ControlledError } from './errors.js';
import { readJson, stateDir, writeJsonPrivate } from './state.js';

type PinFile = { pins: Record<string, { host_key: string; pinned_at: string }> };
export type PinCheck = { result: 'new' } | { result: 'same' } | { result: 'changed'; pinned: string };

const pinPath = () => join(stateDir(), 'pins.json');
const keyOf = (server: string, project: string, targetId: string) => `${server} ${project.toUpperCase()} ${targetId}`;
/** Comparison ignores the comment field of an OpenSSH public key line. */
export const normalizeHostKey = (line: string) => line.trim().split(/\s+/).slice(0, 2).join(' ');

export function fingerprint(hostKey: string): string {
  const blob = Buffer.from(normalizeHostKey(hostKey).split(' ')[1] ?? '', 'base64');
  return `SHA256:${createHash('sha256').update(blob).digest('base64').replace(/=+$/, '')}`;
}

// An unreadable pin file must stop connections: silently starting over would let a corrupted file reset trust.
async function load(): Promise<PinFile> {
  try {
    const stored = await readJson<PinFile>(pinPath());
    if (stored === undefined) return { pins: {} };
    if (typeof stored.pins !== 'object' || stored.pins === null) throw new Error('bad shape');
    return stored;
  } catch { throw new ControlledError('Host key pin store is unreadable; fix or remove it before connecting'); }
}

export async function checkPin(server: string, project: string, targetId: string, hostKey: string): Promise<PinCheck> {
  const pinned = (await load()).pins[keyOf(server, project, targetId)];
  if (!pinned) return { result: 'new' };
  return pinned.host_key === normalizeHostKey(hostKey) ? { result: 'same' } : { result: 'changed', pinned: pinned.host_key };
}

export async function recordPin(server: string, project: string, targetId: string, hostKey: string): Promise<void> {
  const file = await load();
  file.pins[keyOf(server, project, targetId)] = { host_key: normalizeHostKey(hostKey), pinned_at: new Date().toISOString() };
  await writeJsonPrivate(pinPath(), file);
}

export async function pinnedHostKey(server: string, project: string, targetId: string): Promise<string | undefined> {
  return (await load()).pins[keyOf(server, project, targetId)]?.host_key;
}
