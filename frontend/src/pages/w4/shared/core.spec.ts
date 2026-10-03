import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { createW4Owner, definitiveW4Rejection } from './core'

const deferred = <T,>() => { let resolve!: (value: T) => void, reject!: (cause: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
afterEach(() => { vi.useRealTimers() })
describe('W4 retained command and view resources', () => {
  it('Strict view replacement invalidates old channel reads and releases its timer immediately', () => {
    vi.useFakeTimers()
    const owner = createW4Owner('test', 'A', { count: 0 }), first = owner.base.attachView(), old = owner.ticket('detail')
    owner.delay(() => owner.patch({ count: 1 }), 180)
    expect(vi.getTimerCount()).toBe(1)
    first(); expect(vi.getTimerCount()).toBe(0); expect(old.current()).toBe(false)
    const second = owner.base.attachView(), current = owner.ticket('detail')
    expect(current.current()).toBe(true); expect(old.current()).toBe(false)
    owner.base.retire(true); second(); expect(current.current()).toBe(false); expect(vi.getTimerCount()).toBe(0)
  })
  it('pending and unknown refuse another command and guarded retirement keeps exact original body/File', async () => {
    const owner = createW4Owner('test', 'A', { count: 0 }), source = deferred<{ id: string }>(), file = new File(['original'], 'input.txt'), write = vi.fn(() => source.promise)
    const operation = owner.command({ label: '原操作', input: { endpoint: '/original', method: 'POST', body: { id: 'A', version: 7 }, files: [file] }, capability: { kind: 'NONE' }, write, read: async () => {}, changed: () => {} })
    const sent = operation.execute()
    expect(owner.canStartWrite()).toBe(false); expect(owner.base.retire()).toMatchObject({ kind: 'BLOCK' })
    source.reject(new Error('lost receipt')); await expect(sent).rejects.toThrow('lost receipt')
    expect(operation.getSnapshot().phase).toBe('UNKNOWN'); expect(owner.base.capture().isCurrent()).toBe(true)
    expect(owner.canStartWrite()).toBe(false); expect(operation.identity.files[0]).toBe(file); expect(operation.identity.body).toEqual({ id: 'A', version: 7 })
    await expect(operation.recoverWrite()).rejects.toThrow(); expect(write).toHaveBeenCalledTimes(1)
    owner.base.retire(true)
  })
  it('accepted readback failure recovers by GET only and late forced-root replies do not project', async () => {
    const owner = createW4Owner('test', 'A', { count: 0 }), write = vi.fn(async () => ({ id: 'A' })), read = vi.fn(async (_receipt, context) => { if (read.mock.calls.length===1) throw new Error('read failed'); context.apply(() => owner.patch({ count: 1 })) })
    const operation = owner.command({ label: '原操作', input: { endpoint: '/original', method: 'POST', body: { id: 'A' } }, capability: { kind: 'NONE' }, write, read, changed: () => {} })
    await expect(operation.execute()).rejects.toThrow('read failed'); expect(operation.getSnapshot().phase).toBe('ACCEPTED_READBACK'); expect(owner.base.canLeave().kind).toBe('BLOCK')
    await operation.retryReadback(); expect(write).toHaveBeenCalledTimes(1); expect(read).toHaveBeenCalledTimes(2); expect(owner.base.getSnapshot().count).toBe(1)
    const late = deferred<{ id: string }>(), lateRead = vi.fn(async () => { owner.patch({ count: 9 }) })
    const pending = owner.command({ label: '下一操作', input: { endpoint: '/next', method: 'POST', body: { id: 'A' } }, capability: { kind: 'NONE' }, write: () => late.promise, read: lateRead, changed: () => {} }).execute()
    owner.base.retire(true); late.resolve({ id: 'A' }); await pending
    expect(lateRead).not.toHaveBeenCalled(); expect(owner.base.getSnapshot().count).toBe(1)
  })
  it('a dirty submit is explicit but sibling write-gate rejection emits no transport command', () => {
    const owner = createW4Owner('test', 'A', { dirty: true }, () => ({ kind: 'CONFIRM_DISCARD', description: '原草稿', draftRevision: 1 }))
    expect(owner.canStartWrite()).toBe(true)
    owner.setWriteGate(() => false)
    const write = vi.fn()
    expect(() => owner.command({ label: '原操作', input: { endpoint: '/original', method: 'POST', body: {} }, capability: { kind: 'NONE' }, write, read: async () => {}, changed: () => {} })).toThrow()
    expect(write).not.toHaveBeenCalled(); owner.base.retire(true)
  })
  it('409 and missing records cannot generically discard the unknown original identity', () => {
    expect(definitiveW4Rejection(new ApiError('版本冲突', 409, { code: 'CONFLICT' }))).toBe(false)
    expect(definitiveW4Rejection(new ApiError('未找到', 404, { code: 'NOT_FOUND' }))).toBe(false)
    expect(definitiveW4Rejection(new Error('transport'))).toBe(false)
  })
})
