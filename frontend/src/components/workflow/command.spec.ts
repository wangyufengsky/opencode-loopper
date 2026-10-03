import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useWorkflowCommand } from './command'
import { ApiError } from '@/api/client'
let wrapper: VueWrapper | undefined
afterEach(() => wrapper?.unmount())
function command(identity?: () => unknown) { let value!: ReturnType<typeof useWorkflowCommand>; wrapper = mount({ setup() { value = useWorkflowCommand(identity); return () => null } }); return value }
function deferred<T>() {
  let resolve!: (value: T) => void, reject!: (reason: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
describe('workflow command receipts', () => {
  it('retries an unknown response without replacing its command and locks new commands', async () => {
    const value = command(), execute = vi.fn().mockRejectedValueOnce(new Error('timeout')).mockResolvedValue({ id: 'first' }), after = vi.fn().mockResolvedValue(undefined), other = vi.fn()
    await value.submit('创建', execute, after); expect(value.locked.value).toBe(true)
    await value.submit('另一次', other, after); expect(other).not.toHaveBeenCalled()
    await value.retry(); expect(execute).toHaveBeenCalledTimes(2); expect(after).toHaveBeenCalledWith({ id: 'first' }); expect(value.locked.value).toBe(false)
  })
  it('only rereads after an accepted command whose refresh failed', async () => {
    const value = command(), execute = vi.fn().mockResolvedValue({ id: 'receipt' }), after = vi.fn().mockRejectedValueOnce(new Error('offline')).mockResolvedValue(undefined)
    await value.submit('执行', execute, after); expect(value.pending.value?.accepted).toBe(true)
    await value.retry(); expect(execute).toHaveBeenCalledTimes(1); expect(after).toHaveBeenCalledTimes(2); expect(value.pending.value).toBeNull()
  })
  it('unlocks on deterministic rejection and suppresses callbacks after leaving', async () => {
    const value = command(), after = vi.fn(); await value.submit('执行', async () => { throw new ApiError('计划已变化', 409) }, after); expect(value.locked.value).toBe(false)
    let resolve!: (value: string) => void; const work = value.submit('执行', () => new Promise<string>(r => { resolve = r }), after); wrapper!.unmount(); wrapper = undefined; resolve('done'); await work; expect(after).not.toHaveBeenCalled()
  })
  it('keeps the new command locked when an invalidated write finishes late', async () => {
    const value = command(), first = deferred<string>(), second = deferred<string>(), firstAfter = vi.fn(), secondAfter = vi.fn()
    const oldWork = value.submit('旧需求', () => first.promise, firstAfter), oldOperation = value.pending.value!
    value.invalidate()
    const newWork = value.submit('新需求', () => second.promise, secondAfter), newOperation = value.pending.value!
    first.resolve('accepted'); await oldWork
    expect(oldOperation.accepted).toBe(true); expect(firstAfter).not.toHaveBeenCalled()
    expect(value.pending.value).toBe(newOperation); expect(value.busy.value).toBe(true); expect(value.locked.value).toBe(true)
    second.resolve('new receipt'); await newWork
    expect(secondAfter).toHaveBeenCalledWith('new receipt'); expect(value.locked.value).toBe(false)
  })
  it('does not replace the new error or pending command with an old rejection', async () => {
    const value = command(), first = deferred<string>()
    const oldWork = value.submit('旧需求', () => first.promise, vi.fn())
    value.invalidate()
    await value.submit('新需求', async () => { throw new Error('unknown') }, vi.fn())
    const newOperation = value.pending.value, newError = value.error.value
    first.reject(new ApiError('旧计划已变化', 409)); await oldWork
    expect(value.pending.value).toBe(newOperation); expect(value.error.value).toBe(newError); expect(value.locked.value).toBe(true)
  })
  it('rejects the previous identity before its load invalidates the command', async () => {
    let identity = 'A'
    const value = command(() => identity), first = deferred<string>(), after = vi.fn(), current = value.captureScope()
    const work = value.submit('确认 A', () => first.promise, after)
    identity = 'B'; expect(current()).toBe(false)
    first.resolve('accepted A'); await work
    expect(after).not.toHaveBeenCalled()
    value.invalidate(); expect(value.locked.value).toBe(false)
  })
  it('invalidates an accepted read without replaying its write or clearing a new read', async () => {
    const value = command(), first = deferred<void>(), second = deferred<void>(), write = vi.fn().mockResolvedValue('accepted'), after = vi.fn(() => first.promise)
    const oldWork = value.submit('旧读取', write, after)
    await Promise.resolve(); expect(value.pending.value?.accepted).toBe(true)
    value.invalidate()
    const newWork = value.submit('新读取', async () => 'new', () => second.promise), newOperation = value.pending.value
    first.reject(new Error('old read failed')); await oldWork
    expect(value.pending.value).toBe(newOperation); expect(value.busy.value).toBe(true); expect(value.error.value).toBe('')
    second.resolve(); await newWork; expect(write).toHaveBeenCalledOnce(); expect(value.locked.value).toBe(false)
  })
})
