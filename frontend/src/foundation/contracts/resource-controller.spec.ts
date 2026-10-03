import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSnapshotController } from './controller'
import { createOperationOwner } from './receipt'
import { createResourceScope, OwnedResourceCleanupError } from './resource'
import { createOwnerScope } from './scope'
import type { ReadContext } from './receipt'
import type { ResourceScope } from './types'

const identity = { domain: 'task', id: 'same-id', epoch: 1 }
const allow = () => ({ kind: 'ALLOW' as const })
afterEach(() => vi.useRealTimers())

describe('owned resources and single projection controller', () => {
  it('distinguishes identical identity values belonging to separate instances and invalidates retired tokens', () => {
    const a = createOwnerScope(identity), b = createOwnerScope(identity), token = a.capture()
    expect(a.isCurrent(token)).toBe(true); expect(b.isCurrent(token)).toBe(false)
    a.retire(); a.retire(); expect(a.isCurrent(token)).toBe(false); expect(b.isCurrent(b.capture())).toBe(true)
  })

  it('releases exact own listener identities and timers immediately while keeping the other instance active', () => {
    vi.useFakeTimers()
    const target = new EventTarget(), a = createResourceScope(identity), b = createResourceScope(identity), first = vi.fn(), second = vi.fn()
    target.addEventListener('change', first); a.own(() => target.removeEventListener('change', first))
    target.addEventListener('change', second); b.own(() => target.removeEventListener('change', second))
    const timeout = setTimeout(first, 100); a.own(() => clearTimeout(timeout))
    const interval = setInterval(second, 100); b.own(() => clearInterval(interval))
    a.dispose(); expect(vi.getTimerCount()).toBe(1)
    target.dispatchEvent(new Event('change')); expect(first).not.toHaveBeenCalled(); expect(second).toHaveBeenCalledOnce()
    b.dispose(); expect(vi.getTimerCount()).toBe(0)
    target.dispatchEvent(new Event('change')); expect(second).toHaveBeenCalledOnce()
  })

  it('invalidates before cleanup, releases everything despite failures, and immediately releases late-owned resources', () => {
    const resource = createResourceScope(identity), token = resource.capture(), order: string[] = [], failure = new Error('cleanup')
    resource.own(() => { order.push('first'); expect(resource.isCurrent(token)).toBe(false) })
    resource.own(() => { resource.own(() => order.push('late')); order.push('throw'); throw failure })
    let caught: unknown
    try { resource.dispose() } catch (error) { caught = error }
    expect(caught).toBeInstanceOf(OwnedResourceCleanupError)
    expect((caught as OwnedResourceCleanupError).failures).toEqual([failure])
    expect(order).toEqual(['late', 'throw', 'first']); resource.dispose(); expect(order).toHaveLength(3)
    const ownLater = vi.fn(); resource.own(ownLater)(); expect(ownLater).toHaveBeenCalledOnce()
  })

  it('returned cancellation releases rather than only unregistering; repeated disposal is idempotent', () => {
    const resource = createResourceScope(identity), dispose = vi.fn(), release = resource.own(dispose)
    release(); release(); resource.dispose(); expect(dispose).toHaveBeenCalledOnce()
  })

  it('prevents an await completed after disposal from projecting or scheduling another poll', async () => {
    vi.useFakeTimers(); const resource = createResourceScope(identity), token = resource.capture(), apply = vi.fn()
    let resolve!: () => void
    const pending = new Promise<void>(done => { resolve = done }).then(() => {
      if (!resource.isCurrent(token)) return
      apply(); resource.own(() => undefined); setTimeout(apply, 100)
    })
    resource.dispose(); resolve(); await pending
    expect(apply).not.toHaveBeenCalled(); expect(vi.getTimerCount()).toBe(0)
  })

  it('has no factory/subscription mutation, retains stable snapshots, and shares one read owner across independent leases', () => {
    const setup = vi.fn((resources: ResourceScope) => resources.own(dispose)), dispose = vi.fn(), notify = vi.fn()
    const controller = createSnapshotController({ identity, initial: { text: 'original' }, canLeave: allow, attachReads: setup })
    const snapshot = controller.getSnapshot(), unsubscribe = controller.subscribe(notify)
    expect(setup).not.toHaveBeenCalled(); expect(notify).not.toHaveBeenCalled(); expect(controller.getSnapshot()).toBe(snapshot)
    const first = controller.attachView(), second = controller.attachView()
    expect(setup).toHaveBeenCalledOnce(); expect(controller.viewCount()).toBe(2)
    first(); first(); expect(dispose).not.toHaveBeenCalled(); expect(controller.viewCount()).toBe(1)
    controller.project({ text: 'next' }); expect(controller.getSnapshot()).not.toBe(snapshot); expect(notify).toHaveBeenCalledOnce()
    second(); expect(dispose).toHaveBeenCalledOnce(); expect(controller.viewCount()).toBe(0)
    unsubscribe(); unsubscribe(); controller.project({ text: 'last' }); expect(notify).toHaveBeenCalledOnce()
  })

  it('replays read leases without duplicate writers or stale read scopes, keeping another controller intact', () => {
    const scopes: ResourceScope[] = [], disposeA = vi.fn(), disposeB = vi.fn()
    const a = createSnapshotController({ identity, initial: { value: 0 }, canLeave: allow, attachReads: resource => { scopes.push(resource); resource.own(disposeA) } })
    const b = createSnapshotController({ identity, initial: { value: 0 }, canLeave: allow, attachReads: resource => resource.own(disposeB) })
    const bLease = b.attachView()
    for (let n = 0; n < 3; n++) {
      const release = a.attachView(), resource = scopes[scopes.length - 1]!, token = resource.capture()
      expect(resource.isCurrent(token)).toBe(true); release(); release(); expect(resource.isCurrent(token)).toBe(false)
    }
    expect(disposeA).toHaveBeenCalledTimes(3); expect(disposeB).not.toHaveBeenCalled()
    bLease(); expect(disposeB).toHaveBeenCalledOnce()
  })

  it('reports both failed read setup and cleanup errors, releases the scope, and permits a later fresh lease', () => {
    const setupFailure = new Error('setup'), cleanupFailure = new Error('cleanup'), disposed = vi.fn(); let resource!: ResourceScope, first = true
    const controller = createSnapshotController({ identity, initial: {}, canLeave: allow, attachReads: scope => {
      resource = scope; scope.own(disposed)
      if (first) { first = false; scope.own(() => { throw cleanupFailure }); throw setupFailure }
    } })
    let caught: unknown
    try { controller.attachView() } catch (error) { caught = error }
    expect((caught as OwnedResourceCleanupError).failures).toEqual([setupFailure, cleanupFailure])
    expect(resource.isActive()).toBe(false); expect(controller.viewCount()).toBe(0); expect(disposed).toHaveBeenCalledOnce()
    const release = controller.attachView(); release(); expect(disposed).toHaveBeenCalledTimes(2)
  })

  it('guards ordinary dirty retirement and rejects late projections into an identical new controller', () => {
    const a = createSnapshotController({ identity, initial: { dirty: true }, canLeave: () => ({ kind: 'CONFIRM_DISCARD', description: '未保存', draftRevision: 1 }) })
    const b = createSnapshotController({ identity, initial: { dirty: false }, canLeave: allow }), token = a.capture()
    expect(a.retire().kind).toBe('CONFIRM_DISCARD'); expect(a.getSnapshot().dirty).toBe(true)
    expect(a.retire(true).kind).toBe('ALLOW'); expect(a.apply(token, { dirty: false })).toBe(false)
    expect(b.apply(token, { dirty: true })).toBe(false); expect(b.getSnapshot().dirty).toBe(false)
  })

  it('keeps an owned accepted receipt across real read-lease cleanup and forced root loss, suppressing late read application', async () => {
    const dispose = vi.fn()
    const controller = createSnapshotController({ identity, initial: { value: 'original' }, canLeave: allow,
      attachReads: resources => { resources.own(dispose) } })
    const foreign = createSnapshotController({ identity, initial: { value: 'other' }, canLeave: allow })
    let resolve!: () => void, context!: ReadContext
    const wait = new Promise<void>(done => { resolve = done })
    const operation = createOperationOwner({ owner: controller.identity, label: '保存', input: { endpoint: '/save', method: 'PUT', body: { value: 'original' } }, capability: { kind: 'NONE' },
      write: vi.fn().mockResolvedValue({ version: 2 }), read: async (_receipt, current) => { context = current; await wait; current.apply(() => controller.project({ value: 'late' })) } })
    expect(controller.ownOperation(operation)).toBe(true); expect(foreign.ownOperation(operation)).toBe(false)
    const release = controller.attachView()
    const work = operation.execute(); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(controller.retire().kind).toBe('BLOCK'); expect(context.isCurrent()).toBe(true)
    release(); expect(dispose).toHaveBeenCalledOnce(); expect(operation.identity.endpoint).toBe('/save')
    controller.retire(true); resolve(); await work
    expect(context.isCurrent()).toBe(false); expect(controller.getSnapshot().value).toBe('original')
    expect(operation.getSnapshot()).toMatchObject({ accepted: true, retired: true, receipt: { version: 2 } })
  })

  it('keeps a genuine UNKNOWN operation alive across last view detach and resumes the same identity only on explicit recovery', async () => {
    const dispose = vi.fn(), attachReads = vi.fn((resources: ResourceScope) => { resources.own(dispose) })
    const controller = createSnapshotController({ identity, initial: { value: 'original' }, canLeave: allow, attachReads })
    const write = vi.fn().mockRejectedValueOnce(new Error('unknown')).mockResolvedValue({ version: 2 }), read = vi.fn()
    const operation = createOperationOwner({ owner: controller.identity, label: '保存', input: { endpoint: '/save', method: 'PUT', requestKey: 'original-key', body: { value: 'original', requestKey: 'original-key' } },
      capability: { kind: 'IDEMPOTENT_KEY' }, write, read })
    expect(controller.ownOperation(operation)).toBe(true)
    const release = controller.attachView()
    await expect(operation.execute()).rejects.toThrow('unknown')
    expect(operation.getSnapshot()).toMatchObject({ phase: 'UNKNOWN', retired: false, accepted: false })
    expect(controller.retire().kind).toBe('BLOCK'); release()
    expect(controller.viewCount()).toBe(0); expect(dispose).toHaveBeenCalledOnce()
    expect(operation.getSnapshot()).toMatchObject({ phase: 'UNKNOWN', retired: false })
    const nextLease = controller.attachView()
    expect(attachReads).toHaveBeenCalledTimes(2); expect(write).toHaveBeenCalledOnce()
    await operation.recoverWrite()
    expect(write.mock.calls[1]![0]).toBe(write.mock.calls[0]![0]); expect(operation.identity.requestKey).toBe('original-key')
    expect(operation.getSnapshot().phase).toBe('SETTLED'); expect(read).toHaveBeenCalledOnce()
    nextLease(); expect(dispose).toHaveBeenCalledTimes(2); expect(controller.retire().kind).toBe('ALLOW')
  })

  it('reports subscriber failures without corrupting projection or interrupting another subscriber', () => {
    const controller = createSnapshotController({ identity, initial: { value: 1 }, canLeave: allow }), failure = new Error('subscriber'), notify = vi.fn()
    controller.subscribe(() => { throw failure }); controller.subscribe(notify)
    expect(controller.project({ value: 2 })).toBe(true); expect(notify).toHaveBeenCalledOnce()
    expect(controller.getSnapshot().value).toBe(2); expect(controller.notificationFailures()).toEqual([failure])
  })
})
