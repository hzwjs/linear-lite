/** An error whose message is written for the Agent and is safe to return as-is. */
export class ControlledError extends Error {}

/** The cloud service could not be reached or answered with a server-side failure. */
export class CloudUnavailableError extends ControlledError {
  constructor() { super('Cloud service unavailable'); }
}

export function notSignedIn(server: string): ControlledError {
  return new ControlledError(`Not signed in; run: remote-terminal-mcp login --server ${server}`);
}
