import { captureDto } from '@/foundation/contracts/immutable'
import { createOwnerScope } from '@/foundation/contracts/scope'
import type { OperationLeaveRisk } from '@/foundation/contracts/receipt'
import type { OwnerIdentity } from '@/foundation/contracts/types'
import type { TemplateFailedBatch } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'
import { idleCommand, type CommandState } from './core'

/** Endpoint-specific original CAS replay. This is not a keyed or generic retry capability.
 * TemplateBatchRetryService/Store compare original ID/version and reuse replaced generations.
 * Only an explicit user action replays; reads never alter this captured selection.
 */
export function createBatchCasOperation(options: {
  owner: OwnerIdentity; id: string; rows: readonly TemplateFailedBatch[]
  write: (id: string, rows: TemplateFailedBatch[]) => Promise<unknown>
  read: () => Promise<void>; canReplay: () => boolean; changed: (state: CommandState) => void
}) {
  const scope = createOwnerScope(options.owner)
  const identity = Object.freeze({ id: options.id, body: captureDto([...options.rows]), owner: scope.identity })
  let state: CommandState = { ...idleCommand, label: '原批次 CAS 恢复' }, running: Promise<void> | undefined
  function publish(changes: Partial<CommandState>) { state = Object.freeze({ ...state, ...changes }); if (scope.isActive()) options.changed(state) }
  async function readback() {
    await options.read()
    if (scope.isActive()) publish({ phase: 'SETTLED', error: '', accepted: true })
  }
  function perform(write: boolean) {
    if (running) return running
    if (!scope.isActive()) return Promise.resolve()
    publish({ busy: true, phase: write ? 'SENDING' : 'ACCEPTED_READBACK', error: '' })
    running = Promise.resolve().then(async () => {
      if (!scope.isActive()) return
      if (write) { await options.write(identity.id, identity.body as TemplateFailedBatch[]); if (!scope.isActive()) return; publish({ accepted: true, phase: 'ACCEPTED_READBACK', recovery: 'READ_ORIGINAL' }) }
      await readback()
    }).catch(failure => {
      // A CAS conflict after uncertain delivery cannot prove that the original write did not run.
      if (scope.isActive()) publish({ phase: state.accepted ? 'ACCEPTED_READBACK' : 'UNKNOWN', recovery: state.accepted ? 'READ_ORIGINAL' : 'RETRY_IDENTICAL', error: userFacingError(failure, '原批次操作尚未确认，请核对停止状态并保留原 CAS。') })
    }).finally(() => { running = undefined; if (scope.isActive()) publish({ busy: false }) })
    return running
  }
  return {
    identity, belongsTo: (owner: OwnerIdentity) => owner === options.owner,
    getSnapshot: () => state,
    leaveRisk: (): OperationLeaveRisk => ({ phase: state.phase, permitsHandoff: () => false }),
    retire(forced = false) { if (!forced && !['IDLE', 'SETTLED'].includes(state.phase)) return false; scope.retire(); return true },
    execute() { return state.phase === 'IDLE' ? perform(true) : Promise.resolve() },
    recover() { if (state.accepted) return perform(false); return state.phase === 'UNKNOWN' && options.canReplay() ? perform(true) : Promise.resolve() },
  }
}
