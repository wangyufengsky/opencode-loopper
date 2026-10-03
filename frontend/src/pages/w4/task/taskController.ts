import { api } from '@/api/client'
import type { Task, TaskQueueStatus, RecoveryDraft } from '@/types/domain'
import type { LeaveDecision } from '@/foundation/contracts/types'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { OperationOwner, ReadContext } from '@/foundation/contracts/receipt'
import { OwnedResourceCleanupError } from '@/foundation/contracts/resource'
import type { W2PageProps } from '@/pages/w2/shared/types'
import type { TaskChildOwner, TaskParentPort } from '../shared/types'
import { createW4Owner, idleCommand, pendingCommand, recoverOperation, type CommandState } from '../shared/core'
import { createTaskEventSubscription } from '@/stores/taskEventSubscription'
import { userFacingError } from '@/utils/displayLabels'
import { taskEventNotice, taskFacts } from './projections'

export type TaskAction = 'start' | 'pause' | 'resume' | 'cancel' | 'retryJudges' | 'retryLoop' | 'reconcile' | 'rework'
export interface TaskDetailState {
  task?: Task; queue?: TaskQueueStatus; loading: boolean; error: string; auditLoading: boolean; auditError: string; queueLoading: boolean; queueError: string
  command: CommandState; confirming?: { action: TaskAction; version?: number; status: Task['status']; updatedAt: string }
  connection: 'idle' | 'connected' | 'reconnecting'; notices: string[]; now: number; childRevision: number; deliveryState: string; reworkChild?: string
}
const labels: Record<TaskAction, string> = { start: '开始执行', pause: '暂停任务', resume: '恢复任务', cancel: '取消任务', retryJudges: '重新进行双评审', retryLoop: '继续一轮', reconcile: '核对并释放队列', rework: '新分支重做' }
export function validateTask(task: Task, id: string, minimum?: number): Task {
  if (!task || task.id !== id || typeof task.status !== 'string' || !task.status) throw new Error('读取结果不属于原任务，请继续核对。')
  if (task.version !== undefined && (!Number.isInteger(task.version) || task.version < 0)) throw new Error('任务版本无效，请继续读取原任务。')
  if (minimum !== undefined && (task.version === undefined || task.version < minimum)) throw new Error('读取结果早于原操作，请继续读取原任务。')
  return task
}
export function createTaskDetailController(id: string, navigation?: W2PageProps['navigation']) {
  const env = createW4Owner<TaskDetailState>('task-detail', id, { loading: false, error: '', auditLoading: false, auditError: '', queueLoading: false, queueError: '', command: idleCommand, connection: 'idle', notices: [], now: Date.now(), childRevision: 0, deliveryState: '' })
  const { base, patch, ticket } = env
  const children = new Map<TaskChildOwner, { views: number; decision: string; release?: () => void }>()
  const ownLeave = (request?: NavigationRequest) => base.canLeave(request)
  function canLeave(request?: NavigationRequest): LeaveDecision {
    const decisions = [ownLeave(request), ...[...children.keys()].map(child => child.canLeave(request))]
    return decisions.find(row => row.kind === 'BLOCK') ?? decisions.find(row => row.kind === 'CONFIRM_DISCARD') ?? { kind: 'ALLOW' }
  }
  function canStartWrite(caller?: TaskChildOwner) {
    return base.capture().isCurrent() && ownLeave().kind === 'ALLOW' && [...children.keys()].every(child => {
      const decision = child.canLeave()
      return decision.kind === 'ALLOW' || child === caller && decision.kind === 'CONFIRM_DISCARD'
    })
  }
  env.setWriteGate(() => canStartWrite())
  function registerChild(child: TaskChildOwner) {
    let record = children.get(child)
    if (!record) {
      record = { views: 0, decision: JSON.stringify(child.canLeave()) }; children.set(child, record)
      if ('subscribe' in child && typeof child.subscribe === 'function') record.release = child.subscribe(() => {
        const decision = JSON.stringify(child.canLeave())
        // Only write/draft risk changes affect the parent's gate; read snapshots must not clone Task back into a child projection loop.
        if (record!.decision === decision) return
        record!.decision = decision
        if (!record!.views && child.canLeave().kind === 'ALLOW') { record!.release?.(); children.delete(child) }
        patch({ childRevision: base.getSnapshot().childRevision + 1 })
      })
    }
    record.views++; patch({ childRevision: base.getSnapshot().childRevision + 1 })
    let released = false
    return () => {
      if (released) return; released = true; record!.views--
      // A disappearing panel cannot drop a captured write or a user's draft from the parent guard.
      if (!record!.views && child.canLeave().kind === 'ALLOW') { record!.release?.(); children.delete(child) }
      patch({ childRevision: base.getSnapshot().childRevision + 1 })
    }
  }
  function applyTask(value: Task) {
    validateTask(value, id)
    const old = base.getSnapshot().task
    if (old?.version !== undefined && (value.version === undefined || value.version < old.version)) return
    patch({ task: { ...value, attempts: old?.attempts ?? value.attempts, artifacts: old?.artifacts ?? value.artifacts,
      stages: value.stages?.map(stage => ({ ...stage, attempts: old?.attempts?.filter(row => row.stageId === stage.id) ?? stage.attempts })) } })
  }
  async function overview() {
    const current = ticket('overview'); patch({ loading: true, error: '' })
    try { const value = validateTask(await api.getTaskOverview(id), id); if (current.current()) applyTask(value); return value }
    catch (cause) { if (current.current()) patch({ error: userFacingError(cause, '任务读取失败，请重试。') }); throw cause }
    finally { if (current.current()) patch({ loading: false }) }
  }
  async function audit() {
    const current = ticket('audit'); patch({ auditLoading: true, auditError: '' })
    try {
      const value = await api.getTaskAudit(id)
      if (current.current() && base.getSnapshot().task) {
        const task = base.getSnapshot().task!
        // Overview owns current errors/Judges. A delayed audit only supplies immutable attempts/artifacts.
        patch({ task: { ...task, attempts: value.attempts, artifacts: value.artifacts, stages: task.stages?.map(stage => ({ ...stage, attempts: (value.attempts ?? []).filter(row => row.stageId === stage.id) })) } })
      }
    } catch (cause) { if (current.current()) patch({ auditError: userFacingError(cause, '审计信息读取失败，请重试。') }); throw cause }
    finally { if (current.current()) patch({ auditLoading: false }) }
  }
  async function queue() {
    const current = ticket('queue')
    if (!current.current()) return
    if (base.getSnapshot().task?.status !== 'QUEUED') { patch({ queue: undefined, queueError: '', queueLoading: false }); return }
    patch({ queueLoading: true, queueError: '' })
    try { const value = await api.getTaskQueue(id); if (value.taskId !== id) throw new Error('队列结果不属于原任务。'); if (current.current()) patch({ queue: value }) }
    catch (cause) { if (current.current()) patch({ queueError: userFacingError(cause, '队列读取失败，请重试。') }); throw cause }
    finally { if (current.current()) patch({ queueLoading: false }) }
  }
  async function refresh() {
    const current = ticket('refresh')
    if (!current.current()) return
    const value = await overview()
    // The read lease can end while overview is pending; do not start any further retired-scope request.
    if (current.current()) await Promise.all([audit(), queue()])
    return value
  }
  function allowed(action: TaskAction, task = base.getSnapshot().task) {
    if (!task) return false
    const facts = taskFacts(task, base.getSnapshot().deliveryState)
    switch (action) {
      case 'start': return task.status === 'PENDING_START' && task.executionMode !== 'ROLLING_PACKAGES'
      case 'pause': return !facts.template && ['RUNNING', 'VERIFYING', 'RETRY_WAIT'].includes(task.status)
      case 'resume': return task.status === 'PAUSED'
      case 'cancel': return task.cancellationAvailable === true
      case 'retryJudges': return facts.canRetryJudges
      case 'retryLoop': return facts.canRetryLoop
      case 'reconcile': return task.status === 'QUEUED' && base.getSnapshot().queue?.reconcileAvailable === true
      case 'rework': return facts.canRework
    }
  }
  const paths: Record<Exclude<TaskAction, 'rework'>, string> = { start: 'start', pause: 'pause', resume: 'resume', cancel: 'cancel', retryJudges: 'judges/retry', retryLoop: 'loop/retry', reconcile: 'queue/reconcile' }
  function proves(action: Exclude<TaskAction, 'rework'>, before: Task, value: Task) {
    if (before.version === undefined || value.version === undefined || value.version <= before.version) return false
    switch (action) {
      case 'start': return ['QUEUED', 'PREPARING', 'READY', 'RUNNING', 'VERIFYING', 'RETRY_WAIT', 'JUDGING', 'SUCCEEDED', 'COMPLETED'].includes(value.status)
      case 'pause': return value.status === 'PAUSED'
      case 'resume': return ['QUEUED', 'PREPARING', 'READY', 'RUNNING', 'VERIFYING', 'RETRY_WAIT', 'JUDGING', 'SUCCEEDED', 'COMPLETED'].includes(value.status)
      case 'cancel': return ['STOPPING', 'CANCELLED'].includes(value.status)
      case 'retryLoop': return value.status !== 'WAITING_INPUT' && value.executionCycleOrdinal !== before.executionCycleOrdinal
      case 'retryJudges': return (value.judges ?? []).some(row => !(before.judges ?? []).some(old => old.id === row.id))
      case 'reconcile': return base.getSnapshot().queue?.leaseState === 'RELEASED'
    }
  }
  let operation: OperationOwner<{}, Task> | undefined
  let queueOperation: OperationOwner<{}, TaskQueueStatus> | undefined
  let createRework: OperationOwner<{ mode: 'REWORK_ALL_STAGES' }, RecoveryDraft> | undefined
  async function readTask(receipt: Readonly<Task>, context: ReadContext) {
    validateTask(receipt as Task, receipt.id === base.getSnapshot().reworkChild ? receipt.id : id)
    const value = validateTask(await api.getTaskOverview(receipt.id), receipt.id, receipt.version)
    if (!context.isCurrent()) return
    context.apply(() => { if (value.id === id) applyTask(value) })
    if (value.id === id) await Promise.all([audit(), queue()])
  }
  async function mutate(action: TaskAction) {
    if (!canStartWrite() || !allowed(action)) return
    patch({ confirming: undefined })
    const before = base.getSnapshot().task!
    if (action === 'reconcile') {
      operation = undefined
      queueOperation = env.command<{}, TaskQueueStatus>({ label: labels.reconcile, input: { endpoint: `/tasks/${id}/queue/reconcile`, method: 'POST', body: {} },
        capability: { kind: 'READ_ORIGINAL', readOriginal: async () => { const value = await api.getTaskQueue(id); if (value.taskId !== id) throw new Error('队列结果不属于原任务。'); return value.leaseState === 'RELEASED' ? { kind: 'ACCEPTED', receipt: value } : { kind: 'UNCONFIRMED' } } },
        write: () => api.reconcileTaskQueue(id), read: async (receipt, context) => { if (!receipt || receipt.taskId !== id) throw new Error('队列回执不属于原任务。'); if (context.isCurrent()) await refresh() }, changed: command => patch({ command }) })
      try { await queueOperation.execute() } catch { }
      return
    }
    queueOperation = undefined
    if (action === 'rework') {
      operation = undefined
      createRework = env.command({ label: labels.rework, input: { endpoint: `/tasks/${id}/recoveries`, method: 'POST', body: { mode: 'REWORK_ALL_STAGES' as const } }, capability: { kind: 'NONE' },
        write: identity => api.createTaskRecovery(id, identity.body.mode),
        read: async (receipt, context) => {
          if (receipt.parentTaskId !== id || !receipt.taskId || receipt.mode !== 'REWORK_ALL_STAGES') throw new Error('派生回执不属于原任务，请核对。')
          context.apply(() => patch({ reworkChild: receipt.taskId }))
          validateTask(await api.getTaskOverview(receipt.taskId), receipt.taskId)
        }, handoffTarget: receipt => `/tasks/${encodeURIComponent(receipt.taskId)}`, changed: command => patch({ command }) })
      try { await createRework.execute(); await startRework() } catch { /* Original identity remains visible. */ }
      return
    }
    operation = env.command({ label: labels[action], input: { endpoint: `/tasks/${id}/${paths[action]}`, method: 'POST', body: {}, versions: before.version === undefined ? undefined : { taskVersion: before.version } },
      capability: { kind: 'READ_ORIGINAL', readOriginal: async () => {
        const value = validateTask(await api.getTaskOverview(id), id, before.version)
        if (base.capture().isCurrent()) applyTask(value)
        return proves(action, before, value) ? { kind: 'ACCEPTED', receipt: value } : { kind: 'UNCONFIRMED' }
      } },
      write: async () => {
        const methods = { start: api.startTask, pause: api.pauseTask, resume: api.resumeTask, cancel: api.cancelTask, retryJudges: api.retryTaskJudges, retryLoop: api.retryWaitingTaskLoop }
        return methods[action](id)
      }, read: readTask, changed: command => patch({ command }) })
    try { await operation.execute() } catch { /* Recovery uses GET, never this POST again. */ }
  }
  async function startRework() {
    const child = base.getSnapshot().reworkChild
    if (!child || !canStartWrite()) return
    const before = validateTask(await api.getTaskOverview(child), child)
    if (!canStartWrite()) return
    if (before.status !== 'PENDING_START') { await navigateRework(); return }
    operation = env.command({ label: '开始派生任务', input: { endpoint: `/tasks/${child}/start`, method: 'POST', body: {}, versions: before.version === undefined ? undefined : { taskVersion: before.version } }, capability: { kind: 'READ_ORIGINAL', readOriginal: async () => {
      const value = validateTask(await api.getTaskOverview(child), child, before.version)
      return proves('start', before, value) ? { kind: 'ACCEPTED', receipt: value } : { kind: 'UNCONFIRMED' }
    } }, write: () => api.startTask(child), read: readTask, handoffTarget: receipt => `/tasks/${encodeURIComponent(receipt.id)}`, changed: command => patch({ command }) })
    // The receipt itself determines the precise navigation destination; there is no replacement create.
    try { await operation.execute(); await navigateRework() } catch { }
  }
  async function navigateRework() {
    const child = base.getSnapshot().reworkChild
    const accepted = operation?.getSnapshot().accepted ? operation : createRework?.getSnapshot().accepted ? createRework : undefined
    if (!child || !navigation || !accepted || pendingCommand(base.getSnapshot().command)) return
    await navigation.goAccepted(`/tasks/${encodeURIComponent(child)}`, accepted.prepareHandoff())
  }
  async function recover() {
    try {
      if (createRework && pendingCommand(base.getSnapshot().command) && !operation) { await recoverOperation(createRework); if (createRework.getSnapshot().phase === 'SETTLED') await startRework() }
      else { if (queueOperation) await recoverOperation(queueOperation); else await recoverOperation(operation); if (base.getSnapshot().reworkChild) await navigateRework() }
    } catch { }
  }
  const subscription = createTaskEventSubscription({
    receive: (_taskId, event) => { if (!env.active()) return; const notice = taskEventNotice(event); if (notice) patch({ notices: [...base.getSnapshot().notices.slice(-9), notice] }) },
    needsOverview: type => /^(task|package|stage|attempt|session|verification|judge|error|artifact)\./.test(type),
    overview: async () => { await overview(); await queue() }, audit,
    state: connection => { if (env.active()) patch({ connection }) }, error: cause => { if (env.active()) patch({ error: userFacingError(cause, '更新读取失败，请手动刷新。') }) },
  })
  env.setStart(() => {
    subscription.watch(id); env.own(subscription.stop)
    void refresh().catch(() => {})
    function clock() {
      patch({ now: Date.now() })
      const task = base.getSnapshot().task
      if (task?.executionMode === 'TEMPLATE_REPORT' && !['COMPLETED', 'FAILED', 'CANCELLED'].includes(task.status) && !document.hidden && Math.floor(Date.now() / 1000) % 4 === 0) void overview().catch(() => {})
      env.delay(clock, 1000)
    }
    env.delay(clock, 1000)
  })
  const parent: TaskParentPort = { registerChild, canStartWrite, refresh }
  return { ...base, canLeave, parent, refresh, overview, audit, queue, allowed, recover, startRework, navigateRework,
    operation: () => operation, queueOperation: () => queueOperation, reworkOperation: () => createRework,
    setDeliveryState(value: string) { if (base.getSnapshot().deliveryState !== value) patch({ deliveryState: value }) },
    request(action: TaskAction) {
      if (!canStartWrite() || !allowed(action)) return
      const task = base.getSnapshot().task!
      if (['cancel', 'retryJudges', 'retryLoop', 'rework'].includes(action) || action === 'reconcile' && base.getSnapshot().queue?.releaseReason === 'SESSION_WRITER_UNCONFIRMED') patch({ confirming: { action, version: task.version, status: task.status, updatedAt: task.updatedAt } })
      else void mutate(action)
    },
    confirm() { const s = base.getSnapshot(), c = s.confirming, t = s.task; if (!c || !t || c.version !== t.version || c.status !== t.status || c.updatedAt !== t.updatedAt || !canStartWrite()) return; void mutate(c.action) },
    cancelConfirmation() { patch({ confirming: undefined }) },
    confirmationCurrent() { const s = base.getSnapshot(); return !!s.confirming && s.confirming.version === s.task?.version && s.confirming.status === s.task?.status && s.confirming.updatedAt === s.task?.updatedAt && canStartWrite() },
    retire(forced = false) {
      const decision = canLeave(); if (!forced && decision.kind !== 'ALLOW') return decision
      const failures: unknown[] = []
      try { base.retire(forced) } catch (failure) { failures.push(failure) }
      for (const [child, record] of children) { try { record.release?.(); child.retire(forced) } catch (failure) { failures.push(failure) } }
      children.clear(); if (failures.length) throw new OwnedResourceCleanupError(failures)
      return { kind: 'ALLOW' as const }
    },
  }
}
export type TaskDetailController = ReturnType<typeof createTaskDetailController>
