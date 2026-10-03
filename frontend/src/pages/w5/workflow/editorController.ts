import { ApiError } from '@/api/client'
import { workflowApi } from '@/api/workflow'
import { autoLayout, clone, connect, emptyGraph, emptyLayout, newNode, removeNode } from '@/components/workflow/graph'
import { replaceReviewSource } from '@/components/workflow/reviewSource'
import { prepareCopy, prepareSave, type WorkflowDraft, type WorkflowSave } from '@/components/workflow/save'
import { createW4Owner } from '@/pages/w4/shared/core'
import { createOperationOwner, type AcceptedHandoff, type OperationOwner, type ReadContext } from '@/foundation/contracts/receipt'
import type { NavigationRequest } from '@/foundation/contracts/navigation'
import type { LeaveDecision } from '@/foundation/contracts/types'
import type { WorkflowDiagnostic, WorkflowGraph, WorkflowLayout, WorkflowNode, WorkflowReceipt, WorkflowTemplate } from '@/types/domain'
import { userFacingError } from '@/utils/displayLabels'

export type EditorContext = 'none' | 'flow' | 'node' | 'edge' | 'add' | 'tools' | 'nodes' | 'presets'
export interface EditorSnapshot {
  base: WorkflowTemplate | null; draft: WorkflowDraft; ready: boolean; loading: boolean; validating: boolean; error: string; notice: string; diagnostics: WorkflowDiagnostic[]
  selected: string; selectedEdge: string; connecting: string; context: EditorContext; roleNames: Record<string, string>; draftRevision: number; dirty: boolean; undoCount: number; redoCount: number
  savePhase: 'IDLE' | 'SENDING' | 'UNKNOWN' | 'ACCEPTED_READBACK' | 'PARTIAL_REJECTION' | 'SETTLED'; saveBusy: boolean; saveError: string; conflict: boolean; graphAccepted: boolean; layoutAccepted: boolean; savedId: string
}
const fresh = (): WorkflowDraft => ({ title: '未命名流程', description: '', graph: emptyGraph(), layout: emptyLayout() })
const destination = (id: string) => `/workflows/${encodeURIComponent(id)}`
function validReceipt(value: Readonly<WorkflowReceipt>, expectedId?: string) {
  if (!value || typeof value.id !== 'string' || !value.id || expectedId && value.id !== expectedId || ![value.revision, value.version, value.layoutVersion].every(v => Number.isSafeInteger(v) && v >= 0) || value.revision < 1 || value.state !== 'ACTIVE') throw new Error('保存回执与原流程身份不一致，请保留原操作并重新读取。')
}
export function createWorkflowEditorController(options: { id?: string; goAccepted?: (to: string, permit: AcceptedHandoff) => Promise<boolean> } = {}) {
  const id = options.id || '', initial: EditorSnapshot = { base: null, draft: fresh(), ready: false, loading: false, validating: false, error: '', notice: '', diagnostics: [], selected: '', selectedEdge: '', connecting: '', context: 'none', roleNames: {}, draftRevision: 0, dirty: false, undoCount: 0, redoCount: 0, savePhase: 'IDLE', saveBusy: false, saveError: '', conflict: false, graphAccepted: false, layoutAccepted: false, savedId: '' }
  const env = createW4Owner('workflow-editor', id || 'new', initial)
  const { base, patch, ticket } = env
  let baseline = JSON.stringify(initial.draft), undo: WorkflowDraft[] = [], redo: WorkflowDraft[] = [], saving: WorkflowSave | undefined
  let graphOperation: OperationOwner<unknown, WorkflowReceipt> | undefined, layoutOperation: OperationOwner<unknown, WorkflowReceipt> | undefined, finalOperation: OperationOwner<unknown, WorkflowReceipt> | undefined
  let completed: WorkflowTemplate | undefined, running: Promise<void> | undefined
  const active = () => base.capture().isCurrent()
  const locked = () => { const s = base.getSnapshot(); return !active() || !s.ready || s.loading || s.validating || !!saving || !!s.base?.builtin }
  function canLeave(request?: NavigationRequest): LeaveDecision {
    const s = base.getSnapshot()
    if (saving) {
      if (completed && request && finalOperation?.leaveRisk().permitsHandoff(request.handoff, request.destination)) return { kind: 'ALLOW' }
      return { kind: 'BLOCK', reason: s.saveBusy ? '保存正在发送，请等待结果。' : s.savePhase === 'PARTIAL_REJECTION' ? '图定义已接受，但布局已明确拒绝，请恢复原布局保存操作。' : '保存结果尚未结清，请恢复原保存操作。', recoveryAction: '恢复原保存操作' }
    }
    const risk = base.canLeave(request); if (risk.kind === 'BLOCK') return risk
    if (s.validating) return { kind: 'BLOCK', reason: '正在检查流程，请等待结果。', recoveryAction: '等待读取完成' }
    return s.dirty ? { kind: 'CONFIRM_DISCARD', description: '有未保存的流程修改，是否放弃后离开？', draftRevision: s.draftRevision } : { kind: 'ALLOW' }
  }
  function accept(value: WorkflowTemplate, final = false) {
    const draft = clone({ title: value.title, description: value.description, graph: value.graph, layout: value.layout }); baseline = JSON.stringify(draft); undo = []; redo = []
    patch({ base: value, draft, ready: true, diagnostics: value.diagnostics, dirty: false, undoCount: 0, redoCount: 0, conflict: false, error: '', notice: final ? '流程已保存' : '', draftRevision: base.getSnapshot().draftRevision + 1 })
  }
  async function load() {
    if (!active() || saving || base.getSnapshot().validating) return
    const read = ticket('load'); patch({ loading: true, ready: false, error: '', context: 'none', selected: '', selectedEdge: '', connecting: '' })
    try { if (!id) { if (read.current()) { const draft = fresh(); baseline = JSON.stringify(draft); undo = []; redo = []; patch({ draft, base: null, ready: true, dirty: false, diagnostics: [], undoCount: 0, redoCount: 0 }) }; return }
      const value = await workflowApi.get(id); if (!read.current()) return
      if (value.id !== id || !Number.isSafeInteger(value.revision) || value.revision < 1 || !value.graph || !value.layout) throw new Error('流程读取结果与当前身份不一致。')
      accept(value)
    } catch (cause) { if (read.current()) patch({ error: userFacingError(cause, '流程读取失败，请重试。'), ready: false }) } finally { if (read.current()) patch({ loading: false }) }
  }
  function change(next: WorkflowDraft) {
    if (locked()) return false
    const s = base.getSnapshot(); if (JSON.stringify(next) === JSON.stringify(s.draft)) return false
    undo = [...undo.slice(-99), clone(s.draft)]; redo = []; projectDraft(next); return true
  }
  function projectDraft(draft: WorkflowDraft) {
    const s = base.getSnapshot(), selected = draft.graph.nodes.some(n => n.id === s.selected) ? s.selected : '', selectedEdge = draft.graph.edges.some(e => e.id === s.selectedEdge) ? s.selectedEdge : '', connecting = draft.graph.nodes.some(n => n.id === s.connecting) ? s.connecting : ''
    patch({ draft, dirty: !s.base?.builtin && JSON.stringify(draft) !== baseline, diagnostics: [], notice: '', error: '', draftRevision: s.draftRevision + 1, undoCount: undo.length, redoCount: redo.length, selected, selectedEdge, connecting, context: s.context === 'node' && !selected || s.context === 'edge' && !selectedEdge ? 'none' : s.context })
  }
  function history(back: boolean) {
    if (locked()) return
    const stack = back ? undo : redo, value = stack.pop(); if (!value) return
    ;(back ? redo : undo).push(clone(base.getSnapshot().draft)); projectDraft(value)
  }
  function select(nodeId: string) { if (!base.getSnapshot().draft.graph.nodes.some(node => node.id === nodeId)) return; patch({ selected: nodeId, selectedEdge: '', context: 'node' }) }
  function selectEdge(edgeId: string) { if (!base.getSnapshot().draft.graph.edges.some(edge => edge.id === edgeId)) return; patch({ selected: '', selectedEdge: edgeId, connecting: '', context: 'edge' }) }
  function dismiss() { patch({ selected: '', selectedEdge: '', connecting: '', context: 'none' }) }
  function context(value: EditorContext) { const s = base.getSnapshot(); patch({ context: s.context === value ? 'none' : value, selected: '', selectedEdge: '', connecting: '' }) }
  function joinPair(from: string, to: string) { if (locked()) return; try { const s = base.getSnapshot(); change({ ...s.draft, graph: connect(s.draft.graph, from, to) }); patch({ connecting: '' }) } catch (cause) { patch({ error: userFacingError(cause, '暂时无法连接这些节点。'), connecting: '' }) } }
  function join(nodeId: string) { if (locked()) return; const s = base.getSnapshot(); if (!s.connecting) patch({ connecting: nodeId }); else { const from = s.connecting; joinPair(from, nodeId) } }
  function add(module: 'free.readonly' | 'free.write' | 'human') { if (locked()) return; const s = base.getSnapshot(), node = newNode(module), count = s.draft.graph.nodes.length; if (change({ ...s.draft, graph: { ...s.draft.graph, nodes: [...s.draft.graph.nodes, node] }, layout: { ...s.draft.layout, positions: { ...s.draft.layout.positions, [node.id]: { x: count * 40, y: count * 150 } } } })) select(node.id) }
  function insert(graph: WorkflowGraph, node: WorkflowNode) { const s = base.getSnapshot(); if (change({ ...s.draft, graph, layout: { ...s.draft.layout, positions: { ...s.draft.layout.positions, [node.id]: autoLayout(graph)[node.id]! } } })) select(node.id) }
  function remove(nodeId: string) { if (locked()) return; try { const s = base.getSnapshot(), graph = removeNode(s.draft.graph, nodeId), positions = { ...s.draft.layout.positions }; delete positions[nodeId]; if (change({ ...s.draft, graph, layout: { ...s.draft.layout, positions } })) dismiss() } catch (cause) { patch({ error: userFacingError(cause, '暂时无法删除节点。') }) } }
  const patchNode = (node: WorkflowNode) => { const s = base.getSnapshot(); change({ ...s.draft, graph: replaceReviewSource(s.draft.graph, node) }) }
  const graph = (value: WorkflowGraph) => change({ ...base.getSnapshot().draft, graph: value })
  function layout(value: WorkflowLayout) { const s = base.getSnapshot(); if (s.base?.builtin && !saving && s.ready) patch({ draft: { ...s.draft, layout: value } }); else change({ ...s.draft, layout: value }) }
  async function validate() { if (locked()) return; const read = ticket('validate'), original = clone(base.getSnapshot().draft.graph); patch({ validating: true, error: '' }); try { const diagnostics = await workflowApi.validate(original); if (read.current()) patch({ diagnostics, notice: diagnostics.some(d => d.severity === 'ERROR') ? '请根据提示调整流程。' : '流程检查通过' }) } catch (cause) { if (read.current()) patch({ error: userFacingError(cause, '流程检查失败，请重试。') }) } finally { if (read.current()) patch({ validating: false }) } }
  function publishOperation(operation: OperationOwner<unknown, WorkflowReceipt>) {
    if (!base.ownOperation(operation)) { operation.retire(true); throw new Error('原页面已离开，不能开始写入。') }
    operation.subscribe(() => { if (!active()) return; const s = operation.getSnapshot(); patch({ savePhase: s.phase, saveBusy: s.busy, saveError: s.error ? userFacingError(s.error, s.accepted ? '写入已接受，请读取原结果。' : '保存结果尚未确认，请恢复原身份。') : '', conflict: s.error instanceof ApiError && s.error.status === 409 }) })
    return operation
  }
  async function finalRead(receipt: Readonly<WorkflowReceipt>, read: ReadContext) {
    validReceipt(receipt, saving?.id || undefined)
    if (!read.isCurrent() || !active()) return
    const value = await workflowApi.get(receipt.id); if (!read.isCurrent() || !active()) return
    if (value.id !== receipt.id || !Number.isSafeInteger(value.revision) || !Number.isSafeInteger(value.version) || !Number.isSafeInteger(value.layoutVersion) || value.revision < receipt.revision || value.version < receipt.version || value.layoutVersion < receipt.layoutVersion || !value.graph || !value.layout) throw new Error('保存后的读取结果版本不一致，请继续读取原流程，不能再次发送已接受的写入。')
    completed = value
  }
  // These template APIs replay by request key before checking CAS, in one transaction.
  // A definite CAS rejection may keep the draft available for an explicit new copy.
  const rejected = (cause: unknown) => cause instanceof ApiError && [400, 401, 403, 404, 409, 422].includes(cause.status)
  async function perform(operation: OperationOwner<unknown, WorkflowReceipt>) { const s = operation.getSnapshot(); if (s.accepted) await operation.retryReadback(); else if (s.phase === 'UNKNOWN') await operation.recoverWrite(); else if (s.phase === 'IDLE') await operation.execute(); else if (s.error) throw s.error }
  function makeGraph(operation: WorkflowSave) {
    const original = operation.draft, isFinal = !operation.id
    const body = operation.copySource ? { requestKey: operation.graphKey, sourceRevision: operation.copySource.revision, title: original.title } : operation.id ? { requestKey: operation.graphKey, expectedVersion: operation.version, expectedRevision: operation.revision, title: original.title, description: original.description, graph: original.graph } : { requestKey: operation.graphKey, title: original.title, description: original.description, graph: original.graph, layout: original.layout }
    return publishOperation(createOperationOwner<unknown, WorkflowReceipt>({ owner: base.identity, label: '保存流程定义', input: { endpoint: operation.copySource ? `/workflows/templates/${operation.copySource.id}/copy` : operation.id ? `/workflows/templates/${operation.id}` : '/workflows/templates', method: operation.id ? 'PUT' : 'POST', requestKey: operation.graphKey, body }, capability: { kind: 'IDEMPOTENT_KEY' }, isDefinitiveRejection: rejected,
      write: identity => operation.copySource ? workflowApi.copy(operation.copySource.id, identity.body as Parameters<typeof workflowApi.copy>[1]) : operation.id ? workflowApi.revise(operation.id, identity.body as Parameters<typeof workflowApi.revise>[1]) : workflowApi.create(identity.body as Parameters<typeof workflowApi.create>[0]),
      read: async (receipt, read) => { validReceipt(receipt, operation.id || undefined); operation.graphReceipt = clone(receipt); if (active()) patch({ graphAccepted: true }); if (isFinal) { operation.layoutReceipt = clone(receipt); if (active()) patch({ layoutAccepted: true }); await finalRead(receipt, read) } }, handoffTarget: receipt => { validReceipt(receipt); return destination(receipt.id) },
    }))
  }
  function makeLayout(operation: WorkflowSave) {
    const receipt = operation.graphReceipt!; validReceipt(receipt, operation.id || undefined)
    const body = { requestKey: operation.layoutKey, expectedRevision: receipt.revision, expectedLayoutVersion: receipt.layoutVersion, layout: operation.draft.layout }
    return publishOperation(createOperationOwner<unknown, WorkflowReceipt>({ owner: base.identity, label: '保存流程布局', input: { endpoint: `/workflows/templates/${receipt.id}/layout`, method: 'PUT', requestKey: operation.layoutKey, body }, capability: { kind: 'IDEMPOTENT_KEY' }, isDefinitiveRejection: rejected,
      write: identity => workflowApi.layout(receipt.id, identity.body as Parameters<typeof workflowApi.layout>[1]), read: async (value, read) => { validReceipt(value, receipt.id); operation.layoutReceipt = clone(value); if (active()) patch({ layoutAccepted: true }); await finalRead(value, read) }, handoffTarget: value => { validReceipt(value, receipt.id); return destination(value.id) },
    }))
  }
  async function completeSave() {
    if (!completed || !finalOperation || !active()) return
    const value = completed
    if (value.id !== id && options.goAccepted) {
      patch({ savePhase: 'ACCEPTED_READBACK', saveBusy: false, savedId: value.id, saveError: '' })
      let moved = false
      try { moved = await options.goAccepted(destination(value.id), finalOperation.prepareHandoff()) } catch (cause) { if (active()) patch({ saveError: userFacingError(cause, '流程已保存，页面切换失败；请继续打开原已保存流程。') }); return }
      if (!active()) return
      if (!moved) { patch({ saveError: '流程已保存，页面切换未完成；请继续打开原已保存流程。' }); return }
    }
    accept(value, true); saving = undefined; graphOperation = undefined; layoutOperation = undefined; completed = undefined
    patch({ savePhase: 'SETTLED', saveBusy: false, saveError: '', savedId: value.id })
  }
  async function pipeline() {
    if (!saving || !active()) return
    if (completed) { await completeSave(); return }
    const operation = saving
    try {
      if (!operation.graphReceipt) { graphOperation ??= makeGraph(operation); if (!operation.id) finalOperation = graphOperation; await perform(graphOperation) }
      if (!active()) return
      if (!operation.layoutReceipt) { layoutOperation ??= makeLayout(operation); finalOperation = layoutOperation; await perform(layoutOperation) }
      else if (!completed && finalOperation) await perform(finalOperation)
      if (active() && completed) await completeSave()
    } catch (cause) {
      if (!active()) return
      const current = layoutOperation ?? graphOperation
      const state = current?.getSnapshot()
      patch({ saveBusy: false, saveError: userFacingError(cause, state?.accepted ? '写入已接受，请继续读取原结果。' : '保存结果尚未确认，请保留原身份。'), conflict: cause instanceof ApiError && cause.status === 409 })
      if (state?.phase === 'SETTLED' && !state.accepted) {
        if (layoutOperation && operation.graphReceipt) {
          // A rejected layout cannot undo the accepted graph. Retire only that
          // primitive; an explicit retry rebuilds it with the original key/body/CAS.
          layoutOperation.retire(); layoutOperation = undefined; finalOperation = undefined
          patch({ savePhase: 'PARTIAL_REJECTION', saveError: userFacingError(cause, '布局已明确拒绝，请按原请求键和原版本恢复布局保存。') })
        } else { saving = undefined; graphOperation = undefined; layoutOperation = undefined; finalOperation = undefined; patch({ savePhase: 'SETTLED' }) }
      }
    }
  }
  function save(asNew = false): Promise<void> {
    if (running) return running
    if (!active() || !base.getSnapshot().ready || base.getSnapshot().validating || base.getSnapshot().loading) return Promise.resolve()
    if (asNew && saving) return Promise.resolve()
    if (!saving) {
      const s = base.getSnapshot(); saving = s.base?.builtin ? prepareCopy(s.base) : prepareSave(s.draft, asNew ? null : s.base)
      completed = undefined; graphOperation = undefined; layoutOperation = undefined; finalOperation = undefined
      patch({ savePhase: 'SENDING', saveBusy: true, saveError: '', conflict: false, graphAccepted: !!saving.graphReceipt, layoutAccepted: false })
    }
    running = pipeline().finally(() => { running = undefined }); return running
  }
  env.setStart(() => { patch({ validating: false }); if (!base.getSnapshot().ready && !saving) void load(); const listener = (event: BeforeUnloadEvent) => { if (canLeave().kind !== 'ALLOW') { event.preventDefault(); event.returnValue = '' } }; window.addEventListener('beforeunload', listener); env.own(() => window.removeEventListener('beforeunload', listener)) })
  return { ...base, canLeave, load, locked, save, recover: () => save(), validate, change, patchNode, graph, layout, add, insert, remove, select, selectEdge, context, dismiss, join, joinPair, history,
    roleLabel(id: string, label: string) { if (active()) patch({ roleNames: { ...base.getSnapshot().roleNames, [id]: label } }) },
    title(value: string) { change({ ...base.getSnapshot().draft, title: value }) }, description(value: string) { change({ ...base.getSnapshot().draft, description: value }) },
    removeEdge(edgeId: string) { const s = base.getSnapshot(); if (change({ ...s.draft, graph: { ...s.draft.graph, edges: s.draft.graph.edges.filter(e => e.id !== edgeId) } })) dismiss() },
    edgeOutcome(edgeId: string, outcome: string) { const s = base.getSnapshot(); graph({ ...s.draft.graph, edges: s.draft.graph.edges.map(edge => edge.id === edgeId ? { ...edge, outcome: outcome || null } : edge) }) },
    autoLayout() { const s = base.getSnapshot(); change({ ...s.draft, layout: { ...s.draft.layout, positions: autoLayout(s.draft.graph) } }) },
    originalSave: () => saving,
    retire(forced = false) { const decision = canLeave(); if (!forced && decision.kind !== 'ALLOW') return decision; return base.retire(forced) },
  }
}
export type WorkflowEditorController = ReturnType<typeof createWorkflowEditorController>
