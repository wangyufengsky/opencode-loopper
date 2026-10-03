import { describe, expect, it, vi } from 'vitest'
import { createOperationOwner } from './receipt'
import type { OperationInput, ReadContext } from './receipt'

const identity = { domain: 'requirement', id: 'original', epoch: 1 }
const input = () => ({ endpoint: '/api/requirements', method: 'POST' as const,
  requestKey: 'original-key', body: { requestKey: 'original-key', project: 'original-project', title: '原始标题', items: [1, 2] },
  versions: { commandVersion: 19, templateRevision: 'original-revision' }, files: [] as File[] })
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
function text(file: File) {
  return new Promise<string>((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => reject(reader.error)
    reader.readAsText(file)
  })
}

describe('framework-free operation owner', () => {
  it('does not write on construction, subscription or snapshots; only an explicit initial execute writes', async () => {
    const write = vi.fn().mockResolvedValue({ id: 'created', version: 20 }), read = vi.fn()
    const operation = createOperationOwner({ owner: identity, label: '创建', input: input(), capability: { kind: 'IDEMPOTENT_KEY' }, write, read })
    const initial = operation.getSnapshot(), unsubscribe = operation.subscribe(vi.fn())
    expect(operation.getSnapshot()).toBe(initial)
    expect(write).not.toHaveBeenCalled(); expect(read).not.toHaveBeenCalled()
    unsubscribe(); unsubscribe()
    await operation.execute()
    expect(write).toHaveBeenCalledOnce(); expect(read).toHaveBeenCalledOnce()
    expect(operation.getSnapshot()).toMatchObject({ phase: 'SETTLED', accepted: true, busy: false, receipt: { version: 20 } })
    await expect(operation.execute()).rejects.toThrow('原操作恢复')
    expect(write).toHaveBeenCalledOnce()
  })

  it('keeps endpoint/key/DTO/versions and actual File bytes/order fixed after the caller edits inputs', async () => {
    const original = input(), first = new File(['original bytes'], 'same.md'), second = new File(['second bytes'], 'second.md')
    original.files.push(first, second)
    const write = vi.fn().mockRejectedValueOnce(new Error('unknown')).mockResolvedValue({ id: 'r' })
    const operation = createOperationOwner({ owner: identity, label: '上传', input: original, capability: { kind: 'IDEMPOTENT_KEY' }, write, read: vi.fn() })
    original.endpoint = '/api/other'; original.requestKey = 'replacement-key'; original.body.title = '替换'; original.body.items.reverse()
    original.versions.commandVersion = 100; original.files.reverse(); original.files[0] = new File(['replacement bytes'], 'same.md')
    await expect(operation.execute()).rejects.toThrow('unknown')
    await operation.recoverWrite()
    expect(write.mock.calls[1]![0]).toBe(write.mock.calls[0]![0])
    expect(operation.identity).toMatchObject({ endpoint: '/api/requirements', requestKey: 'original-key',
      body: { title: '原始标题', items: [1, 2] }, versions: { commandVersion: 19, templateRevision: 'original-revision' } })
    expect(operation.identity.files).toEqual([first, second])
    expect(await Promise.all(operation.identity.files.map(text))).toEqual(['original bytes', 'second bytes'])
    expect(Object.isFrozen(first)).toBe(false); expect(Object.isFrozen(operation.identity.files)).toBe(true)
  })

  it('coalesces an in-flight write and hard-blocks normal retirement without losing the key', async () => {
    const pending = deferred<{ id: string }>(), write = vi.fn(() => pending.promise)
    const operation = createOperationOwner({ owner: identity, label: '创建', input: input(), capability: { kind: 'IDEMPOTENT_KEY' }, write, read: vi.fn() })
    const first = operation.execute(), second = operation.execute()
    expect(second).toBe(first); expect(operation.getSnapshot().phase).toBe('SENDING')
    expect(operation.retire()).toBe(false); expect(operation.identity.requestKey).toBe('original-key')
    pending.resolve({ id: 'r' }); await first
    expect(write).toHaveBeenCalledOnce(); expect(operation.retire()).toBe(true)
  })

  it('coalesces subscriber reentry before dispatch and isolates notification failures from the accepted operation', async () => {
    const write = vi.fn().mockResolvedValue({ id: 'created' }), read = vi.fn(), failure = new Error('subscriber failed')
    const operation = createOperationOwner({ owner: identity, label: '创建', input: input(), capability: { kind: 'IDEMPOTENT_KEY' }, write, read })
    let reentered: Promise<void> | undefined
    operation.subscribe(() => { if (operation.getSnapshot().phase === 'SENDING') reentered = operation.execute() })
    operation.subscribe(() => { throw failure })
    const healthy = vi.fn(); operation.subscribe(healthy)
    const work = operation.execute()
    expect(reentered).toBe(work); expect(write).not.toHaveBeenCalled()
    await work
    expect(write).toHaveBeenCalledOnce(); expect(read).toHaveBeenCalledOnce(); expect(healthy).toHaveBeenCalled()
    expect(operation.getSnapshot()).toMatchObject({ phase: 'SETTLED', accepted: true, error: undefined })
    expect(operation.notificationFailures().length).toBeGreaterThan(0)
    expect(operation.notificationFailures().every(error => error === failure)).toBe(true)
  })

  it('never replays a no-key unknown write without an endpoint capability', async () => {
    const original: OperationInput<{ title: string }> = { endpoint: '/api/no-key', method: 'POST', body: { title: '原稿' } }
    const write = vi.fn().mockRejectedValue(new Error('unknown'))
    const operation = createOperationOwner({ owner: identity, label: '提交', input: original, capability: { kind: 'NONE' }, write, read: vi.fn() })
    await expect(operation.execute()).rejects.toThrow('unknown')
    expect(operation.getSnapshot().recovery.kind).toBe('BLOCKED')
    await expect(operation.recoverWrite()).rejects.toThrow('未授权')
    await expect(operation.readOriginal()).rejects.toThrow('没有可用')
    expect(operation.retire()).toBe(false); expect(write).toHaveBeenCalledOnce()
    expect(operation.identity.body).toEqual({ title: '原稿' })
  })

  it('rejects a fabricated idempotent capability with no retained request key', () => {
    expect(() => createOperationOwner({ owner: identity, label: '提交', input: { endpoint: '/api/no-key', method: 'POST', body: {} },
      capability: { kind: 'IDEMPOTENT_KEY' }, write: vi.fn(), read: vi.fn() })).toThrow('未保留幂等身份')
  })

  it('allows no-key read-original only when that exact endpoint supplies it; unconfirmed reads do not write', async () => {
    const lookup = vi.fn().mockResolvedValueOnce({ kind: 'UNCONFIRMED' }).mockResolvedValue({ kind: 'ACCEPTED', receipt: { id: 'original-r' } })
    const write = vi.fn().mockRejectedValue(new Error('unknown')), read = vi.fn()
    const operation = createOperationOwner({ owner: identity, label: '提交', input: { endpoint: '/api/no-key/original-id', method: 'POST', body: {} },
      capability: { kind: 'READ_ORIGINAL', readOriginal: lookup }, write, read })
    await expect(operation.execute()).rejects.toThrow('unknown')
    await operation.readOriginal()
    expect(operation.getSnapshot().phase).toBe('UNKNOWN'); expect(write).toHaveBeenCalledOnce(); expect(read).not.toHaveBeenCalled()
    await operation.readOriginal()
    expect(read.mock.calls[0]![0]).toEqual({ id: 'original-r' }); expect(operation.getSnapshot().phase).toBe('SETTLED')
    expect(lookup.mock.calls[1]![0]).toBe(operation.identity); expect(write).toHaveBeenCalledOnce()
  })

  it('recovers an accepted write/read failure only by reading, retaining the receipt and its advanced revision', async () => {
    const write = vi.fn().mockResolvedValue({ id: 'r', version: 20 }), read = vi.fn().mockRejectedValueOnce(new Error('read failed')).mockResolvedValue(undefined)
    const operation = createOperationOwner({ owner: identity, label: '保存', input: input(), capability: { kind: 'IDEMPOTENT_KEY' }, write, read })
    await expect(operation.execute()).rejects.toThrow('read failed')
    expect(operation.getSnapshot()).toMatchObject({ accepted: true, phase: 'ACCEPTED_READBACK', receipt: { version: 20 } })
    expect(operation.retire()).toBe(false)
    await operation.recoverWrite()
    expect(write).toHaveBeenCalledOnce(); expect(read).toHaveBeenCalledTimes(2)
    expect(read.mock.calls[1]![0]).toEqual({ id: 'r', version: 20 })
    expect(operation.identity.versions?.commandVersion).toBe(19)
  })

  it('preserves fulfilled-write acceptance when local receipt capture fails, exposing no invalid DTO or write recovery', async () => {
    const invalid: { id: string; self?: unknown } = { id: 'accepted' }; invalid.self = invalid
    const write = vi.fn().mockResolvedValue(invalid), read = vi.fn(), target = vi.fn(() => '/accepted')
    const operation = createOperationOwner({ owner: identity, label: '创建', input: input(), capability: { kind: 'IDEMPOTENT_KEY' }, write, read, handoffTarget: target })
    await expect(operation.execute()).rejects.toThrow('循环引用')
    expect(operation.getSnapshot()).toMatchObject({ accepted: true, phase: 'ACCEPTED_READBACK', recovery: { kind: 'BLOCKED' } })
    expect(Object.prototype.hasOwnProperty.call(operation.getSnapshot(), 'receipt')).toBe(false)
    await expect(operation.recoverWrite()).rejects.toThrow('经过验证'); await expect(operation.retryReadback()).rejects.toThrow('经过验证')
    await expect(operation.readOriginal()).rejects.toThrow('没有可用')
    expect(operation.retire()).toBe(false); expect(() => operation.prepareHandoff()).toThrow('尚无确定回执')
    expect(write).toHaveBeenCalledOnce(); expect(read).not.toHaveBeenCalled(); expect(target).not.toHaveBeenCalled()
    expect(operation.identity.requestKey).toBe('original-key')
  })

  it('validates an accepted but unavailable receipt only through explicit original-identity lookup, then reads without rewriting', async () => {
    const lookup = vi.fn().mockResolvedValue({ kind: 'ACCEPTED', receipt: { id: 'original-r', version: 20 } })
    const write = vi.fn().mockResolvedValue(new Date(0)), read = vi.fn()
    const operation = createOperationOwner({ owner: identity, label: '创建', input: input(), capability: { kind: 'IDEMPOTENT_KEY', readOriginal: lookup }, write, read })
    await expect(operation.execute()).rejects.toThrow('原生对象')
    expect(operation.getSnapshot()).toMatchObject({ phase: 'ACCEPTED_READBACK', accepted: true, recovery: { kind: 'READ_ORIGINAL' } })
    expect(lookup).not.toHaveBeenCalled(); expect(read).not.toHaveBeenCalled()
    await operation.readOriginal()
    expect(lookup).toHaveBeenCalledExactlyOnceWith(operation.identity)
    expect(read.mock.calls[0]![0]).toEqual({ id: 'original-r', version: 20 })
    expect(operation.getSnapshot()).toMatchObject({ accepted: true, phase: 'SETTLED', receipt: { id: 'original-r', version: 20 } })
    expect(write).toHaveBeenCalledOnce()
  })

  it('keeps acceptance reported by an invalid original-result lookup and never feeds its raw value to a reader or writer', async () => {
    const invalid: { id: string; self?: unknown } = { id: 'original-r' }; invalid.self = invalid
    const lookup = vi.fn().mockResolvedValue({ kind: 'ACCEPTED', receipt: invalid }), write = vi.fn().mockRejectedValue(new Error('unknown')), read = vi.fn()
    const operation = createOperationOwner({ owner: identity, label: '创建', input: input(), capability: { kind: 'IDEMPOTENT_KEY', readOriginal: lookup }, write, read })
    await expect(operation.execute()).rejects.toThrow('unknown'); await expect(operation.readOriginal()).rejects.toThrow('循环引用')
    expect(operation.getSnapshot()).toMatchObject({ accepted: true, phase: 'ACCEPTED_READBACK', recovery: { kind: 'READ_ORIGINAL' } })
    expect(Object.prototype.hasOwnProperty.call(operation.getSnapshot(), 'receipt')).toBe(false)
    await expect(operation.recoverWrite()).rejects.toThrow('经过验证')
    expect(lookup).toHaveBeenCalledExactlyOnceWith(operation.identity); expect(write).toHaveBeenCalledOnce(); expect(read).not.toHaveBeenCalled()
    expect(operation.retire()).toBe(false); expect(() => operation.prepareHandoff()).toThrow('尚无确定回执')
  })

  it('keeps a definitive 409 rejection and draft separate without inventing a new operation', async () => {
    const conflict = { status: 409 }, write = vi.fn().mockRejectedValue(conflict)
    const operation = createOperationOwner({ owner: identity, label: '保存', input: input(), capability: { kind: 'IDEMPOTENT_KEY' },
      write, read: vi.fn(), isDefinitiveRejection: failure => failure === conflict })
    await expect(operation.execute()).rejects.toBe(conflict)
    expect(operation.getSnapshot()).toMatchObject({ phase: 'SETTLED', accepted: false, error: conflict })
    expect(operation.getSnapshot().recovery.kind).toBe('BLOCKED')
    expect(operation.identity.body.title).toBe('原始标题')
    await expect(operation.recoverWrite()).rejects.toThrow('未授权'); expect(write).toHaveBeenCalledOnce()
  })

  it('preserves a late accepted receipt after forced loss while applying no old or new-page projection', async () => {
    const pending = deferred<{ id: string }>(), write = vi.fn(() => pending.promise), read = vi.fn(), newPage = vi.fn()
    const operation = createOperationOwner({ owner: identity, label: '创建', input: input(), capability: { kind: 'IDEMPOTENT_KEY' }, write, read })
    const work = operation.execute(); await Promise.resolve(); expect(write).toHaveBeenCalledOnce()
    expect(operation.retire(true)).toBe(true); operation.subscribe(newPage)
    pending.resolve({ id: 'original-r' }); await work
    expect(operation.getSnapshot()).toMatchObject({ retired: true, accepted: true, phase: 'ACCEPTED_READBACK', receipt: { id: 'original-r' } })
    expect(operation.identity.requestKey).toBe('original-key'); expect(read).not.toHaveBeenCalled(); expect(newPage).not.toHaveBeenCalled()
    const retired = operation.getSnapshot()
    await expect(operation.execute()).rejects.toThrow('已退休'); await expect(operation.recoverWrite()).rejects.toThrow('已退休')
    expect(operation.getSnapshot()).toBe(retired); expect(operation.leaveRisk().phase).toBe(retired.phase)
  })

  it('invalidates a read context after its await without writing into a replacement scope', async () => {
    const pending = deferred<void>(), apply = vi.fn(); let captured!: ReadContext
    const operation = createOperationOwner({ owner: identity, label: '读取', input: input(), capability: { kind: 'IDEMPOTENT_KEY' },
      write: vi.fn().mockResolvedValue({ id: 'r' }), read: async (_receipt, context) => { captured = context; await pending.promise; context.apply(apply) } })
    const work = operation.execute(); await Promise.resolve(); await Promise.resolve(); await Promise.resolve()
    expect(captured.isCurrent()).toBe(true); operation.retire(true); pending.resolve(); await work
    expect(captured.apply(apply)).toBe(false); expect(apply).not.toHaveBeenCalled()
  })

  it('does not serialize a File embedded as DTO or accept a mismatched retained key', () => {
    expect(() => createOperationOwner({ owner: identity, label: '错误输入', input: { endpoint: '/x', method: 'POST', body: { file: new File(['a'], 'a.md') } },
      capability: { kind: 'NONE' }, write: vi.fn(), read: vi.fn() })).toThrow('原生对象')
    expect(() => createOperationOwner({ owner: identity, label: '错误输入', input: { ...input(), requestKey: 'other' },
      capability: { kind: 'IDEMPOTENT_KEY' }, write: vi.fn(), read: vi.fn() })).toThrow('不一致')
  })
})
