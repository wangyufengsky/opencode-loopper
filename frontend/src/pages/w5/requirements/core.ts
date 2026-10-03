import { createW4Owner, idleCommand, pendingCommand, recoverOperation, dirtyDecision, type CommandState } from '@/pages/w4/shared/core'
import type { OperationInput, OperationOwner, OriginalLookup, ReadContext } from '@/foundation/contracts/receipt'
import type { LeaveDecision } from '@/foundation/contracts/types'
import { userFacingError } from '@/utils/displayLabels'

export interface RequirementChild { subscribe?(listener: () => void): () => void; canLeave(): LeaveDecision; retire(forced?: boolean): LeaveDecision; discardDraft?(): void }
export interface RequirementParent { registerChild(child: RequirementChild): () => void; canStartWrite(caller?: RequirementChild): boolean; refresh(): Promise<void> }
export interface OwnedState { error: string; loading: boolean; dirty: boolean; draftRevision: number; command: CommandState }
export const ownedState = (): OwnedState => ({ error: '', loading: false, dirty: false, draftRevision: 0, command: idleCommand })
export function createRequirementScope<S extends OwnedState>(domain: string, id: string, initial: S, policy?: (snapshot: Readonly<S>) => LeaveDecision) {
  const core = createW4Owner(domain, id, initial, snapshot => policy?.(snapshot) ?? dirtyDecision(snapshot.dirty, snapshot.draftRevision))
  let parent: RequirementParent | undefined, operation: OperationOwner<unknown, unknown> | undefined
  const locked = () => pendingCommand(core.base.getSnapshot().command)
  const fail = (failure: unknown, fallback = '操作未完成，请保留原输入并核对结果。') => core.patch({ error: userFacingError(failure, fallback) } as Partial<S>)
  const owner = Object.assign(core.base, core, {
    locked, fail,
    edit(changes: Partial<S>) {
      if (!owner.capture().isCurrent() || locked() || parent && !parent.canStartWrite(owner)) return false
      return core.patch({ ...changes, dirty: true, draftRevision: owner.getSnapshot().draftRevision + 1 } as Partial<S>)
    },
    bindParent(value: RequirementParent) { parent = value; core.setWriteGate(() => parent!.canStartWrite(owner)) },
    async refreshParent() { if (!owner.capture().isCurrent()) return; await parent?.refresh() },
    async mutate<B, R>(options: {
      label: string; input: OperationInput<B>; write: (body: Readonly<B>, files: readonly File[]) => Promise<R>
      read: (receipt: Readonly<NoInfer<R>>, context: ReadContext) => Promise<void>
      lookup?: (body: Readonly<B>) => Promise<OriginalLookup<NoInfer<R>>>; clearDraft?: boolean
      handoffTarget?: (receipt: Readonly<NoInfer<R>>) => string
      rejected?: (failure: unknown) => boolean
    }) {
      if (locked() || !core.active() || !core.canStartWrite()) return false
      const next = core.command<B, R>({ label: options.label, input: options.input,
        capability: options.input.requestKey ? { kind: 'IDEMPOTENT_KEY' } : options.lookup ? { kind: 'READ_ORIGINAL', readOriginal: identity => options.lookup!(identity.body) } : { kind: 'NONE' },
        write: identity => options.write(identity.body, identity.files),
        read: async (receipt, context) => { await options.read(receipt, context); if (options.clearDraft) context.apply(() => core.patch({ dirty: false } as Partial<S>)) },
        changed: command => core.patch({ command } as Partial<S>), handoffTarget: options.handoffTarget,
        isDefinitiveRejection: options.rejected,
      })
      operation = next as unknown as OperationOwner<unknown, unknown>
      try { await next.execute(); return true } catch { return false }
    },
    async recover() { if (!owner.capture().isCurrent()) return; try { await recoverOperation(operation) } catch (cause) { fail(cause) } },
    operationIdentity: () => operation?.identity,
    prepareHandoff: () => operation!.prepareHandoff(),
    discardDraft() { if (!locked()) core.patch({ dirty: false, draftRevision: core.base.getSnapshot().draftRevision + 1 } as Partial<S>) },
  })
  return owner
}
export function requireIdentity(actual: string | undefined, expected: string, description = '读取结果') {
  if (actual !== expected) throw new Error(`${description}与原身份不一致，请保留原操作并重新读取。`)
}
export function dirty(state: OwnedState): LeaveDecision { return dirtyDecision(state.dirty, state.draftRevision) }
export { idleCommand, pendingCommand }
