import { z } from 'zod';

const MAX_SAFE = Number.MAX_SAFE_INTEGER;
const validUnicode = (value: string) => Buffer.from(value, 'utf8').toString('utf8') === value;
const id = z.string().uuid();
const catalogueId = z.string().regex(/^[A-Za-z0-9._-]{1,100}$/);
const integer = (min: number, max: number) => z.number().int().min(min).max(max);
const base64 = (max: number, allowEmpty: boolean) => z.string().min(allowEmpty ? 0 : 1).max(4 * Math.ceil(max / 3)).refine(value => {
  const bytes = Buffer.from(value, 'base64');
  return bytes.length <= max && bytes.toString('base64') === value;
}, 'Canonical Base64 required');
const remotePath = z.string().min(1).max(4096).refine(validUnicode).refine(value => value.startsWith('/') && !/[\0\r\n]/.test(value), 'Remote absolute path required');
const offset = integer(0, MAX_SAFE - 262144);
const signal = z.enum(['INT','TERM','KILL','HUP','QUIT','USR1','USR2']);
const action = z.discriminatedUnion('kind', [
  z.object({ kind: z.literal('data'), data_base64: base64(65536, false) }).strict(),
  z.object({ kind: z.literal('eof') }).strict(),
  z.object({ kind: z.literal('close') }).strict(),
  z.object({ kind: z.literal('resize'), rows: integer(1,1000), cols: integer(1,1000) }).strict(),
  z.object({ kind: z.literal('signal'), signal }).strict(),
]);
// Single object rather than a discriminated union: clients such as Claude Code reject a top-level oneOf in tool input schemas.
const exec = z.object({
  session_id: id,
  command: z.string().min(1).max(1048576).refine(validUnicode).refine(value => !value.includes('\0') && Buffer.byteLength(value) <= 1048576),
  pty: z.boolean(),
  rows: integer(1,1000).optional(),
  cols: integer(1,1000).optional(),
}).strict().refine(value => value.pty ? value.rows !== undefined && value.cols !== undefined : value.rows === undefined && value.cols === undefined, 'rows and cols are required with a PTY and not allowed without one');
export const toolSchemas = {
  list_targets: z.object({}).strict(),
  open_session: z.object({ target_id: catalogueId, profile_id: catalogueId, ttl_seconds: integer(1,28800) }).strict(),
  close_session: z.object({ session_id: id }).strict(),
  exec,
  read_output: z.object({ session_id: id, channel_id: id, after_seq: integer(0, MAX_SAFE), max_bytes: integer(16384,262144), wait_ms: integer(0,20000) }).strict(),
  write_stdin: z.object({ session_id: id, channel_id: id, action }).strict(),
  upload: z.object({ session_id: id, remote_path: remotePath, offset, data_base64: base64(262144,true), truncate: z.boolean() }).strict().refine(value => !value.truncate || value.offset === 0, 'Truncate requires offset zero'),
  download: z.object({ session_id: id, remote_path: remotePath, offset, max_bytes: integer(1,262144) }).strict(),
};
export type ToolName = keyof typeof toolSchemas;
