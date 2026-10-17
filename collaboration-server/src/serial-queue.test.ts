import assert from 'node:assert/strict'
import { describe, it } from 'node:test'
import { KeyedSerialQueue } from './serial-queue.js'

describe('KeyedSerialQueue', () => {
  it('serializes operations for one room and continues after rejection', async () => {
    const queue = new KeyedSerialQueue()
    const order: string[] = []
    const first = queue.run('doc:1', async () => {
      order.push('first:start')
      await new Promise((resolve) => setTimeout(resolve, 5))
      order.push('first:end')
      throw new Error('expected')
    })
    const second = queue.run('doc:1', () => order.push('second'))

    await assert.rejects(first, /expected/)
    await second
    assert.deepEqual(order, ['first:start', 'first:end', 'second'])
  })

  it('allows separate rooms to progress without waiting on each other', async () => {
    const queue = new KeyedSerialQueue()
    const order: string[] = []
    const first = queue.run('doc:1', async () => {
      await new Promise((resolve) => setTimeout(resolve, 10))
      order.push('slow')
    })
    const second = queue.run('doc:2', () => order.push('fast'))

    await Promise.all([first, second])
    assert.deepEqual(order, ['fast', 'slow'])
  })
})
