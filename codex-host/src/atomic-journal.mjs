import { mkdir, open, rename, unlink } from 'node:fs/promises'
import { randomUUID } from 'node:crypto'
import path from 'node:path'

export async function syncDirectory(directory) {
  const handle = await open(directory, 'r')
  try { await handle.sync() } finally { await handle.close() }
}

/** 在网络请求或启动进程前，将恢复事实落盘。 */
export async function ensureJournalDirectory(directory, anchor) {
  if (!path.isAbsolute(anchor) || !path.isAbsolute(directory)) throw new Error('日志路径无效')
  const relative = path.relative(anchor, directory)
  if (relative === '..' || relative.startsWith(`..${path.sep}`) || path.isAbsolute(relative))
    throw new Error('日志路径越界')
  await mkdir(directory, { recursive: true, mode: 0o700 })
  // 每个调用都同步目录链，避免并发创建者尚未同步父目录的窗口。
  let current = directory
  while (current !== anchor) {
    await syncDirectory(path.dirname(current))
    current = path.dirname(current)
  }
}

export async function writeExclusiveJournalFile(filename, value, anchor) {
  const directory = path.dirname(filename)
  await ensureJournalDirectory(directory, anchor)
  const handle = await open(filename, 'wx', 0o600)
  try { await handle.writeFile(JSON.stringify(value)); await handle.sync() } finally { await handle.close() }
  await syncDirectory(directory)
}

export async function writeJournalFile(filename, value, anchor) {
  const directory = path.dirname(filename)
  await ensureJournalDirectory(directory, anchor)
  const temporary = path.join(directory, `.journal-${randomUUID()}.tmp`)
  try {
    const handle = await open(temporary, 'wx', 0o600)
    try { await handle.writeFile(JSON.stringify(value)); await handle.sync() } finally { await handle.close() }
    await rename(temporary, filename)
    await syncDirectory(directory)
  } finally { await unlink(temporary).catch(() => {}) }
}
