import { readFile, stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import { z } from 'zod';
import type { Configuration } from './transport.js';

const identifier = z.string().regex(/^[A-Za-z0-9._-]{1,100}$/);
const localPath = z.string().min(1).max(4096).refine(isAbsolute).refine(value => !value.includes('\0'));
const text = z.string().min(1).max(256).refine(value => value.trim().length > 0);
export const configurationSchema = z.object({
  targets: z.array(z.object({
    target_id: identifier,
    alias: text,
    environment: text,
    host: text.refine(value => !/[\s\0]/.test(value)),
    port: z.number().int().min(1).max(65535),
    host_key: z.string().min(1).max(16384),
    profiles: z.array(z.union([z.object({
      profile_id: identifier,
      login_user: text.refine(value => !/[\s\0]/.test(value)),
      public_key_path: localPath,
      agent_socket: localPath,
      max_ttl_seconds: z.number().int().min(1).max(28800),
    }).strict(), z.object({
      profile_id: identifier,
      login_user: text.refine(value => !/[\s\0]/.test(value)),
      private_key_path: localPath,
      certificate_path: localPath.optional(),
      max_ttl_seconds: z.number().int().min(1).max(28800),
    }).strict(), z.object({
      profile_id: identifier,
      login_user: text.refine(value => !/[\s\0]/.test(value)),
      password_file: localPath,
      max_ttl_seconds: z.number().int().min(1).max(28800),
    }).strict()])).min(1).max(20).refine(profiles => new Set(profiles.map(profile => profile.profile_id)).size === profiles.length),
  }).strict()).max(100).refine(targets => new Set(targets.map(target => target.target_id)).size === targets.length),
}).strict();

export async function loadConfig(path: string): Promise<Configuration> {
  try {
    const info = await stat(path);
    if (!info.isFile() || info.size > 1024 * 1024) throw new Error('Configuration size exceeds limit');
    return configurationSchema.parse(JSON.parse(await readFile(path, 'utf8')));
  } catch { throw new Error('Invalid local connection configuration'); }
}
