import { afterEach, describe, expect, it, vi } from 'vitest'
import { mount, type VueWrapper } from '@vue/test-utils'
import { useWorkflowCommand } from './command'
import { ApiError } from '@/api/client'
let wrapper: VueWrapper | undefined
afterEach(() => wrapper?.unmount())
function command() { let value!: ReturnType<typeof useWorkflowCommand>; wrapper = mount({ setup() { value = useWorkflowCommand(); return () => null } }); return value }
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
})
