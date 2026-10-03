import { api } from '@/api/client'
import type { DocumentTemplateOverview, SourceTemplateOverview, SourceTemplateBatch, SourceTemplateCommand } from '@/types/domain'
import type { LeaveDecision } from '@/foundation/contracts/types'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { OperationOwner } from '@/foundation/contracts/receipt'
import { OwnedResourceCleanupError } from '@/foundation/contracts/resource'
import { userFacingError } from '@/utils/displayLabels'
import { idleCommand, pendingCommand, recoverOperation, runOwner, type CommandState } from './core'

export type RunKind = 'source' | 'document'
export type Run = SourceTemplateOverview | DocumentTemplateOverview
export type RunAction = 'start' | 'cancel' | 'resume' | 'retry' | 'archive' | 'unarchive'
interface RunChild { identity?: { domain: string; id: string; epoch: number }; canLeave: (request?: NavigationRequest) => LeaveDecision; getSnapshot?: () => unknown; setWriteGate?: (gate: () => boolean) => void; subscribe?: (listener: () => void) => () => void; recover?: () => Promise<unknown>; retire?: (forced?: boolean) => unknown }
export interface RunState { run?: Run; documentContext?: DocumentTemplateOverview; loading: boolean; error: string; disconnected: boolean; batches: SourceTemplateBatch[]; next: string | null; selected: string[]; batchLoading: boolean; command: CommandState; confirming?: { action: RunAction; version: number }; childOperations: { key: string; label: string; command?: CommandState; dirty: boolean; error?: string }[] }
export function createRunController(kind: RunKind, id: string) {
  const children = new Map<RunChild, () => void>()
  const core = runOwner<RunState>(`template-${kind}`, id, { loading: false, error: '', disconnected: false, batches: [], next: null, selected: [], batchLoading: false, command: idleCommand, childOperations: [] }, () => {
    let dirty: LeaveDecision = { kind: 'ALLOW' }
    for (const child of children.keys()) { const decision = child.canLeave(); if (decision.kind === 'BLOCK') return decision; if (decision.kind === 'CONFIRM_DISCARD') dirty = decision }
    return dirty
  })
  const { base, patch, ticket } = core
  function childrenHaveDraft() { return [...children.keys()].some(child => child.canLeave().kind !== 'ALLOW') }
  function projectChildren() {
    const state = base.getSnapshot()
    if (kind === 'document' && state.run && !childrenHaveDraft()) patch({ documentContext: state.run as DocumentTemplateOverview })
    patch({ childOperations: [...children.keys()].flatMap(child => {
      const value = child.getSnapshot?.() as { command?: CommandState; error?: string } | undefined, decision = child.canLeave()
      if (!value?.command || !pendingCommand(value.command) && decision.kind !== 'CONFIRM_DISCARD' && !(child.identity?.domain === 'template-diagnostics' && value.error)) return []
      const label = child.identity?.domain === 'document-clarification' ? '业务回答' : child.identity?.domain === 'document-supplement' ? '补充文档' : child.identity?.domain === 'template-diagnostics' ? '会话诊断' : '批次恢复'
      return [{ key: `${child.identity?.domain}:${child.identity?.id}:${child.identity?.epoch}`, label, command: value.command, error: value.error, dirty: decision.kind === 'CONFIRM_DISCARD' }]
    }) })
  }
  let operation: OperationOwner<SourceTemplateCommand, Run> | undefined
  let stream: EventSource | undefined, releaseStream: (() => void) | undefined, cancelPoll: (() => void) | undefined, cancelRefresh: (() => void) | undefined
  const terminal = () => ['COMPLETED', 'CANCELLED'].includes(base.getSnapshot().run?.state ?? '')
  function stopStream() { releaseStream?.(); releaseStream = undefined; stream = undefined; cancelPoll?.(); cancelPoll = undefined }
  async function batches(append = false) {
    const current = base.getSnapshot().run
    if (kind !== 'source' || current?.templateId !== 'DETAILED_DESIGN_WRITING') return
    const request = ticket('batches'), before = base.getSnapshot(); patch({ batchLoading: true })
    try { const page = await api.sourceBatches(id, append ? before.next ?? '' : '')
      if (request.current()) patch({ batches: append ? [...before.batches, ...page.items] : page.items, next: page.nextCursor ?? null,
        selected: before.selected.filter(selected => page.items.some(item => item.id === selected && item.retryable) || append && before.batches.some(item => item.id === selected && item.retryable)) })
    } catch (failure) { if (request.current()) patch({ error: userFacingError(failure, '批次读取失败，请重试') }) }
    finally { if (request.current()) patch({ batchLoading: false }) }
  }
  async function refresh() {
    const request = ticket('overview'); patch({ loading: true })
    try {
      const value = await (kind === 'source' ? api.sourceTemplate(id) : api.documentTemplate(id))
      if (!request.current()) return
      applyRun(value); patch({ error: '' }); await batches()
    } catch (failure) { if (request.current()) patch({ error: userFacingError(failure, '任务状态读取失败，请重试') }) }
    finally { if (request.current()) patch({ loading: false }) }
  }
  function applyRun(value: Readonly<Run>) {
    if (value.id !== id || !base.capture().isCurrent()) return
    const current = base.getSnapshot().run
    if (current && value.version < current.version) return
    // A read may advance the run while the original child still owns a draft or write.
    // Keep that child's UI and eligibility until it settles or the user discards its draft.
    patch({ run: value as Run, ...(kind === 'document' && (!base.getSnapshot().documentContext || !childrenHaveDraft()) ? { documentContext: value as DocumentTemplateOverview } : {}) }); if (terminal()) stopStream()
  }
  function invalidate() { if (!cancelRefresh) cancelRefresh = core.delay(() => { cancelRefresh = undefined; void refresh() }, 180) }
  function schedule() { cancelPoll?.(); cancelPoll = undefined; if (!terminal() && core.active()) cancelPoll = core.delay(() => { cancelPoll = undefined; void refresh().then(schedule) }, 10_000) }
  function connect() {
    if (!core.active() || terminal() || stream) return
    if (typeof EventSource !== 'undefined') {
      const current = kind === 'source' ? api.sourceEvents(id) : api.documentEvents(id), lease = ticket('stream')
      stream = current
      const progress = () => { if (lease.current()) { patch({ disconnected: false }); invalidate() } }
      const opened = () => { if (lease.current()) { patch({ disconnected: false }); invalidate() } }
      const failed = () => { if (lease.current()) { patch({ disconnected: true }); invalidate() } }
      current.addEventListener('progress', progress); current.onopen = opened; current.onerror = failed
      releaseStream = core.own(() => { current.removeEventListener('progress', progress); current.onopen = null; current.onerror = null; current.close(); if (stream === current) stream = undefined })
    }
    schedule()
  }
  async function submit(action: RunAction) {
    const current = base.getSnapshot().run
    if (!current || pendingCommand(base.getSnapshot().command) || [...children.keys()].some(child => child.canLeave().kind === 'BLOCK')) return
    if (kind === 'document' && !['cancel', 'resume', 'archive', 'unarchive'].includes(action)) return
    const input: SourceTemplateCommand = { requestKey: crypto.randomUUID(), expectedVersion: current.version,
      ...(action === 'retry' ? { modelIds: [...base.getSnapshot().selected].sort() } : {}) }
    if (action === 'retry' && !input.modelIds?.length) return
    operation = core.command<SourceTemplateCommand, Run>({ label: action === 'cancel' ? '取消任务' : action === 'retry' ? '重试所选批次' : '模板操作',
      input: { endpoint: `/template-tasks/${kind}-runs/${id}/${action}`, method: 'POST', body: input, requestKey: input.requestKey }, capability: { kind: 'IDEMPOTENT_KEY' },
      write: identity => kind === 'source' ? api.sourceTemplateCommand(id, action, identity.body) : api.documentTemplateCommand(id, action as 'cancel' | 'resume' | 'archive' | 'unarchive', identity.body),
      read: async (receipt, context) => {
        if (receipt.id !== id || !Number.isSafeInteger(receipt.version) || receipt.version < input.expectedVersion) throw new Error('原操作回执不属于当前运行或版本无效，请核对原结果。')
        context.apply(() => { applyRun(receipt); patch({ selected: [] }) }); if (context.isCurrent() && core.active()) { await batches(); connect() }
      }, changed: command => patch({ command }),
    })
    await operation.execute().catch(() => {})
  }
  core.setStart(() => { cancelRefresh = undefined; cancelPoll = undefined; void refresh().then(connect) })
  return { ...base, kind, id, refresh, loadBatches: batches, applyRun,
    registerChild(child: RunChild) {
      if (!base.capture().isCurrent()) return () => {}
      child.setWriteGate?.(() => base.capture().isCurrent() && !pendingCommand(base.getSnapshot().command) && ![...children.keys()].some(other => other !== child && other.canLeave().kind === 'BLOCK'))
      if (!children.has(child)) children.set(child, child.subscribe?.(projectChildren) ?? (() => {}))
      projectChildren()
      return () => { if (child.canLeave().kind === 'ALLOW') { children.get(child)?.(); children.delete(child); projectChildren() } }
    },
    recoverChild(key: string) { const child = [...children.keys()].find(child => `${child.identity?.domain}:${child.identity?.id}:${child.identity?.epoch}` === key); return child?.recover?.() },
    retire(forced = false) {
      const decision = base.canLeave(); if (!forced && decision.kind !== 'ALLOW') return decision
      const failures: unknown[] = []
      try { base.retire(forced) } catch (failure) { failures.push(failure) }
      for (const [child, release] of children) { try { child.retire?.(true) } catch (failure) { failures.push(failure) } try { release() } catch (failure) { failures.push(failure) } }
      children.clear(); if (failures.length) throw new OwnedResourceCleanupError(failures); return { kind: 'ALLOW' as const }
    },
    toggleBatch(batchId: string, selected: boolean) { const state = base.getSnapshot(); if (state.command.busy) return; patch({ selected: selected ? [...new Set([...state.selected, batchId])] : state.selected.filter(value => value !== batchId) }) },
    requestCommand(action: RunAction) { const state = base.getSnapshot(); if (!state.run || pendingCommand(state.command) || [...children.keys()].some(child => child.canLeave().kind === 'BLOCK')) return; if (action === 'cancel') patch({ confirming: { action, version: state.run.version } }); else void submit(action) },
    confirmCommand() { const state = base.getSnapshot(), confirmation = state.confirming; if (!confirmation || !state.run || confirmation.version !== state.run.version || !base.capture().isCurrent()) return; patch({ confirming: undefined }); void submit(confirmation.action) },
    cancelConfirmation() { patch({ confirming: undefined }) },
    recover: () => recoverOperation(operation).catch(() => {}),
    operation: () => operation,
  }
}
export type RunController = ReturnType<typeof createRunController>
