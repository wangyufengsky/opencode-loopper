import { ApiError } from '@/api/client'
import { createSnapshotController } from '@/foundation/contracts/controller'
import { createOperationOwner, type OperationInput, type OperationOwner, type OriginalLookup } from '@/foundation/contracts/receipt'
import type { LeaveDecision, ResourceScope } from '@/foundation/contracts/types'
import { userFacingError } from '@/utils/displayLabels'

export interface CoreState {
  error: string
  message: string
  dirty: boolean
  draftRevision: number
  mutation: { phase: 'IDLE' | 'SENDING' | 'UNKNOWN' | 'ACCEPTED_READBACK' | 'SETTLED'; label: string; busy: boolean; canRead: boolean; canRetry: boolean }
}
export const initialCoreState = (): CoreState => ({ error: '', message: '', dirty: false, draftRevision: 0,
  mutation: { phase: 'IDLE', label: '', busy: false, canRead: false, canRetry: false } })
let epoch = 0
export function rejected(failure: unknown) {
  return failure instanceof ApiError && [400, 401, 403, 404, 409, 422].includes(failure.status)
}
export const sameDto = (left: unknown, right: unknown): boolean => {
  if (Object.is(left, right)) return true
  if (Array.isArray(left) && Array.isArray(right)) return left.length === right.length && left.every((value, i) => sameDto(value, right[i]))
  if (!left || !right || typeof left !== 'object' || typeof right !== 'object') return false
  const l = Object.keys(left as object).filter(key => (left as Record<string, unknown>)[key] !== undefined).sort()
  const r = Object.keys(right as object).filter(key => (right as Record<string, unknown>)[key] !== undefined).sort()
  return l.length === r.length && l.every((key, i) => key === r[i] && sameDto((left as Record<string, unknown>)[key], (right as Record<string, unknown>)[key]))
}

/** Local page owner. Read leases may disappear; an unresolved mutation never loses its identity. */
export function createCoreOwner<S extends CoreState>(domain: string, initial: S, options?: {
  attachReads?: (resources: ResourceScope) => void
  extraLeave?: (state: Readonly<S>) => LeaveDecision | undefined
}) {
  const base = createSnapshotController({ identity: { domain, id: domain, epoch: ++epoch }, initial,
    attachReads: resources => options?.attachReads?.(resources),
    canLeave: state => options?.extraLeave?.(state) ?? (state.dirty
      ? { kind: 'CONFIRM_DISCARD', description: '仍有未保存修改，是否放弃后离开？', draftRevision: state.draftRevision }
      : { kind: 'ALLOW' }),
  })
  let operation: OperationOwner<unknown, unknown> | undefined
  let operationLabel = ''
  let operationIdempotent = false
  const set = (patch: Partial<S>) => base.project({ ...base.getSnapshot(), ...patch } as S)
  const locked = () => !!operation && !['IDLE', 'SETTLED'].includes(operation.getSnapshot().phase)
  const edit = (patch: Partial<S>) => {
    if (locked() || !base.capture().isCurrent()) return false
    return set({ ...patch, dirty: true, draftRevision: base.getSnapshot().draftRevision + 1 } as Partial<S>)
  }
  function publishOperation() {
    const value = operation?.getSnapshot()
    if (!value) return
    set({ mutation: { phase: value.phase, label: operationLabel, busy: value.busy,
      canRead: value.recovery.kind === 'READ_ORIGINAL', canRetry: operationIdempotent && value.phase === 'UNKNOWN' },
      ...(value.error ? { error: userFacingError(value.error, '操作未完成，请核对原操作结果') } : {}) } as Partial<S>)
  }
  async function mutate<B, R>(input: OperationInput<B>, options: {
    label: string
    write: (body: Readonly<B>) => Promise<R>
    read: (receipt: Readonly<NoInfer<R>>, apply: (project: () => void) => boolean) => Promise<void> | void
    lookup?: (body: Readonly<B>) => Promise<OriginalLookup<NoInfer<R>>>
    idempotent?: boolean
  }): Promise<boolean> {
    if (locked() || !base.capture().isCurrent()) return false
    set({ error: '', message: '' } as Partial<S>)
    const next = createOperationOwner({ owner: base.identity, label: options.label, input,
      capability: options.idempotent ? { kind: 'IDEMPOTENT_KEY', readOriginal: options.lookup ? identity => options.lookup!(identity.body) : undefined }
        : options.lookup ? { kind: 'READ_ORIGINAL', readOriginal: identity => options.lookup!(identity.body) } : { kind: 'NONE' },
      write: identity => options.write(identity.body),
      read: async (receipt, context) => { await options.read(receipt, context.apply) },
      isDefinitiveRejection: rejected,
    })
    if (!base.ownOperation(next)) { next.retire(true); return false }
    operationLabel = options.label
    operationIdempotent = options.idempotent ?? false
    operation = next as unknown as OperationOwner<unknown, unknown>
    next.subscribe(publishOperation)
    try { await next.execute(); return true } catch { return false }
    finally { publishOperation() }
  }
  async function recover() {
    if (!operation || operation.getSnapshot().busy) return
    const current = operation.getSnapshot()
    try {
      if (current.accepted) {
        if (current.receipt === undefined) await operation.readOriginal()
        else await operation.retryReadback()
      } else if (current.recovery.kind === 'READ_ORIGINAL') await operation.readOriginal()
      else if (current.recovery.kind === 'RETRY_IDENTICAL') await operation.recoverWrite()
    } catch { /* Original identity and the visible diagnostic stay owned. */ }
    publishOperation()
  }
  async function retryOriginal() {
    if (!operation || !operationIdempotent || operation.getSnapshot().phase !== 'UNKNOWN') return
    try { await operation.recoverWrite() } catch { /* Keep the exact operation and visible diagnostic. */ }
    publishOperation()
  }
  return { ...base, set, edit, locked, mutate, recover, retryOriginal,
    operationIdentity: () => operation?.identity,
    clearDraft: () => { if (!locked()) set({ dirty: false } as Partial<S>) },
  }
}
