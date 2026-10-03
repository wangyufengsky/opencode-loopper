import { createSnapshotController } from '@/foundation/contracts/controller'
import { createOperationOwner, type OperationIdentity, type OperationInput, type ReadContext, type RecoveryCapability } from '@/foundation/contracts/receipt'
import type { LeaveDecision, ResourceScope } from '@/foundation/contracts/types'
import { ApiError } from '@/api/client'
import { userFacingError } from '@/utils/displayLabels'
export { recoverOperation, dirtyDecision, idleCommand, pendingCommand } from '@/pages/w3/templates/runs/core'
export type { CommandState } from '@/pages/w3/templates/runs/core'
import type { CommandState } from '@/pages/w3/templates/runs/core'

let epoch = 0
/** A 409/404 is not generic proof that an uncertain original operation is safe to replace. */
export const definitiveW4Rejection = (cause: unknown) => cause instanceof ApiError && [400, 401, 403, 422].includes(cause.status)

/** W1 scope/receipt primitives; view leases own reads, retained record owns commands. */
export function createW4Owner<S>(domain: string, id: string, initial: S, canLeave: (state: Readonly<S>) => LeaveDecision = () => ({ kind: 'ALLOW' })) {
  let resources: ResourceScope | undefined, start = () => {}, writeGate = () => true
  const channels = new Map<string, number>()
  const base = createSnapshotController({ identity: { domain, id, epoch: ++epoch }, initial, canLeave,
    attachReads(scope) {
      resources = scope
      scope.own(() => { if (resources === scope) resources = undefined; channels.clear() })
      void Promise.resolve().then(() => { if (scope.isActive()) start() })
    },
  })
  const patch = (changes: Partial<S>) => base.project({ ...base.getSnapshot(), ...changes } as S)
  function ticket(channel: string) {
    const lease = resources, serial = (channels.get(channel) ?? 0) + 1
    channels.set(channel, serial)
    return { current: () => !!lease?.isActive() && resources === lease && channels.get(channel) === serial && base.capture().isCurrent() }
  }
  const own = (release: () => void) => resources ? resources.own(release) : (release(), () => {})
  const canStartWrite = () => base.capture().isCurrent() && base.canLeave().kind !== 'BLOCK' && writeGate()
  function command<B, R>(options: {
    label: string; input: OperationInput<B>; capability: RecoveryCapability<B, R>
    write: (identity: OperationIdentity<B>) => Promise<R>
    read: (receipt: Readonly<R>, context: ReadContext) => Promise<void>
    changed: (state: CommandState) => void
    isDefinitiveRejection?: (cause: unknown) => boolean
    handoffTarget?: (receipt: Readonly<R>) => string
  }) {
    if (!canStartWrite()) throw new Error('尚有未结清操作或草稿，请先核对原身份。')
    const operation = createOperationOwner({ ...options, owner: base.identity, isDefinitiveRejection: options.isDefinitiveRejection ?? definitiveW4Rejection })
    if (!base.ownOperation(operation)) { operation.retire(true); throw new Error('原页面已经离开，不能开始新操作。') }
    operation.subscribe(() => {
      if (!base.capture().isCurrent()) return
      const state = operation.getSnapshot()
      options.changed({ label: options.label, phase: state.phase, busy: state.busy, accepted: state.accepted, recovery: state.recovery.kind,
        error: state.error ? userFacingError(state.error, state.accepted ? '操作已接受，请重新读取原结果。' : '原操作结果尚未确认，请保留并恢复原身份。') : '' })
    })
    return operation
  }
  return { base, patch, ticket, own, command, canStartWrite, active: () => !!resources?.isActive(),
    setWriteGate(gate: () => boolean) { writeGate = gate },
    setStart(value: () => void) { start = value },
    delay(callback: () => void, milliseconds: number) {
      const lease = resources
      let release = () => {}
      const timer = setTimeout(() => { release(); if (lease?.isActive() && resources === lease) callback() }, milliseconds)
      release = own(() => clearTimeout(timer))
      return release
    },
  }
}
