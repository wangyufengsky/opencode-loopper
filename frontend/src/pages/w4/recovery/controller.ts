import { api, ApiError } from '@/api/client'
import { createOperationOwner, type OperationOwner } from '@/foundation/contracts/receipt'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { LeaveDecision } from '@/foundation/contracts/types'
import type { W2Navigation } from '@/pages/w2/shared/types'
import { createW4Owner, dirtyDecision, idleCommand, pendingCommand, recoverOperation, type CommandState } from '../shared/core'
import type { RecoveryDraft, RecoveryMode, Task } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

export const recoveryModes = ['FROM_FAILED_STAGE', 'ALL_STAGES', 'VERIFY_ONLY'] as const
export interface RecoveryStudioState {
  task?: Task
  recoveries: RecoveryDraft[]
  result?: RecoveryDraft
  mode: RecoveryMode
  dirty: boolean
  draftRevision: number
  loading: boolean
  error: string
  command: CommandState
}
export function recoveryEligible(task?: Task) { return !!task && ['FAILED', 'CANCELLED'].includes(task.status) }
export function recoveryStage(task?: Task) {
  const stages = task?.stages ?? []
  return stages.find(stage => stage.status === 'FAILED') ?? stages.find(stage => ['RUNNING', 'PAUSED'].includes(stage.status))
    ?? stages.find(stage => stage.status !== 'SUCCEEDED') ?? stages.at(-1)
}
// RecoveryService validates these codes before creating/confirming the child draft (lines 51–68, 115–123, 162–180).
const rejectedBeforeCreate = new Set(['TEMPLATE_RECOVERY_UNSUPPORTED', 'RECOVERY_PARENT_NOT_TERMINAL', 'RECOVERY_CONTRACT_MISSING', 'RECOVERY_WORKSPACE_FINGERPRINT_MISSING', 'RECOVERY_WORKSPACE_FINGERPRINT_MISMATCH'])
function sameDraft(a: Readonly<RecoveryDraft>, b: Readonly<RecoveryDraft>) {
  return a.taskId === b.taskId && a.parentTaskId === b.parentTaskId && a.mode === b.mode
    && a.parentStageId === b.parentStageId && a.workspaceFingerprint === b.workspaceFingerprint && a.writableSession === b.writableSession
}
export function createRecoveryStudioController(taskId: string) {
  const core = createW4Owner<RecoveryStudioState>('task-recovery', taskId, {
    recoveries: [], mode: 'FROM_FAILED_STAGE', dirty: false, draftRevision: 0, loading: false, error: '', command: idleCommand,
  }, state => dirtyDecision(state.dirty, state.draftRevision))
  const { base, patch } = core
  let operation: OperationOwner<{ mode: RecoveryMode }, RecoveryDraft> | undefined
  async function load() {
    const ticket = core.ticket('context'); patch({ loading: true, error: '' })
    try {
      const [task, recoveries] = await Promise.all([api.getTask(taskId), api.getTaskRecoveries(taskId)])
      if (!ticket.current()) return
      if (task.id !== taskId || recoveries.some(item => item.parentTaskId !== taskId)) throw new Error('恢复上下文与原任务不一致，请重新读取。')
      patch({ task, recoveries })
    } catch (failure) { if (ticket.current()) patch({ error: userFacingError(failure, '无法读取失败上下文') }) }
    finally { if (ticket.current()) patch({ loading: false }) }
  }
  core.setStart(() => { void load() })
  function changeMode(mode: RecoveryMode) {
    if (!recoveryModes.some(value => value === mode) || pendingCommand(base.getSnapshot().command)) return
    patch({ mode, dirty: true, draftRevision: base.getSnapshot().draftRevision + 1 })
  }
  async function create() {
    const state = base.getSnapshot()
    if (!recoveryEligible(state.task) || pendingCommand(state.command) || !core.canStartWrite()) return
    const mode = state.mode
    const next = createOperationOwner({
      owner: base.identity, label: '创建恢复草稿', input: { endpoint: `/tasks/${encodeURIComponent(taskId)}/recoveries`, method: 'POST', body: { mode } },
      // The lineage list has no request key or creation identity. It is useful evidence,
      // but cannot prove which keyless POST created a new row, even when only one row appears.
      capability: { kind: 'READ_ORIGINAL', readOriginal: async () => {
        const token = base.capture()
        const rows = await api.getTaskRecoveries(taskId)
        if (token.isCurrent() && rows.every(row => row.parentTaskId === taskId)) patch({ recoveries: rows })
        return { kind: 'UNCONFIRMED' }
      } },
      write: identity => api.createTaskRecovery(taskId, identity.body.mode),
      isDefinitiveRejection: failure => failure instanceof ApiError && ([400, 401, 403, 422].includes(failure.status) || failure.status === 409 && !!failure.code && rejectedBeforeCreate.has(failure.code)),
      handoffTarget: receipt => `/tasks/${encodeURIComponent(receipt.taskId)}`,
      read: async (receipt, context) => {
        if (receipt.parentTaskId !== taskId || receipt.mode !== mode || !receipt.taskId) throw new Error('恢复回执与原父任务或模式不一致。')
        context.apply(() => patch({ result: { ...receipt } }))
        const [task, rows] = await Promise.all([api.getTask(taskId), api.getTaskRecoveries(taskId)])
        if (!context.isCurrent()) return
        if (task.id !== taskId || !rows.some(row => sameDraft(row, receipt))) throw new Error('草稿已创建，但恢复记录尚未核对，请只重新读取原结果。')
        context.apply(() => patch({ task, recoveries: rows, dirty: false, error: '' }))
      },
    })
    if (!base.ownOperation(next)) { next.retire(true); return }
    operation = next
    next.subscribe(() => {
      const value = next.getSnapshot(); if (operation !== next || !base.capture().isCurrent()) return
      patch({ command: { label: '创建恢复草稿', phase: value.phase, busy: value.busy, accepted: value.accepted, recovery: value.recovery.kind,
        error: value.error ? userFacingError(value.error, value.accepted ? '草稿已创建，请只重新读取原恢复记录。' : '恢复结果尚未确认，请保留原模式并核对。') : '' } })
    })
    await next.execute().catch(() => undefined)
  }
  async function recover() { await recoverOperation(operation).catch(() => undefined) }
  async function openTask(id: string, navigation: W2Navigation) {
    const state = base.getSnapshot(), receipt = operation?.getSnapshot().receipt
    if (operation?.getSnapshot().accepted && pendingCommand(state.command) && receipt?.taskId === id && state.result && sameDraft(state.result, receipt)) {
      const permit = operation.prepareHandoff()
      return navigation.goAccepted(`/tasks/${encodeURIComponent(id)}`, permit)
    }
    return navigation.go(`/tasks/${encodeURIComponent(id)}`)
  }
  function canLeave(request?: NavigationRequest): LeaveDecision {
    const result = base.getSnapshot().result, receipt = operation?.getSnapshot().receipt
    // Only the mode already accepted in this exact verified child is covered by its permit.
    if (base.capture().isCurrent() && request && result && receipt && sameDraft(result, receipt) && operation?.leaveRisk().permitsHandoff(request.handoff, request.destination)) return { kind: 'ALLOW' }
    return base.canLeave(request)
  }
  return { ...base, canLeave, load, changeMode, create, recover, openTask }
}
export type RecoveryStudioController = ReturnType<typeof createRecoveryStudioController>
