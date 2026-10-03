import { api } from '@/api/client'
import type { TemplateFailedBatch, TemplateSessionDiagnostic, TemplateDiagnosticFilter, TemplateRecoveryAction } from '@/types/domain'
import type { OperationOwner } from '@/foundation/contracts/receipt'
import { createRecoveryCommandId } from '@/utils/recoveryCommandId'
import { userFacingError } from '@/utils/displayLabels'
import { runOwner, idleCommand, pendingCommand, recoverOperation, type CommandState } from './core'
import { createBatchCasOperation } from './batchCasOperation'

export interface BatchState { rows: TemplateFailedBatch[]; cursor: string | null; selected: string[]; loading: boolean; ready: boolean; resumeAvailable: boolean; taskVersion: number; blockingBatches: number; environmentBlocked: boolean; error: string; command: CommandState }
export function createBatchRecoveryOwner(kind: 'document' | 'task', id: string, active = false) {
  const core = runOwner<BatchState>('template-batch-recovery', `${kind}:${id}`, { rows: [], cursor: null, selected: [], loading: false, ready: false, resumeAvailable: false, taskVersion: 0, blockingBatches: 0, environmentBlocked: false, error: '', command: idleCommand })
  const { base, patch, ticket } = core
  let operation: OperationOwner<TemplateFailedBatch[] | { expectedVersion: number }, unknown> | undefined
  let casOperation: ReturnType<typeof createBatchCasOperation> | undefined
  let enabled = active, cancelTimer: (() => void) | undefined
  function schedule() { cancelTimer?.(); cancelTimer = undefined; if (kind === 'task' && enabled && core.active()) cancelTimer = core.delay(() => { const state = base.getSnapshot(); if (state.command.busy || state.selected.length || typeof document !== 'undefined' && document.hidden) schedule(); else void load() }, 5_000) }
  async function load(more = false, throwFailure = false) {
    cancelTimer?.(); cancelTimer = undefined; const current = ticket('list'), before = base.getSnapshot(); patch({ loading: true })
    try { const page = await (kind === 'document' ? api.documentFailedBatches : api.templateFailedBatches)(id, more ? before.cursor ?? '' : '')
      if (current.current()) patch({ rows: more ? [...before.rows, ...page.items] : page.items, cursor: page.nextCursor ?? null, ready: page.facets?.retrySelectionReady === 1,
        resumeAvailable: kind === 'task' && page.facets?.resumeAvailable === 1, taskVersion: page.facets?.taskVersion ?? 0, blockingBatches: page.facets?.blockingBatches ?? 0,
        environmentBlocked: page.facets?.environmentBlocked === 1, error: '', selected: before.selected.filter(value => page.items.some(row => row.id === value) || more && before.rows.some(row => row.id === value)) })
    } catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '未能读取待处理批次，请重新加载。'), ready: false, resumeAvailable: false }); if (throwFailure) throw failure }
    finally { if (current.current()) { patch({ loading: false }); schedule() } }
  }
  async function mutate(recheck: boolean) {
    const state = base.getSnapshot()
    if (pendingCommand(state.command) || state.loading || state.error || !base.canStartWrite()) return
    const rows = state.rows.filter(row => state.selected.includes(row.id))
    if (recheck ? !state.resumeAvailable : !state.ready || !rows.length || rows.length > 100) return
    if (!recheck) {
      casOperation = createBatchCasOperation({ owner: base.identity, id, rows,
        write: (ownerId, selection) => (kind === 'document' ? api.retrySelectedDocumentBatches : api.retrySelectedTemplateBatches)(ownerId, selection),
        read: async () => { await load(false, true); if (base.capture().isCurrent()) patch({ selected: [] }) },
        canReplay: () => base.capture().isCurrent() && base.getSnapshot().ready && !base.getSnapshot().environmentBlocked && !base.getSnapshot().loading,
        changed: command => patch({ command }),
      })
      if (!base.ownOperation(casOperation)) { casOperation.retire(true); return }
      await casOperation.execute(); return
    }
    const body: TemplateFailedBatch[] | { expectedVersion: number } = recheck ? { expectedVersion: state.taskVersion } : rows
    operation = core.command<TemplateFailedBatch[] | { expectedVersion: number }, unknown>({ label: recheck ? '重新检查原批次' : '重新触发所选批次', input: { endpoint: `/template-tasks/${kind === 'document' ? 'document-runs/' : ''}${id}/${recheck ? 'recheck' : 'batches/retry'}`, method: 'POST', body },
      // This endpoint has CAS but no idempotency key or authoritative by-request lookup.
      // A changed version does not prove that this particular write was accepted.
      capability: { kind: 'READ_ORIGINAL', readOriginal: async () => { await load(false, true); return { kind: 'UNCONFIRMED' } } },
      write: identity => recheck ? api.recheckTemplateTask(id, (identity.body as { expectedVersion: number }).expectedVersion)
        : (kind === 'document' ? api.retrySelectedDocumentBatches : api.retrySelectedTemplateBatches)(id, identity.body as TemplateFailedBatch[]),
      read: async (_receipt, context) => { if (context.isCurrent()) await load(false, true); context.apply(() => patch({ selected: [] })) }, changed: command => patch({ command }),
    })
    await operation.execute().catch(() => {})
  }
  core.setStart(() => { void load() })
  return { ...base, load, setActive(value: boolean) { enabled = value; schedule() }, retry: () => mutate(false), recheck: () => mutate(true),
    select(id: string, checked: boolean) { const state = base.getSnapshot(); if (state.command.busy) return; patch({ selected: checked ? [...new Set([...state.selected, id])] : state.selected.filter(value => value !== id) }) },
    selectAll() { const state = base.getSnapshot(); if (!state.command.busy) patch({ selected: state.selected.length === state.rows.length ? [] : state.rows.map(row => row.id) }) },
    recover: () => casOperation ? casOperation.recover() : recoverOperation(operation).catch(() => {}), operation: () => casOperation ?? operation,
  }
}

export interface DiagnosticState { items: TemplateSessionDiagnostic[]; filter: TemplateDiagnosticFilter; cursor?: string; previous: (string | undefined)[]; next: string | null; loading: boolean; error: string; notice: string; selected: string; detail?: TemplateSessionDiagnostic; detailLoading: boolean; detailError: string; confirming?: TemplateSessionDiagnostic; command: CommandState }
export function createDiagnosticOwner(taskId: string, active = true) {
  const core = runOwner<DiagnosticState>('template-diagnostics', taskId, { items: [], filter: 'ATTENTION', previous: [], next: null, loading: false, error: '', notice: '', selected: '', detailLoading: false, detailError: '', command: idleCommand })
  const { base, patch, ticket } = core
  let operation: OperationOwner<{ action?: TemplateRecoveryAction; expectedVersion: number; commandId?: string }, TemplateSessionDiagnostic> | undefined
  const acceptedCommands = new Set<string>()
  const commandKey = (row: TemplateSessionDiagnostic, action: TemplateRecoveryAction | 'CHECK') => `${row.batchId}:${row.batchVersion}:${action}`
  let enabled = active, cancelTimer: (() => void) | undefined
  function schedule() { cancelTimer?.(); cancelTimer = undefined; if (enabled && core.active()) cancelTimer = core.delay(() => { cancelTimer = undefined; if (typeof document !== 'undefined' && document.hidden) schedule(); else void load() }, 5_000) }
  async function load(throwFailure = false) {
    cancelTimer?.(); cancelTimer = undefined; const state = base.getSnapshot(), current = ticket('list'); patch({ loading: true })
    try { const page = await api.getTemplateSessionDiagnostics(taskId, state.filter, state.cursor, 50)
      if (current.current()) { patch({ items: page.items, next: page.hasMore ? page.nextCursor : null, error: '' }); if (state.selected) { if (page.items.some(row => row.batchId === state.selected)) void details(state.selected); else close() } }
    } catch (failure) { if (current.current()) patch({ error: userFacingError(failure, '批次诊断加载失败，请刷新重试') }); if (throwFailure) throw failure }
    finally { if (current.current()) { patch({ loading: false }); schedule() } }
  }
  async function details(batchId: string) {
    const current = ticket('detail'); patch({ selected: batchId, detail: undefined, detailLoading: true, detailError: '' })
    try { const detail = await api.getTemplateSessionDiagnostic(taskId, batchId); if (current.current()) patch({ detail }) }
    catch (failure) { if (current.current()) patch({ detailError: userFacingError(failure, '诊断详情加载失败，请重试') }) }
    finally { if (current.current()) patch({ detailLoading: false }) }
  }
  function close() { ticket('detail'); ticket('copy'); patch({ selected: '', detail: undefined, detailError: '', detailLoading: false }) }
  function canAct(row: TemplateSessionDiagnostic, action: TemplateRecoveryAction | 'CHECK') {
    const state = base.getSnapshot(), projected = state.items.find(item => item.batchId === row.batchId)
    return base.canStartWrite() && !pendingCommand(state.command) && !state.loading && !state.error && !acceptedCommands.has(commandKey(row, action)) && projected?.batchVersion === row.batchVersion && !!(action === 'CHECK' ? row.canCheck : action === 'FINALIZE' ? row.canFinalize : row.canStop)
  }
  async function mutate(row: TemplateSessionDiagnostic, action: TemplateRecoveryAction | 'CHECK') {
    if (!canAct(row, action)) return
    const body = action === 'CHECK' ? { expectedVersion: row.batchVersion } : { action, expectedVersion: row.batchVersion, commandId: createRecoveryCommandId() }
    operation = core.command({ label: action === 'CHECK' ? '重新检查会话' : '会话恢复', input: { endpoint: `/tasks/${taskId}/session-diagnostics/${row.batchId}/${action === 'CHECK' ? 'check' : 'recover'}`, method: 'POST', body, commandId: body.commandId },
      capability: action === 'CHECK' ? { kind: 'READ_ORIGINAL', readOriginal: async () => { await load(true); return { kind: 'UNCONFIRMED' } } } : { kind: 'IDEMPOTENT_KEY' },
      write: async identity => { const receipt = await (action === 'CHECK' ? api.checkTemplateSession(taskId, row.batchId, identity.body.expectedVersion)
        : api.recoverTemplateSession(taskId, row.batchId, identity.body as { action: TemplateRecoveryAction; expectedVersion: number; commandId: string })); acceptedCommands.add(commandKey(row, action)); return receipt },
      read: async (receipt, context) => { if (receipt.batchId !== row.batchId || !Number.isSafeInteger(receipt.batchVersion) || receipt.batchVersion < body.expectedVersion) throw new Error('原会话回执身份或版本无效，请保留原恢复命令。'); context.apply(() => patch({ items: base.getSnapshot().items.map(item => item.batchId === receipt.batchId ? receipt as TemplateSessionDiagnostic : item), notice: '恢复请求已记录，正在核对原会话与停止状态。' })); if (context.isCurrent()) await load(true) },
      changed: command => patch({ command }),
    })
    await operation.execute().catch(() => {})
  }
  async function copySummary() {
    const row = base.getSnapshot().detail, current = ticket('copy'); if (!row) return
    try { if (!navigator.clipboard) throw new Error('复制不可用'); await navigator.clipboard.writeText(diagnosticSummary(taskId, row)); if (current.current()) patch({ notice: '诊断摘要已复制', detailError: '' }) }
    catch { if (current.current()) patch({ detailError: '复制失败，请选中诊断摘要手动复制' }) }
  }
  core.setStart(() => { cancelTimer = undefined; void load() })
  return { ...base, load, details, close, canAct, copySummary,
    setActive(value: boolean) { enabled = value; schedule() },
    changeFilter(filter: TemplateDiagnosticFilter) { if (!base.getSnapshot().command.busy) { close(); patch({ filter, cursor: undefined, previous: [], items: [] }); void load() } },
    page(next: boolean) { const state = base.getSnapshot(); if (state.command.busy) return; close(); patch(next ? { previous: [...state.previous, state.cursor], cursor: state.next ?? undefined } : { cursor: state.previous.at(-1), previous: state.previous.slice(0, -1) }); void load() },
    request(row: TemplateSessionDiagnostic, action: TemplateRecoveryAction | 'CHECK') { if (action === 'STOP') patch({ confirming: row }); else void mutate(row, action) },
    confirm() { const row = base.getSnapshot().confirming; if (row) { patch({ confirming: undefined }); void mutate(row, 'STOP') } },
    cancelConfirmation() { patch({ confirming: undefined }) }, recover: () => recoverOperation(operation).catch(() => {}), operation: () => operation,
  }
}
/** Only the explicit diagnostic drawer exposes IDs; no transcripts or credential fields. */
export function diagnosticSummary(taskId: string, row: TemplateSessionDiagnostic) {
  const { batchId, batchVersion, sessionKey, localSessionId, externalSessionId, requestMessageId, worktreePath, stageOrdinal, purpose, ordinal, generation, state, phase, reason, remoteState, connected, acceptedAt, submissionRevision, candidateAccepted, observedAt, lastActivityAt, lastProgressAt, stopProof, stopConfirmedAt, recoveryAction, recoveryRequestedAt, automaticRetries, retryLimit, nextRetryAt, failedOperation, transportError, transportMessage, transportFailures, firstFailedAt, lastFailedAt, nextCheckAt } = row
  return JSON.stringify({ taskId, batchId, batchVersion, sessionKey, localSessionId, externalSessionId, requestMessageId, worktreePath, stageOrdinal, purpose, ordinal, generation, state, phase, reason, remoteState, connected, acceptedAt, submissionRevision, candidateAccepted, observedAt, lastActivityAt, lastProgressAt, stopProof, stopConfirmedAt, recoveryAction, recoveryRequestedAt, automaticRetries, retryLimit, nextRetryAt, failedOperation, transportError, transportMessage, transportFailures, firstFailedAt, lastFailedAt, nextCheckAt }, null, 2)
}
