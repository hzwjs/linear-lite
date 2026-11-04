const TIMEOUT_MS = 10_000;

export class HttpError extends Error {
  constructor(readonly status: number) { super(`HTTP ${status}`); }
}

/** Redirects are refused: a redirect could move a bearer token to another host. */
export function request(url: string, init: RequestInit = {}): Promise<Response> {
  return fetch(url, { ...init, signal: AbortSignal.timeout(TIMEOUT_MS), redirect: 'error' });
}

export async function requestJson(url: string, init: RequestInit = {}): Promise<unknown> {
  const response = await request(url, { ...init, headers: { accept: 'application/json', ...init.headers } });
  if (!response.ok) throw new HttpError(response.status);
  return response.json();
}
