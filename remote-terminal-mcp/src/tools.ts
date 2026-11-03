import { McpServer } from '@modelcontextprotocol/server';
import { z } from 'zod';
import { toolSchemas, type ToolName } from './schemas.js';
import type { TerminalService } from './service.js';

const targetProfile = z.object({
  profile_id: z.string(),
  login_user: z.string(),
  max_ttl_seconds: z.number().int(),
}).strict();

const outputSchemas: Record<ToolName, z.ZodType> = {
  list_targets: z.object({
    targets: z.array(z.object({
      target_id: z.string(),
      alias: z.string(),
      environment: z.string(),
      profiles: z.array(targetProfile),
    }).strict()),
  }).strict(),
  open_session: z.object({
    session_id: z.string(),
    target_id: z.string(),
    profile_id: z.string(),
    login_user: z.string(),
    expires_at: z.string(),
  }).strict(),
  close_session: z.object({ closed: z.literal(true) }).strict(),
  exec: z.object({ session_id: z.string(), channel_id: z.string() }).strict(),
  read_output: z.object({
    session_id: z.string(),
    channel_id: z.string(),
    chunks: z.array(z.object({
      seq: z.number().int(),
      stream: z.enum(['stdout', 'stderr', 'pty']),
      data_base64: z.string(),
    }).strict()),
    next_seq: z.number().int(),
    first_available_seq: z.number().int(),
    truncated: z.boolean(),
    eof: z.boolean(),
    exit_status: z.number().int().nullable(),
    exit_signal: z.string().nullable(),
    channel_open: z.boolean(),
    transport_error: z.boolean(),
  }).strict(),
  write_stdin: z.object({ sent: z.literal(true) }).strict(),
  upload: z.object({ bytes_written: z.number().int(), next_offset: z.number().int() }).strict(),
  download: z.object({ data_base64: z.string(), next_offset: z.number().int(), eof: z.boolean() }).strict(),
};

const descriptions: Record<ToolName, string> = {
  list_targets: 'List configured server aliases and the login profiles available to this local agent.',
  open_session: 'Open an SSH session for a configured target and profile.',
  close_session: 'Close an SSH session and its channels.',
  exec: 'Start one command on an SSH session and return its channel handle.',
  read_output: 'Read buffered output from a command channel without consuming other readers’ data.',
  write_stdin: 'Send input or a terminal control action to a command channel.',
  upload: 'Write a bounded data block to an absolute path through the session.',
  download: 'Read a bounded data block from an absolute path through the session.',
};

const controlledServiceErrors = new Set([
  'Local terminal service is closed',
  'Configured server identity is unavailable',
  'Connection lifetime exceeds the configured identity limit',
  'Connection limit reached',
  'SSH connection could not be opened',
  'Terminal channel limit reached; close an unused channel',
  'SSH terminal channel could not be opened',
  'Connection handle is unavailable',
  'SSH connection expired or disconnected',
  'Terminal channel is unavailable',
  'Output position exceeds the channel stream',
  'SSH terminal operation failed; delivery is unknown',
  'SFTP upload failed; remote file changes are unknown',
  'SFTP download failed',
]);

function safeErrorMessage(error: unknown): string {
  if (error instanceof Error && controlledServiceErrors.has(error.message)) return error.message;
  return 'Remote operation failed; the result may be unknown.';
}

export function registerTerminalTools(server: McpServer, service: TerminalService): void {
  for (const name of Object.keys(toolSchemas) as ToolName[]) {
    server.registerTool(name, {
      description: descriptions[name],
      inputSchema: toolSchemas[name],
      outputSchema: outputSchemas[name],
    }, async (raw: unknown) => {
      try {
        const structuredContent = await service.call(name, raw);
        return {
          structuredContent,
          content: [{ type: 'text' as const, text: JSON.stringify(structuredContent) }],
        };
      } catch (error) {
        return {
          isError: true,
          content: [{ type: 'text' as const, text: safeErrorMessage(error) }],
        };
      }
    });
  }
}
