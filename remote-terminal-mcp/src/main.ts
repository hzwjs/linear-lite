#!/usr/bin/env node
import { isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { McpServer } from '@modelcontextprotocol/server';
import { StdioServerTransport, serveStdio } from '@modelcontextprotocol/server/stdio';
import type { Readable, Writable } from 'node:stream';
import { CloudSource } from './cloud.js';
import { loadConfig } from './config.js';
import { login, logout, normalizeServer } from './oauth.js';
import { checkPin, fingerprint, recordPin } from './pins.js';
import { TerminalService } from './service.js';
import { registerTerminalTools } from './tools.js';

const HELP = [
  'Usage:',
  '  remote-terminal-mcp --config /absolute/path/to/targets.json',
  '  remote-terminal-mcp --server https://host --project KEY',
  '  remote-terminal-mcp login --server https://host',
  '  remote-terminal-mcp logout --server https://host',
  '  remote-terminal-mcp pins accept --server https://host --project KEY TARGET_ID',
  '',
].join('\n');
const GENERIC_ERROR = 'Remote terminal MCP could not start.';

export type CliOptions = {
  command: 'serve' | 'login' | 'logout' | 'pins-accept';
  configPath?: string; server?: string; project?: string; targetId?: string; help: boolean;
};

export function parseArgs(args: string[]): CliOptions {
  const rest = [...args];
  let command: CliOptions['command'] = 'serve';
  if (rest[0] === 'login' || rest[0] === 'logout') command = rest.shift() as 'login' | 'logout';
  else if (rest[0] === 'pins' && rest[1] === 'accept') { rest.splice(0, 2); command = 'pins-accept'; }
  const options: CliOptions = { command, help: false };
  for (let i = 0; i < rest.length; i++) {
    const arg = rest[i]!;
    const value = () => { if (i + 1 >= rest.length) throw new Error('Invalid command line'); return rest[++i]!; };
    if (arg === '--help' || arg === '-h') options.help = true;
    else if (arg === '--config' && options.configPath === undefined) options.configPath = value();
    else if (arg === '--server' && options.server === undefined) options.server = value();
    else if (arg === '--project' && options.project === undefined) options.project = value();
    else if (command === 'pins-accept' && options.targetId === undefined && !arg.startsWith('-')) options.targetId = arg;
    else throw new Error('Invalid command line');
  }
  if (options.help) return options;
  if (command === 'serve') {
    const team = options.server !== undefined || options.project !== undefined;
    if (team === (options.configPath !== undefined)) throw new Error('Invalid command line');
    if (team && (!options.server || !options.project)) throw new Error('Invalid command line');
    if (options.configPath !== undefined && !isAbsolute(options.configPath)) throw new Error('Invalid command line');
  } else if (!options.server || options.configPath !== undefined || (command !== 'pins-accept' && (options.project || options.targetId))
    || (command === 'pins-accept' && (!options.project || !options.targetId))) throw new Error('Invalid command line');
  return options;
}

export function serveTerminalService(
  service: TerminalService,
  input?: Readable,
  output?: Writable,
  onStdioError: (message: string) => void = () => {},
) {
  const transport = input && output
    ? new StdioServerTransport(input, output)
    : new StdioServerTransport();
  const closeTransport = transport.close.bind(transport);
  let closing: Promise<void> | undefined;
  // A discovery probe can be discarded without closing the stdio connection.
  // Keep SSH cleanup on the physical transport, rather than an SDK instance.
  transport.close = () => {
    closing ??= closeTransport().finally(() => service.close());
    return closing;
  };
  return serveStdio(() => {
    // The SDK creates a new instance when a discovery probe changes protocol era.
    const server = new McpServer({ name: 'remote-terminal-mcp', version: '0.1.0' }, { capabilities: { tools: {} } });
    registerTerminalTools(server, service);
    return server;
  }, {
    transport,
    legacy: 'serve',
    onerror: () => onStdioError('MCP stdio transport error.'),
  });
}

async function runSubcommand(options: CliOptions): Promise<void> {
  if (options.command === 'login') return login(options.server!);
  if (options.command === 'logout') { await logout(options.server!); process.stdout.write('Signed out\n'); return; }
  // pins accept: show the key the cloud currently advertises and ask the human to confirm it.
  const server = normalizeServer(options.server!);
  const source = new CloudSource({ server, project: options.project! });
  const listed = (await source.list()).find(item => item.target_id === options.targetId);
  if (!listed) throw new Error('Target not found in your catalogue');
  const pin = await checkPin(server, options.project!, options.targetId!, listed.host_key);
  if (pin.result === 'same') { process.stdout.write('Already pinned to this host key\n'); return; }
  process.stdout.write(`${options.targetId} advertises host key ${fingerprint(listed.host_key)}`
    + (pin.result === 'changed' ? ` (currently pinned: ${fingerprint(pin.pinned)})` : '') + '\n');
  if (!process.stdin.isTTY) throw new Error('Run this command in a terminal to confirm the fingerprint');
  const { createInterface } = await import('node:readline/promises');
  const rl = createInterface({ input: process.stdin, output: process.stdout });
  const answer = (await rl.question('Compare it with the owner\'s value. Type "yes" to pin: ')).trim();
  rl.close();
  if (answer !== 'yes') throw new Error('Not pinned');
  await recordPin(server, options.project!, options.targetId!, listed.host_key);
  process.stdout.write('Pinned\n');
}

export async function runCli(args = process.argv.slice(2)): Promise<void> {
  let options: CliOptions;
  try {
    options = parseArgs(args);
  } catch {
    process.stderr.write(HELP);
    process.exitCode = 2;
    return;
  }
  if (options.help) {
    process.stderr.write(HELP);
    return;
  }

  if (options.command !== 'serve') {
    try { await runSubcommand(options); } catch (error) {
      process.stderr.write(`${error instanceof Error ? error.message : 'Command failed'}\n`);
      process.exitCode = 1;
    }
    return;
  }

  let service: TerminalService | undefined;
  let handle: ReturnType<typeof serveTerminalService> | undefined;
  let shuttingDown: Promise<void> | undefined;
  const shutdown = () => {
    if (!shuttingDown) {
      shuttingDown = (async () => {
        await service?.close();
        await handle?.close();
      })();
    }
    return shuttingDown;
  };

  try {
    const { SshTransport } = await import('./ssh.js');
    const source = options.configPath !== undefined
      ? await loadConfig(options.configPath)
      : new CloudSource({ server: options.server!, project: options.project! });
    service = new TerminalService(source, new SshTransport());
    handle = serveTerminalService(service, undefined, undefined, message => process.stderr.write(`${message}\n`));
    let finishWait!: () => void;
    const inputEnded = new Promise<void>(resolve => { finishWait = resolve; });
    const onSignal = (exitCode: number) => {
      process.exitCode = exitCode;
      process.stdin.pause();
      void shutdown().finally(finishWait);
    };
    const onSigint = () => onSignal(130);
    const onSigterm = () => onSignal(143);
    process.once('SIGINT', onSigint);
    process.once('SIGTERM', onSigterm);
    process.stdin.once('end', finishWait);
    process.stdin.once('close', finishWait);
    await inputEnded;
    process.stdin.off('end', finishWait);
    process.stdin.off('close', finishWait);
    process.off('SIGINT', onSigint);
    process.off('SIGTERM', onSigterm);
    await shutdown();
  } catch {
    await service?.close();
    await handle?.close();
    process.stderr.write(`${GENERIC_ERROR}\n`);
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  void runCli();
}
