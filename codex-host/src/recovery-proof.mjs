// 只在同一进程内传递；重启后旧证明自然失效。
const snapshots = new WeakMap()

export function createLocalRecoveryProof(owner, identity) {
  if (!owner || owner.processId !== process.pid || owner.hostInstanceId !== identity.hostInstanceId
      || !Number.isSafeInteger(owner.runEpoch) || owner.runEpoch < 0) throw new Error('恢复交接归属无效')
  const proof = Object.freeze({})
  snapshots.set(proof, JSON.stringify(owner))
  return proof
}

export function matchesLocalRecoveryProof(proof, owner, identity) {
  return !!proof && typeof proof === 'object' && owner?.processId === process.pid
    && owner.hostInstanceId === identity.hostInstanceId && owner.runEpoch >= 0
    && snapshots.get(proof) === JSON.stringify(owner)
}

const attemptSnapshots = new WeakMap()

export function createLocalAttemptProof(owner, record, identity) {
  if (!owner || !record || owner.processId !== process.pid || owner.hostInstanceId !== identity.hostInstanceId
      || owner.batchId !== record.batchId || owner.runEpoch !== record.runEpoch
      || owner.hostInstanceId !== record.hostInstanceId || owner.processId !== record.processId)
    throw new Error('尝试恢复交接归属无效')
  const proof = Object.freeze({})
  attemptSnapshots.set(proof, JSON.stringify({ owner, record }))
  return proof
}

export function matchesLocalAttemptProof(proof, owner, record, identity) {
  return !!proof && typeof proof === 'object' && owner?.processId === process.pid
    && owner.hostInstanceId === identity.hostInstanceId
    && attemptSnapshots.get(proof) === JSON.stringify({ owner, record })
}
