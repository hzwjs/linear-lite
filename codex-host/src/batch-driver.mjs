import { setTimeout as delay } from 'node:timers/promises'
import { runPool } from './pool.mjs'
import { listBatchRuns, readBatchRun } from './batch-journal.mjs'
import { recoverBatch } from './recovery.mjs'

const POLL_INTERVAL_MS = 5000

/** 宿主轮询持久待领取批次；单批次只启动一个执行池。 */
export class BatchDriver {
  constructor({ client, identity, stateDirectory, vault, pool = runPool, intervalMs = POLL_INTERVAL_MS }) {
    this.client = client; this.identity = identity; this.stateDirectory = stateDirectory; this.vault = vault
    this.pool = pool; this.intervalMs = intervalMs
    this.active = new Map(); this.blocked = new Map(); this.recoveries = new Map(); this.recovering = new Map()
    this.abort = new AbortController(); this.loop = null; this.polling = null
  }

  start() {
    if (!this.loop && !this.abort.signal.aborted) this.loop = this.run()
    return this.loop
  }

  async run() {
    while (!this.abort.signal.aborted) {
      try { await this.poll() } catch { /* 离线及失效授权等待下一轮，不推断执行停止。 */ }
      try { await delay(this.intervalMs, undefined, { signal: this.abort.signal }) } catch { break }
    }
  }

  async poll() {
    if (this.polling) return this.polling
    const work = this.pollOnce()
    this.polling = work
    try { return await work } finally { if (this.polling === work) this.polling = null }
  }

  async pollOnce() {
    // 先处理可确认的本机批次记录。
    const owners = await listBatchRuns({ stateDirectory: this.stateDirectory, identity: this.identity })
    for (const owner of owners) {
      if (this.abort.signal.aborted) return
      if (this.active.has(owner.batchId)) continue
      const localProof = this.recoveries.get(owner.batchId)
      if (owner.hostInstanceId === this.identity.hostInstanceId && !localProof) continue
      if (this.recovering.has(owner.batchId)) continue
      const work = (async () => {
        try {
          await recoverBatch({ batchId: owner.batchId, stateDirectory: this.stateDirectory, identity: this.identity,
            client: this.client, vault: this.vault, localProof })
          this.recoveries.delete(owner.batchId)
          this.blocked.delete(owner.batchId)
        } catch { /* 未确认的批次记录保留。 */ }
      })()
      this.recovering.set(owner.batchId, work)
      void work.finally(() => {
        if (this.recovering.get(owner.batchId) === work) this.recovering.delete(owner.batchId)
      })
    }
    const response = await this.client.call('batches/pending', {}, { signal: this.abort.signal })
    if (!Array.isArray(response.result?.items)) throw new Error('待领取批次响应无效')
    for (const item of response.result.items) {
      if (this.abort.signal.aborted) break
      if (!item || !Number.isSafeInteger(item.projectId) || item.projectId <= 0
          || typeof item.batchId !== 'string' || !item.batchId || item.batchId.length > 36
          || item.deviceId !== this.identity.deviceId || typeof item.projectDirectory !== 'string'
          || item.lifecycle !== 'queued' || item.desiredIntent !== 'RUN') throw new Error('待领取批次归属无效')
      if (this.active.has(item.batchId) || this.recovering.has(item.batchId)) continue
      if (this.blocked.has(item.batchId)) {
        // 未确认停止的批次保持等待。
        try {
          if (await readBatchRun({ batchId: item.batchId, stateDirectory: this.stateDirectory })) continue
          this.blocked.delete(item.batchId)
        } catch { continue }
      }
      if (this.abort.signal.aborted) break
      const controller = new AbortController()
      const entry = { projectDirectory: item.projectDirectory, controller, finished: null, proof: null }
      this.active.set(item.batchId, entry)
      entry.finished = this.execute(item, entry).finally(() => {
        this.active.delete(item.batchId)
        if (entry.proof) this.recoveries.set(item.batchId, entry.proof)
      })
    }
  }

  async execute(item, entry) {
    try {
      const result = await this.pool({ client: this.client, identity: this.identity, stateDirectory: this.stateDirectory,
        vault: this.vault, projectId: item.projectId, batchId: item.batchId, signal: entry.controller.signal,
        onQuiescent: proof => { entry.proof = proof } })
      if (result?.state === 'RECOVERY_REQUIRED') this.blocked.set(item.batchId, item.projectDirectory)
    } catch {
      // 接受结果不明时保留该批次记录。
      try {
        if (await readBatchRun({ batchId: item.batchId, stateDirectory: this.stateDirectory }))
          this.blocked.set(item.batchId, item.projectDirectory)
      } catch { this.blocked.set(item.batchId, item.projectDirectory) }
    }
  }

  async close() {
    this.abort.abort()
    for (const entry of this.active.values()) entry.controller.abort()
    await this.loop
    await Promise.allSettled([...this.active.values()].map(entry => entry.finished))
    await Promise.allSettled([...this.recovering.values()])
  }
}
