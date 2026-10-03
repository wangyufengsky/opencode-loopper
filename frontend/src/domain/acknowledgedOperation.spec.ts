import { describe, expect, it, vi } from 'vitest'
import { createAcknowledgedOperation } from './acknowledgedOperation'

describe('acknowledged command contract shared by UI adapters', () => {
  it('retries only the read after the write receipt was accepted', async () => {
    const write = vi.fn().mockResolvedValue({ revision: 4 })
    const read = vi.fn().mockRejectedValueOnce(new Error('read lost')).mockResolvedValue(undefined)
    const operation = createAcknowledgedOperation('保存', write, read, () => true)
    await expect(operation.execute()).rejects.toThrow('read lost')
    expect(operation.accepted).toBe(true)
    await operation.execute()
    expect(write).toHaveBeenCalledTimes(1)
    expect(read).toHaveBeenNthCalledWith(2, { revision: 4 })
  })
  it('replays the original write closure when its response is unknown', async () => {
    const body = { requestKey: 'fixed-key', expectedVersion: 3, text: '原始输入' }
    const send = vi.fn().mockRejectedValueOnce(new Error('unknown')).mockResolvedValue({ version: 4 })
    const operation = createAcknowledgedOperation('提交', () => send(body), vi.fn(), () => true)
    await expect(operation.execute()).rejects.toThrow('unknown')
    expect(operation.accepted).toBe(false)
    await operation.execute()
    expect(send.mock.calls[1]![0]).toBe(send.mock.calls[0]![0])
  })
  it('coalesces repeated calls while the same command is in flight', async () => {
    let resolve!: (value: number) => void
    const write = vi.fn(() => new Promise<number>(done => { resolve = done }))
    const read = vi.fn()
    const operation = createAcknowledgedOperation('提交', write, read, () => true)
    const first = operation.execute(), second = operation.execute()
    expect(first).toBe(second)
    resolve(1); await first
    expect(write).toHaveBeenCalledTimes(1); expect(read).toHaveBeenCalledTimes(1)
  })
  it('retains an accepted receipt without applying a late result after disposal', async () => {
    let active = true, resolve!: (value: number) => void
    const write = vi.fn(() => new Promise<number>(done => { resolve = done })), read = vi.fn()
    const operation = createAcknowledgedOperation('提交', write, read, () => active)
    const pending = operation.execute(); active = false; resolve(1); await pending
    expect(operation.accepted).toBe(true); expect(read).not.toHaveBeenCalled()
    await operation.execute(); expect(write).toHaveBeenCalledTimes(1)
  })
  it('allows an already started read to reject its result after its scope expires', async () => {
    let active = true, resolve!: (value: number) => void
    const write = vi.fn().mockResolvedValue({ revision: 4 }), apply = vi.fn()
    const operation = createAcknowledgedOperation('保存', write, async () => {
      const value = await new Promise<number>(done => { resolve = done })
      if (operation.isActive()) apply(value)
    }, () => active)
    const work = operation.execute(); await Promise.resolve()
    active = false; resolve(4); await work; await operation.execute()
    expect(operation.accepted).toBe(true); expect(apply).not.toHaveBeenCalled(); expect(write).toHaveBeenCalledOnce()
  })
})
