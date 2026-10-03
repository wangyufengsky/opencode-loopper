import { createSnapshotController } from '@/foundation/contracts/controller'
import { createOperationOwner, type OperationInput, type OperationOwner, type RecoveryCapability, type ReadContext } from '@/foundation/contracts/receipt'
import type { LeaveDecision, ResourceScope } from '@/foundation/contracts/types'
import { ApiError } from '@/api/client'
import { userFacingError } from '@/utils/displayLabels'

let epoch = 0
export interface CommandStatus {
  label: string
  phase: 'IDLE' | 'SENDING' | 'UNKNOWN' | 'ACCEPTED_READBACK' | 'SETTLED'
  busy: boolean
  accepted: boolean
  recovery: 'READ_ORIGINAL' | 'RETRY_IDENTICAL' | 'BLOCKED'
  error: string
}
export const noCommand: CommandStatus = { label: '', phase: 'IDLE', busy: false, accepted: false, recovery: 'BLOCKED', error: '' }
export function unresolved(command: CommandStatus) { return command.phase !== 'IDLE' && command.phase !== 'SETTLED' }
export function definitive(failure: unknown) { return failure instanceof ApiError && [400, 403, 404, 409, 422].includes(failure.status) }

/** A view lease owns reads/timers, while the retained controller owns the exact operation. */
export function pageController<S>(domain: string, initial: S, leave: (snapshot: Readonly<S>) => LeaveDecision = () => ({ kind: 'ALLOW' })) {
  let resources: ResourceScope | null = null
  const generations = new Map<string, number>()
  let start: () => void = () => undefined
  const owner = createSnapshotController({ identity: { domain, id: 'page', epoch: ++epoch }, initial, canLeave: leave,
    attachReads(scope) {
      resources = scope
      scope.own(() => { if (resources === scope) resources = null; generations.clear() })
      // React StrictMode may retire this lease before the initial read begins.
      void Promise.resolve().then(() => { if (scope.isActive()) start() })
    },
  })
  const patch = (changes: Partial<S>) => owner.project({ ...owner.getSnapshot(), ...changes } as S)
  function ticket(channel: string) {
    const scope = resources, generation = (generations.get(channel) ?? 0) + 1
    generations.set(channel, generation)
    return { current: () => !!scope?.isActive() && resources === scope && generations.get(channel) === generation }
  }
  function invalidate(...channels: string[]) { for (const channel of channels) generations.set(channel, (generations.get(channel) ?? 0) + 1) }
  function own(dispose: () => void): () => void { if (resources) return resources.own(dispose); dispose(); return () => undefined }
  function command<B, R>(options: {
    label: string; input: OperationInput<B>; capability: RecoveryCapability<B, R>
    write: Parameters<typeof createOperationOwner<B, R>>[0]['write']
    read: (receipt: Readonly<R>, context: ReadContext) => Promise<void>
    changed: (status: CommandStatus) => void
    handoffTarget?: (receipt: Readonly<R>) => string
    rejected?: (failure: unknown) => void
  }): OperationOwner<B, R> {
    const operation = createOperationOwner({ ...options, owner: owner.identity,
      isDefinitiveRejection: failure => { const rejected = definitive(failure); if (rejected && owner.capture().isCurrent()) options.rejected?.(failure); return rejected },
    })
    owner.ownOperation(operation)
    operation.subscribe(() => {
      const state = operation.getSnapshot()
      options.changed({ label: options.label, phase: state.phase, busy: state.busy, accepted: state.accepted,
        recovery: state.recovery.kind, error: state.error ? userFacingError(state.error, state.accepted ? '写入已接受，读取或导航尚未完成。请恢复原操作。' : '结果尚未确认，请恢复原操作。') : '' })
    })
    return operation
  }
  return { owner, patch, ticket, invalidate, own, command, setStart(value: () => void) { start = value },
    active: () => !!resources?.isActive(), resources: () => resources }
}
export async function recover<B, R>(operation: OperationOwner<B, R> | null) {
  if (!operation || operation.getSnapshot().busy) return
  const snapshot = operation.getSnapshot()
  if (snapshot.accepted && snapshot.receipt !== undefined) await operation.retryReadback()
  else if (snapshot.recovery.kind === 'READ_ORIGINAL') await operation.readOriginal()
  else if (snapshot.recovery.kind === 'RETRY_IDENTICAL') await operation.recoverWrite()
}
