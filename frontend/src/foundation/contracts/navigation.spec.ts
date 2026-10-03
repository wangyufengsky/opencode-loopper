import { describe, expect, it, vi } from 'vitest'
import { createNavigationGate, decideLeave, toClosePolicy } from './navigation'
import { createOperationOwner } from './receipt'
import { createOwnerScope } from './scope'
import type { LeaveDecision, ReceiptPhase } from './types'

const identity = { domain: 'requirement', id: 'original', epoch: 1 }
const clean = () => ({ kind: 'ALLOW' as const })
const dirty = (draftRevision = 1): LeaveDecision => ({ kind: 'CONFIRM_DISCARD', description: '放弃原稿？', draftRevision })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}

describe('explicit navigation through a single injected history owner', () => {
  for (const phase of ['SENDING', 'UNKNOWN', 'ACCEPTED_READBACK'] as const) {
    it(`blocks ${phase} before dirty/File confirmation on every destination`, async () => {
      const confirmDiscard = vi.fn().mockResolvedValue(true), navigate = vi.fn().mockResolvedValue(true)
      const gate = createNavigationGate({ scope: createOwnerScope(identity), confirmDiscard, navigate,
        canLeave: request => decideLeave({ operations: [{ phase, permitsHandoff: () => false }], dirty: true, unsentFiles: 1, draftRevision: 1, request }) })
      for (const destination of ['/tasks', '/requirements/other-id', '/settings']) {
        expect((await gate.navigate({ destination })).kind).toBe('BLOCKED')
      }
      expect(confirmDiscard).not.toHaveBeenCalled(); expect(navigate).not.toHaveBeenCalled()
    })
  }

  it('keeps ordinary dirty or unsent File input until explicit confirmation; cancellation never navigates', async () => {
    const confirmDiscard = vi.fn().mockResolvedValueOnce(false).mockResolvedValue(true), navigate = vi.fn().mockResolvedValue(true)
    const gate = createNavigationGate({ scope: createOwnerScope(identity), confirmDiscard, navigate,
      canLeave: request => decideLeave({ operations: [], dirty: false, unsentFiles: 1, draftRevision: 4, request }) })
    expect((await gate.navigate({ destination: '/tasks' })).kind).toBe('DECLINED')
    expect(navigate).not.toHaveBeenCalled()
    expect((await gate.navigate({ destination: '/tasks' })).kind).toBe('NAVIGATED')
    expect(navigate).toHaveBeenCalledOnce()
    expect(confirmDiscard.mock.calls[1]![0]).toMatchObject({ kind: 'CONFIRM_DISCARD', draftRevision: 4 })
  })

  it('rechecks scope and newly pending writes after awaited dirty confirmation', async () => {
    const answer = deferred<boolean>(), navigate = vi.fn().mockResolvedValue(true)
    let phase: ReceiptPhase = 'SETTLED'
    const scope = createOwnerScope(identity)
    const gate = createNavigationGate({ scope, navigate, confirmDiscard: () => answer.promise,
      canLeave: request => decideLeave({ operations: [{ phase, permitsHandoff: () => false }], dirty: true, unsentFiles: 0, draftRevision: 1, request }) })
    const work = gate.navigate({ destination: '/tasks' }); await Promise.resolve()
    phase = 'UNKNOWN'; answer.resolve(true)
    expect((await work).kind).toBe('BLOCKED'); expect(navigate).not.toHaveBeenCalled()
    const second = deferred<boolean>()
    const stale = createNavigationGate({ scope, navigate, confirmDiscard: () => second.promise, canLeave: () => dirty() })
    const late = stale.navigate({ destination: '/other' }); await Promise.resolve(); scope.retire(); second.resolve(true)
    expect((await late).kind).toBe('STALE'); expect(navigate).not.toHaveBeenCalled()
  })

  it('rejects an old confirmation when the ordinary draft revision changed', async () => {
    const answer = deferred<boolean>(), navigate = vi.fn().mockResolvedValue(true); let revision = 1
    const gate = createNavigationGate({ scope: createOwnerScope(identity), navigate, canLeave: () => dirty(revision), confirmDiscard: () => answer.promise })
    const work = gate.navigate({ destination: '/tasks' }); await Promise.resolve(); revision = 2; answer.resolve(true)
    expect((await work).kind).toBe('STALE'); expect(navigate).not.toHaveBeenCalled()
  })

  it('coalesces the same intent but does not falsely report another destination as navigated', async () => {
    const move = deferred<boolean>(), navigate = vi.fn(() => move.promise)
    const gate = createNavigationGate({ scope: createOwnerScope(identity), navigate, canLeave: clean, confirmDiscard: vi.fn() })
    const first = gate.navigate({ destination: '/a' })
    expect(gate.navigate({ destination: '/a' })).toBe(first)
    expect((await gate.navigate({ destination: '/b' })).kind).toBe('BLOCKED')
    move.resolve(true); expect((await first).kind).toBe('NAVIGATED')
    expect(navigate).toHaveBeenCalledExactlyOnceWith('/a', { replace: undefined, handoff: undefined })
  })

  it('retains an accepted creation receipt for false/rejected navigation, then performs its exact handoff without creating again', async () => {
    const write = vi.fn().mockResolvedValue({ id: 'created' }), read = vi.fn().mockRejectedValue(new Error('read unavailable'))
    const operation = createOperationOwner<{ requestKey: string }, { id: string }>({ owner: identity, label: '创建', input: { endpoint: '/api/requirements', method: 'POST', requestKey: 'key', body: { requestKey: 'key' } },
      capability: { kind: 'IDEMPOTENT_KEY' }, write, read, handoffTarget: receipt => `/requirements/${receipt.id}` })
    await expect(operation.execute()).rejects.toThrow('read unavailable')
    const permit = operation.prepareHandoff(), navigate = vi.fn().mockResolvedValueOnce(false).mockRejectedValueOnce(new Error('router failed')).mockResolvedValue(true)
    const gate = createNavigationGate({ scope: createOwnerScope(identity), navigate, confirmDiscard: vi.fn(),
      canLeave: request => decideLeave({ operations: [operation.leaveRisk()], dirty: false, unsentFiles: 0, draftRevision: 0, request }) })
    expect((await gate.navigate({ destination: '/other', handoff: permit })).kind).toBe('BLOCKED')
    expect((await gate.navigate({ destination: permit.destination, handoff: { destination: permit.destination } })).kind).toBe('BLOCKED')
    expect((await gate.navigate({ destination: permit.destination, handoff: permit })).kind).toBe('FAILED')
    expect(operation.getSnapshot()).toMatchObject({ phase: 'ACCEPTED_READBACK', receipt: { id: 'created' }, retired: false })
    expect((await gate.navigate({ destination: permit.destination, handoff: permit })).kind).toBe('FAILED')
    expect(operation.identity.requestKey).toBe('key')
    expect((await gate.navigate({ destination: permit.destination, handoff: permit })).kind).toBe('NAVIGATED')
    expect(operation.getSnapshot()).toMatchObject({ phase: 'SETTLED', accepted: true, retired: true })
    expect(write).toHaveBeenCalledOnce(); expect(read).toHaveBeenCalledOnce()
  })

  it('does not let one accepted handoff bypass another unresolved operation or another instance', async () => {
    const make = () => createOperationOwner({ owner: identity, label: '创建', input: { endpoint: '/create', method: 'POST', body: {} }, capability: { kind: 'NONE' as const },
      write: vi.fn().mockResolvedValue({ id: 'r' }), read: vi.fn().mockRejectedValue(new Error('read')), handoffTarget: () => '/r' })
    const a = make(), b = make(); await expect(a.execute()).rejects.toThrow('read'); await expect(b.execute()).rejects.toThrow('read')
    const permit = a.prepareHandoff(), request = { destination: '/r', handoff: permit }
    expect(decideLeave({ operations: [a.leaveRisk(), b.leaveRisk()], dirty: false, unsentFiles: 0, draftRevision: 0, request }).kind).toBe('BLOCK')
    expect(decideLeave({ operations: [b.leaveRisk()], dirty: false, unsentFiles: 0, draftRevision: 0, request }).kind).toBe('BLOCK')
    expect(() => createOperationOwner({ owner: identity, label: '未接受', input: { endpoint: '/x', method: 'POST', body: {} }, capability: { kind: 'NONE' }, write: vi.fn(), read: vi.fn(), handoffTarget: () => '/r' }).prepareHandoff()).toThrow('尚无确定回执')
  })

  it('finishes accepted handoff only after successful navigation even when that route retires the old view scope', async () => {
    const scope = createOwnerScope(identity), move = deferred<boolean>()
    const operation = createOperationOwner<{}, { id: string }>({ owner: identity, label: '创建', input: { endpoint: '/create', method: 'POST', body: {} }, capability: { kind: 'NONE' },
      write: vi.fn().mockResolvedValue({ id: 'r' }), read: vi.fn().mockRejectedValue(new Error('read')), handoffTarget: receipt => `/r/${receipt.id}` })
    await expect(operation.execute()).rejects.toThrow('read')
    const permit = operation.prepareHandoff()
    const gate = createNavigationGate({ scope, navigate: () => move.promise, confirmDiscard: vi.fn(),
      canLeave: request => decideLeave({ operations: [operation.leaveRisk()], dirty: false, unsentFiles: 0, draftRevision: 0, request }) })
    const work = gate.navigate({ destination: permit.destination, handoff: permit }); await Promise.resolve()
    expect(operation.getSnapshot()).toMatchObject({ phase: 'ACCEPTED_READBACK', retired: false })
    scope.retire(); move.resolve(true)
    expect((await work).kind).toBe('NAVIGATED')
    expect(operation.getSnapshot()).toMatchObject({ phase: 'SETTLED', retired: true, receipt: { id: 'r' } })
  })

  it('rejects a forged handoff even when ordinary leave policy allows navigation', async () => {
    const navigate = vi.fn().mockResolvedValue(true)
    const gate = createNavigationGate({ scope: createOwnerScope(identity), navigate, canLeave: clean, confirmDiscard: vi.fn() })
    expect((await gate.navigate({ destination: '/r', handoff: { destination: '/r' } })).kind).toBe('FAILED')
    expect(navigate).not.toHaveBeenCalled()
  })

  it('keeps denied and rejected dirty decisions separate from navigation and projects reasons to UI policy', async () => {
    const navigate = vi.fn(), decision = dirty()
    const gate = createNavigationGate({ scope: createOwnerScope(identity), navigate, canLeave: () => decision, confirmDiscard: vi.fn().mockRejectedValue(new Error('dismissed')) })
    expect((await gate.navigate({ destination: '/r' })).kind).toBe('FAILED'); expect(navigate).not.toHaveBeenCalled()
    expect(toClosePolicy(decision)).toEqual({ kind: 'confirm', reason: '放弃原稿？' })
    expect(toClosePolicy({ kind: 'BLOCK', reason: '原身份待核对', recoveryAction: '读取' })).toEqual({ kind: 'block', reason: '原身份待核对' })
    expect(toClosePolicy(clean())).toEqual({ kind: 'allow' })
  })
})
