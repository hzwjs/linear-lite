#!/usr/bin/env node
import { isAbsolute } from 'node:path';
import { pathToFileURL } from 'node:url';
import { McpServer } from '@modelcontextprotocol/server';
import { StdioServerTransport, serveStdio } from '@modelcontextprotocol/server/stdio';
import type { Readable, Writable } from 'node:stream';
import { loadConfig } from './config.js';
import { TerminalService } from './service.js';
import { registerTerminalTools } from './tools.js';

const HELP = 'Usage: remote-terminal-mcp --config /absolute/path/to/targets.json\n';
const GENERIC_ERROR = 'Remote terminal MCP could not start.';

export type CliOptions = { configPath?: string; help: boolean };

export function parseArgs(args: string[]): CliOptions {
  let configPath: string | undefined;
  let help = false;
  for (let i = 0; i < args.length; i++) {
    const arg = args[i];
    if (arg === '--help' || arg === '-h') {
      help = true;
      continue;
    }
    if (arg === '--config' && configPath === undefined && i + 1 < args.length) {
      configPath = args[++i];
      continue;
    }
    throw new Error('Invalid command line');
  }
  if (!help && (!configPath || !isAbsolute(configPath))) throw new Error('Invalid command line');
  return { configPath, help };
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
    const config = await loadConfig(options.configPath!);
    const { SshTransport } = await import('./ssh.js');
    service = new TerminalService(config, new SshTransport());
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
