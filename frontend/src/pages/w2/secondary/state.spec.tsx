import { cleanup, renderHook, act, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/api/client'
import { createSecondaryMutationOwner, useLatestRead } from './state'
import { confirmsDatabase, confirmsPolicy } from './recovery'
import type { DatabaseConnection } from '@/types/domain'
const deferred = <T,>() => { let resolve!: (value: T) => void; let reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
afterEach(() => cleanup())
describe('secondary read and mutation scopes', () => {
  it('rejects an old read and append projection after a newer read', async () => {
    const first = deferred<string[]>(), second = deferred<string[]>(), load = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const view = renderHook(() => useLatestRead(load, [] as string[]))
    let old!: Promise<string[] | undefined>, current!: Promise<string[] | undefined>
    act(() => { old = view.result.current.reload(); current = view.result.current.reload() })
    await act(async () => { second.resolve(['B']); await current }); await act(async () => { first.resolve(['A']); await old })
    expect(view.result.current.value).toEqual(['B']); expect(await old).toBeUndefined()
  })
  it('does not read or project after view cleanup', async () => {
    const result = deferred<string>(), load = vi.fn(() => result.promise), view = renderHook(() => useLatestRead(load, ''))
    let pending!: Promise<string | undefined>; act(() => { pending = view.result.current.reload() }); const reload = view.result.current.reload
    view.unmount(); result.resolve('late'); expect(await pending).toBeUndefined(); expect(await reload()).toBeUndefined(); expect(load).toHaveBeenCalledTimes(1)
  })
  it('keeps keyless UNKNOWN blocked and recovers through a verified GET without a second write', async () => {
    const owner = createSecondaryMutationOwner('/databases'), write = vi.fn().mockRejectedValue(new Error('timeout')), lookup = vi.fn().mockResolvedValue({ kind: 'UNCONFIRMED' }), read = vi.fn()
    await owner.run('/database-connections/db-1', { version: 4 }, write, read, 'PUT', { kind: 'READ_ORIGINAL', readOriginal: lookup })
    expect(owner.getSnapshot().phase).toBe('UNKNOWN'); expect(owner.canLeave().kind).toBe('BLOCK')
    await owner.recover(); expect(owner.canLeave().kind).toBe('BLOCK'); expect(write).toHaveBeenCalledTimes(1); expect(read).not.toHaveBeenCalled()
    lookup.mockResolvedValue({ kind: 'ACCEPTED', receipt: { version: 5 } }); await owner.recover()
    expect(owner.getSnapshot().phase).toBe('SETTLED'); expect(write).toHaveBeenCalledTimes(1); expect(read).toHaveBeenCalledTimes(1)
  })
  it('accepted readback retries only its GET and refuses late projections after retirement', async () => {
    const owner = createSecondaryMutationOwner('/tools'), response = deferred<void>(), project = vi.fn(), write = vi.fn().mockResolvedValue({ version: 2 })
    const pending = owner.run('/runtime/tool-policies', { version: 1 }, write, async (_receipt, context) => { await response.promise; context.apply(project) })
    await waitFor(() => expect(owner.getSnapshot().phase).toBe('ACCEPTED_READBACK')); owner.retire(); response.resolve(); await pending
    expect(project).not.toHaveBeenCalled(); expect(write).toHaveBeenCalledTimes(1)
  })
  it('keeps a 409 draft available and does not reissue writes on recovery', async () => {
    const owner = createSecondaryMutationOwner('/tools'), write = vi.fn().mockRejectedValue(new ApiError('已更新', 409))
    await owner.run('/runtime/tool-policies', { version: 2 }, write)
    expect(owner.getSnapshot().phase).toBe('SETTLED'); expect(owner.canLeave().kind).toBe('ALLOW'); await owner.recover(); expect(write).toHaveBeenCalledTimes(1)
  })
  it('requires the exact policy CAS transition and complete current catalog', () => {
    const row = { name: 'read', configurable: true, writes: false, globalEnabled: false, enabled: false, projectOverride: 'DISABLED' as const, source: 'PROJECT' as const, globalVersion: 3, projectVersion: 0 }
    expect(confirmsPolicy({ tools: [row], complete: true, detail: '' }, 'p', [{ name: 'read', version: -1, enabled: 0 }]).kind).toBe('ACCEPTED')
    expect(confirmsPolicy({ tools: [row], complete: false, detail: '' }, 'p', [{ name: 'read', version: -1, enabled: 0 }]).kind).toBe('UNCONFIRMED')
    expect(confirmsPolicy({ tools: [{ ...row, projectVersion: 2 }], complete: true, detail: '' }, 'p', [{ name: 'read', version: -1, enabled: 0 }]).kind).toBe('UNCONFIRMED')
  })
  it('cannot confirm an unreadable password from a public database DTO', () => {
    const config = { type: 'MYSQL' as const, jdbcUrl: 'jdbc:mysql://db/data', host: '', port: 3306, database: '', username: 'r', driverFile: '', driverClass: '', schemas: ['data'], parameters: {}, timeoutSeconds: 10, maxRows: 20 }
    const row: DatabaseConnection = { id: 'db', name: '数据库', config, passwordConfigured: true, enabled: true, archived: false, projectIds: ['p'], version: 4, createdAt: '' }
    const body = { name: row.name, config, enabled: true, archived: false, projectIds: ['p'], version: 3, password: null }
    expect(confirmsDatabase(row, body)).toBe(true); expect(confirmsDatabase(row, { ...body, password: 'new-secret' })).toBe(false)
  })
})
