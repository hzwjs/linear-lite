import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { homedir } from 'node:os';
import { dirname, join } from 'node:path';
import { randomBytes } from 'node:crypto';

/** Per-user state (tokens, host key pins, catalogue cache); never inside a repository. */
export function stateDir(): string {
  return process.env.REMOTE_TERMINAL_MCP_HOME ?? join(homedir(), '.config', 'remote-terminal-mcp');
}

/** Returns undefined when the file does not exist; throws when it exists but is not valid JSON. */
export async function readJson<T>(path: string): Promise<T | undefined> {
  let text: string;
  try { text = await readFile(path, 'utf8'); }
  catch (error) { if ((error as NodeJS.ErrnoException).code === 'ENOENT') return undefined; throw error; }
  return JSON.parse(text) as T;
}

/** Atomic replace with owner-only permissions, so concurrent MCP processes never read a torn file. */
export async function writeJsonPrivate(path: string, value: unknown): Promise<void> {
  await mkdir(dirname(path), { recursive: true, mode: 0o700 });
  const temporary = `${path}.${process.pid}.${randomBytes(4).toString('hex')}.tmp`;
  await writeFile(temporary, JSON.stringify(value, null, 1), { mode: 0o600 });
  await rename(temporary, path);
}
