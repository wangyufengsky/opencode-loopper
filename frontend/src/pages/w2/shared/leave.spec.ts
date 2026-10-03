import { describe, expect, it, vi } from 'vitest'
import { createRouteLeaveCoordinator } from './leave'
import type { LeaveDecision } from '@/foundation/contracts/types'
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }

describe('W2 existing-router leave gate', () => {
  it('pending and unknown block immediately without calling dirty confirmation or navigating', async () => {
    const confirm = vi.fn(), gate = createRouteLeaveCoordinator(confirm)
    gate.register(() => ({ kind: 'BLOCK', reason: '保留原操作', recoveryAction: '核对原操作' }))
    expect(await gate.allow({ destination: '/tasks' })).toBe(false)
    expect(confirm).not.toHaveBeenCalled()
  })
  it('dirty defaults to explicit confirmation; a newer pending operation overrides an open dialog', async () => {
    const reply = deferred<boolean>(), confirm = vi.fn(() => reply.promise)
    let policy: LeaveDecision = { kind: 'CONFIRM_DISCARD', description: '草稿', draftRevision: 1 }
    const gate = createRouteLeaveCoordinator(confirm); gate.register(() => policy)
    const navigation = gate.allow({ destination: '/tasks' }); await Promise.resolve()
    policy = { kind: 'BLOCK', reason: '正在提交', recoveryAction: '等待原回执' }; reply.resolve(true)
    expect(await navigation).toBe(false)
  })
  it('opposing draft revisions cannot collide by summation and authorize discarding newer drafts', async () => {
    const reply = deferred<boolean>(), gate = createRouteLeaveCoordinator(() => reply.promise)
    let a = 1, b = 3
    gate.register(() => ({ kind: 'CONFIRM_DISCARD', description: 'A', draftRevision: a }))
    gate.register(() => ({ kind: 'CONFIRM_DISCARD', description: 'B', draftRevision: b }))
    const navigation = gate.allow({ destination: '/tasks' }); await Promise.resolve()
    a = 2; b = 2; reply.resolve(true)
    expect(await navigation).toBe(false)
  })
  it('replacement of a dirty owner and actual disposal invalidate the old confirmation', async () => {
    const reply = deferred<boolean>(), gate = createRouteLeaveCoordinator(() => reply.promise)
    const stop = gate.register(() => ({ kind: 'CONFIRM_DISCARD', description: '旧草稿', draftRevision: 1 }))
    const navigation = gate.allow({ destination: '/tasks' }); await Promise.resolve()
    stop(); gate.register(() => ({ kind: 'CONFIRM_DISCARD', description: '新草稿', draftRevision: 1 })); reply.resolve(true)
    expect(await navigation).toBe(false)
    const secondReply = deferred<boolean>(), second = createRouteLeaveCoordinator(() => secondReply.promise)
    second.register(() => ({ kind: 'CONFIRM_DISCARD', description: '草稿', draftRevision: 1 }))
    const next = second.allow({ destination: '/projects' }); await Promise.resolve(); second.dispose(); secondReply.resolve(true)
    expect(await next).toBe(false); expect(await second.allow({ destination: '/' })).toBe(false)
  })
  it('does not queue another destination behind a modal; declined confirmation preserves the owner', async () => {
    const reply = deferred<boolean>(), confirm = vi.fn(() => reply.promise), gate = createRouteLeaveCoordinator(confirm)
    gate.register(() => ({ kind: 'CONFIRM_DISCARD', description: '草稿', draftRevision: 1 }))
    const first = gate.allow({ destination: '/tasks' })
    expect(gate.allow({ destination: '/tasks' })).toBe(first)
    expect(await gate.allow({ destination: '/projects' })).toBe(false)
    reply.resolve(false); expect(await first).toBe(false); expect(confirm).toHaveBeenCalledTimes(1)
  })
})
