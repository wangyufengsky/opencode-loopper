import { afterEach, describe, expect, it, vi } from 'vitest'
import { createTaskEventSubscription } from './taskEventSubscription'
import { OwnedResourceCleanupError } from '@/foundation/contracts/resource'
import { subscribeTaskEvents } from '@/api/client'
import type { TaskEvent } from '@/types/domain'
vi.mock('@/api/client', () => ({ subscribeTaskEvents: vi.fn() }))
afterEach(() => { vi.useRealTimers(); vi.clearAllMocks() })

describe('Task subscription cleanup failure isolation', () => {
  it('close failure still clears both coalescing timers and invalidates every old callback', () => {
    vi.useFakeTimers()
    let event!: (value: TaskEvent) => void, streamState!: (value: 'connected' | 'reconnecting') => void
    const close = vi.fn(() => { throw new Error('close failed') }), receive = vi.fn(), overview = vi.fn(async () => {}), audit = vi.fn(async () => {}), state = vi.fn()
    vi.mocked(subscribeTaskEvents).mockImplementation((_id, next, changed) => { event=next; streamState=changed; return { close } })
    const subscription = createTaskEventSubscription({ receive: (_id,value) => receive(value), overview, audit, state, error: vi.fn(), needsOverview: () => true })
    subscription.watch('A'); event({ id: 'cursor-17', type: 'attempt.created', at: '', data: {} })
    expect(vi.getTimerCount()).toBe(2)
    expect(() => subscription.stop()).toThrow(OwnedResourceCleanupError)
    expect(vi.getTimerCount()).toBe(0); expect(state).toHaveBeenLastCalledWith('idle')
    event({ id: 'cursor-18', type: 'task.status', at: '', data: { status: 'CANCELLED' } }); streamState('connected'); vi.runAllTimers()
    expect(receive).toHaveBeenCalledTimes(1); expect(overview).not.toHaveBeenCalled(); expect(audit).not.toHaveBeenCalled(); expect(state).toHaveBeenLastCalledWith('idle')
    subscription.stop(); expect(close).toHaveBeenCalledTimes(1)
  })
})
