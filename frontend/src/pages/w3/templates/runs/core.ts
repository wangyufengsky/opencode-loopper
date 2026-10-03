import { createSnapshotController } from '@/foundation/contracts/controller'
import { createOperationOwner, type OperationIdentity, type OperationInput, type OperationOwner, type RecoveryCapability, type ReadContext } from '@/foundation/contracts/receipt'
import type { LeaveDecision, ResourceScope } from '@/foundation/contracts/types'
import { ApiError } from '@/api/client'
import { userFacingError } from '@/utils/displayLabels'

let epoch = 0
export interface CommandState { label: string; phase: 'IDLE' | 'SENDING' | 'UNKNOWN' | 'ACCEPTED_READBACK' | 'SETTLED'; busy: boolean; accepted: boolean; recovery: 'READ_ORIGINAL' | 'RETRY_IDENTICAL' | 'BLOCKED'; error: string }
export const idleCommand: CommandState = { label: '', phase: 'IDLE', busy: false, accepted: false, recovery: 'BLOCKED', error: '' }
export const pendingCommand = (command: CommandState) => !['IDLE', 'SETTLED'].includes(command.phase)
export const definitive = (failure: unknown) => failure instanceof ApiError && [400, 403, 404, 409, 422].includes(failure.status)

/** Reads belong to a view lease; captured mutations belong to the retained record owner. */
export function runOwner<S>(domain: string, id: string, initial: S, canLeave: (snapshot: Readonly<S>) => LeaveDecision = () => ({ kind: 'ALLOW' })) {
  let resources: ResourceScope | undefined
  let start = () => {}
  const generations = new Map<string, number>()
  const controller = createSnapshotController({ identity: { domain, id, epoch: ++epoch }, initial, canLeave,
    attachReads(scope) {
      resources = scope
      scope.own(() => { if (resources === scope) resources = undefined; generations.clear() })
      void Promise.resolve().then(() => { if (scope.isActive()) start() })
    },
  })
  let writeGate = () => true
  const base = { ...controller, setWriteGate(gate: () => boolean) { writeGate = gate }, canStartWrite: () => controller.capture().isCurrent() && writeGate() }
  const patch = (changes: Partial<S>) => base.project({ ...base.getSnapshot(), ...changes } as S)
  function ticket(channel: string) {
    const lease = resources, sequence = (generations.get(channel) ?? 0) + 1
    generations.set(channel, sequence)
    return { current: () => !!lease?.isActive() && resources === lease && generations.get(channel) === sequence }
  }
  const own = (release: () => void) => resources ? resources.own(release) : (release(), () => {})
  function command<B, R>(options: {
    label: string; input: OperationInput<B>; capability: RecoveryCapability<B, R>
    write: (identity: OperationIdentity<B>) => Promise<R>
    read: (receipt: Readonly<R>, context: ReadContext) => Promise<void>
    changed: (command: CommandState) => void
  }) {
    if (!base.canStartWrite()) throw new Error('原运行不能创建新操作，请先核对正在进行的写入')
    const operation = createOperationOwner({ ...options, owner: base.identity, isDefinitiveRejection: definitive })
    if (!base.ownOperation(operation)) { operation.retire(true); throw new Error('原运行无法持有操作') }
    operation.subscribe(() => {
      if (!base.capture().isCurrent()) return
      const state = operation.getSnapshot()
      options.changed({ label: options.label, phase: state.phase, busy: state.busy, accepted: state.accepted, recovery: state.recovery.kind,
        error: state.error ? userFacingError(state.error, state.accepted ? '操作已接受，请重新读取原结果。' : '原操作结果尚未确认，请保留并恢复原身份。') : '' })
    })
    return operation
  }
  return { base, patch, ticket, own, command, active: () => !!resources?.isActive(),
    setStart(value: () => void) { start = value },
    delay(callback: () => void, milliseconds: number) {
      const lease = resources
      let release = () => {}
      const timer = setTimeout(() => { release(); if (lease?.isActive() && lease === resources) callback() }, milliseconds)
      release = own(() => clearTimeout(timer)); return release
    },
  }
}
export async function recoverOperation<B, R>(operation: OperationOwner<B, R> | undefined) {
  if (!operation || operation.getSnapshot().busy) return
  const state = operation.getSnapshot()
  if (state.accepted && state.receipt !== undefined) await operation.retryReadback()
  else if (state.recovery.kind === 'READ_ORIGINAL') await operation.readOriginal()
  else if (state.recovery.kind === 'RETRY_IDENTICAL') await operation.recoverWrite()
}
export function dirtyDecision(dirty: boolean, revision: number): LeaveDecision {
  return dirty ? { kind: 'CONFIRM_DISCARD', description: '尚有未提交的回答或文档，是否放弃本地草稿？', draftRevision: revision } : { kind: 'ALLOW' }
}
