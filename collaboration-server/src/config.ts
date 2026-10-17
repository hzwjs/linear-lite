import type { CollaborationConfig } from './types.js'

function numberFromEnvironment(name: string, fallback: number, min: number, max: number): number {
  const raw = process.env[name]
  if (raw === undefined) return fallback
  const parsed = Number(raw)
  if (!Number.isSafeInteger(parsed) || parsed < min || parsed > max) {
    throw new Error(`${name} must be an integer from ${min} to ${max}`)
  }
  return parsed
}

export function readConfig(): CollaborationConfig {
  // Hocuspocus rooms and their locks are process-local; run one Node instance for all collaboration rooms.
  const internalKey = process.env.COLLABORATION_INTERNAL_KEY?.trim()
  if (!internalKey) throw new Error('COLLABORATION_INTERNAL_KEY is required')
  return {
    host: process.env.COLLABORATION_HOST?.trim() || '127.0.0.1',
    port: numberFromEnvironment('COLLABORATION_PORT', 9091, 1, 65535),
    javaBaseUrl: process.env.COLLABORATION_JAVA_URL?.trim() || 'http://127.0.0.1:8080',
    internalKey,
    blocknoteMaxBytes: numberFromEnvironment('COLLABORATION_BLOCKNOTE_MAX_BYTES', 10 * 1024 * 1024, 1, 100 * 1024 * 1024),
    htmlMaxBytes: numberFromEnvironment('COLLABORATION_HTML_MAX_BYTES', 5 * 1024 * 1024, 1, 100 * 1024 * 1024),
    requestTimeoutMs: numberFromEnvironment('COLLABORATION_JAVA_TIMEOUT_MS', 5000, 100, 60000),
    persistenceDebounceMs: numberFromEnvironment('COLLABORATION_PERSISTENCE_DEBOUNCE_MS', 250, 0, 10000),
  }
}
