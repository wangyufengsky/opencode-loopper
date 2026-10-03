import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTaskStore } from '@/stores/taskStore'
import { api } from '@/api/client'
import type { Project, RuntimeInfo } from '@/types/domain'
import { createW2TaskPort } from './w2TaskPort'
const deferred = <T>() => { let resolve!: (value: T) => void; const promise = new Promise<T>(r => { resolve = r }); return { promise, resolve } }
const project = (id: string) => ({ id, name: id, rootPath: `/workspace/${id}`, status: 'READY', createdAt: '2026-10-03', updatedAt: '2026-10-03', taskCount: 0, openDesignerSessionCount: 0 }) as Project
beforeEach(() => { setActivePinia(createPinia()); vi.restoreAllMocks() })

describe('W2 single legacy owner boundary', () => {
  it('supplies stable immutable snapshots and precisely stops its subscription', () => {
    const store = useTaskStore(), boundary = createW2TaskPort(store), listener = vi.fn()
    const stop = boundary.port.subscribe(listener), first = boundary.port.getSnapshot()
    expect(boundary.port.getSnapshot()).toBe(first); expect(Object.isFrozen(first.projects)).toBe(true)
    store.projects = [project('A')]; expect(listener).toHaveBeenCalled(); expect(boundary.port.getSnapshot().projects[0]?.id).toBe('A')
    boundary.dispose(); listener.mockClear(); store.projects = [project('B')]
    expect(listener).not.toHaveBeenCalled(); expect(boundary.port.getSnapshot().projects[0]?.id).toBe('A'); stop(); boundary.dispose()
  })
  it('an older project response cannot replace a newer query or another route instance', async () => {
    const old = deferred<Project[]>(), store = useTaskStore(), first = createW2TaskPort(store)
    vi.spyOn(api, 'getProjects').mockReturnValueOnce(old.promise).mockResolvedValue([project('B')])
    const request = first.port.loadProjects(); await first.port.loadProjects(true)
    old.resolve([project('A')]); await request; expect(store.projects[0]?.id).toBe('B')
    const late = deferred<Project[]>(); vi.mocked(api.getProjects).mockReturnValueOnce(late.promise)
    const pending = first.port.loadProjects(); first.dispose(); const next = createW2TaskPort(store)
    store.projects = [project('C')]; late.resolve([project('retired')]); await pending
    expect(next.port.getSnapshot().projects[0]?.id).toBe('C'); next.dispose()
  })
  it('runtime readback is scoped and original API write failure propagates to receipt ownership', async () => {
    const late = deferred<RuntimeInfo>(), store = useTaskStore(), boundary = createW2TaskPort(store)
    const value = { status: 'ONLINE', loopperVersion: 'service-version' } as RuntimeInfo
    vi.spyOn(api, 'getRuntime').mockReturnValue(late.promise)
    const pending = boundary.port.refreshRuntime(); boundary.dispose(); store.runtime = value; late.resolve({ ...value, loopperVersion: 'old' }); await pending
    expect(store.runtime?.loopperVersion).toBe('service-version')
    const live = createW2TaskPort(store); vi.spyOn(api, 'startRuntime').mockRejectedValue(new Error('回执未知'))
    await expect(live.port.startRuntime()).rejects.toThrow('回执未知'); live.dispose()
  })
  it('disposes only task summary reads actually acquired by this boundary', async () => {
    const store = useTaskStore(), invalidate = vi.spyOn(store, 'invalidateTaskSummaries')
    const unrelated = createW2TaskPort(store); unrelated.dispose(); expect(invalidate).not.toHaveBeenCalled()
    vi.spyOn(store, 'loadTaskSummaries').mockResolvedValue()
    const summary = createW2TaskPort(store); await summary.port.loadTaskSummaries(); summary.dispose(); summary.dispose()
    expect(invalidate).toHaveBeenCalledTimes(1)
  })
  it('keeps cached project rows and displays current read errors, while retired errors cannot pollute the next page', async () => {
    const store = useTaskStore(); store.projects = [project('cached')]
    const boundary = createW2TaskPort(store), read = vi.spyOn(api, 'getProjects').mockRejectedValueOnce(new Error('项目暂时不可读'))
    expect(await boundary.port.loadProjects()).toEqual([project('cached')]); expect(store.error).toBe('项目暂时不可读')
    read.mockResolvedValueOnce([project('latest')]); await boundary.port.loadProjects(); expect(store.error).toBeUndefined()
    let reject!: (failure: unknown) => void
    read.mockReturnValueOnce(new Promise((_, fail) => { reject = fail }))
    const old = boundary.port.loadProjects(); boundary.dispose(); store.error = '新页当前错误'
    reject(new Error('旧页迟到错误')); await old; expect(store.error).toBe('新页当前错误'); expect(store.projects[0]?.id).toBe('latest')
  })
})
