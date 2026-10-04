import { api } from '@/api/client'
import { workflowRuns } from '@/api/workflowRuns'
import type { AvailableModel, WorkflowCandidate, WorkflowExecution, WorkflowGraph, WorkflowLayout, WorkflowModel, WorkflowNode, WorkflowReceipt, WorkflowRequirement, WorkflowRunMode } from '@/types/domain'
import { autoLayout, clone, connect, emptyGraph, emptyLayout, newNode, removeNode } from '@/components/workflow/graph'
import { protectedNodes } from '@/components/workflow/planEditing'
import { replaceReviewSource } from '@/components/workflow/reviewSource'
import { preparePlanSave, type WorkflowPlanSave } from '@/components/workflow/planSave'
import { readValues } from '@/components/workflow/values'
import { createRequirementScope, ownedState, requireIdentity, type RequirementChild } from './core'
import type { LeaveDecision } from '@/foundation/contracts/types'

export function createRequirementController(id: string) {
  const children = new Set<RequirementChild>(); let exportOwner: RequirementChild | undefined
  const owner = createRequirementScope('requirement', id, { ...ownedState(), base: null as WorkflowRequirement | null, execution: null as WorkflowExecution | null,
    graph: emptyGraph(), layout: emptyLayout(), readable: false, refreshing: false, editing: false, proposal: null as WorkflowCandidate | null,
    selected: '', edgeId: '', connecting: '', surface: 'none' as 'none' | 'flow' | 'add' | 'tools' | 'nodes' | 'candidates', presets: false, exporting: false,
    inputValues: {} as Record<string, string>, checked: [] as string[], roleNames: {} as Record<string, string>,
    model: null as WorkflowModel | null, modelLoading: false, modelError: '', models: [] as AvailableModel[], modelCatalogLoading: false, notice: '',
    undo: [] as Array<{ graph: WorkflowGraph; layout: WorkflowLayout }>, redo: [] as Array<{ graph: WorkflowGraph; layout: WorkflowLayout }>,
    childRevision: 0, planStage: '' as '' | 'graph' | 'layout' | 'read', graphReceipt: null as WorkflowReceipt | null, layoutReceipt: null as WorkflowReceipt | null,
  }, state => {
    for (const child of children) { const decision = child.canLeave(); if (decision.kind === 'BLOCK') return decision }
    if (state.exporting) return { kind: 'BLOCK', reason: '模板预览仍持有当前计划，请先返回任务画布。', recoveryAction: '返回任务画布' }
    for (const child of children) { const decision = child.canLeave(); if (decision.kind !== 'ALLOW') return decision }
    return state.dirty ? { kind: 'CONFIRM_DISCARD', description: '当前计划有未保存的修改，仍要离开？', draftRevision: state.draftRevision } : { kind: 'ALLOW' }
  })
  let cancelPoll = () => {}, plan: WorkflowPlanSave | null = null, modelSerial = 0, modelAttempted = false, modelSource: 'none' | 'default' | 'control' | 'user' = 'none'
  const state = () => owner.getSnapshot().execution?.execution.state ?? owner.getSnapshot().base?.state
  const terminal = () => ['COMPLETED', 'FAILED', 'CANCELLED'].includes(state() ?? '')
  const beforeStart = () => ['PLANNING', 'PENDING_START'].includes(state() ?? '')
  const inputsFrozen = () => !!owner.getSnapshot().execution?.control.configured || !!owner.getSnapshot().execution?.execution.nodes.some(item => item.attemptCount > 0)
  const proposalReadonly = () => { const s = owner.getSnapshot(), p = s.proposal; return !!p && (p.state !== 'PENDING' || p.stale || p.baseRevision !== s.base?.revision || terminal() || state() === 'STOPPING') }
  const planning = () => (beforeStart() || owner.getSnapshot().editing) && !proposalReadonly() && state() !== 'STOPPING'
  const protectedKeys = () => { const s = owner.getSnapshot(); return s.base && !beforeStart() ? protectedNodes(s.base.graph, s.execution?.execution.nodes ?? [], s.proposal) : new Set<string>() }
  const canStartWrite = (caller?: RequirementChild) => owner.capture().isCurrent() && !owner.locked() && (!owner.getSnapshot().exporting || caller === exportOwner) && [...children].every(child => child === caller || child.canLeave().kind === 'ALLOW')
  owner.setWriteGate(() => canStartWrite())
  const locked = () => !canStartWrite() || owner.getSnapshot().loading || !owner.getSnapshot().readable || !!plan
  function accept(value: WorkflowRequirement) {
    requireIdentity(value.id, id, '需求计划')
    plan = null
    owner.patch({ base: value, graph: clone(value.graph), layout: clone(value.layout), editing: false, proposal: null, undo: [], redo: [], dirty: false, draftRevision: owner.getSnapshot().draftRevision + 1 })
  }
  function schedule() {
    cancelPoll()
    if (!owner.active()) return
    cancelPoll = owner.delay(() => { if (document.hidden || owner.locked() || [...children].some(child => child.canLeave().kind === 'BLOCK')) schedule(); else void refresh() }, 2500)
  }
  function adopt(model: WorkflowModel | null) {
    if (!model || modelSource === 'user') return
    modelSerial++; modelSource = 'control'; owner.patch({ model: clone(model), modelLoading: false, modelError: '' })
  }
  async function refresh(strict = false, apply?: (callback: () => void) => boolean, minimum?: { revision: number; version: number; controlVersion?: number; state?: string }) {
    if (!owner.active() || owner.getSnapshot().refreshing && !strict) return
    const ticket = owner.ticket('execution'); owner.patch({ refreshing: true })
    try {
      const value = await workflowRuns.execution(id)
      if (!ticket.current()) return
      requireIdentity(value.execution.id, id, '执行状态'); requireIdentity(value.control.id, id, '派发状态')
      if (minimum && (value.execution.revision < minimum.revision || value.execution.version < minimum.version || minimum.controlVersion !== undefined && value.control.controlVersion < minimum.controlVersion || minimum.state && value.execution.version === minimum.version && value.execution.state !== minimum.state)) throw new Error('执行读取尚未追上已接受的原回执，请继续读取原结果。')
      const s = owner.getSnapshot(), previous = s.execution
      if (previous && (value.execution.revision < previous.execution.revision || value.execution.version < previous.execution.version || value.control.controlVersion < previous.control.controlVersion || value.control.version < previous.control.version)) return
      if (s.base && value.execution.revision !== s.base.revision) {
        if (s.dirty) throw new Error('计划已有新版本，当前草稿已保留，请重新加载后继续。')
        const updated = await workflowRuns.get(id)
        if (!ticket.current()) return
        requireIdentity(updated.id, id)
        if (updated.revision !== value.execution.revision) throw new Error('计划读取尚未追上执行版本，请保留原计划并重新读取。')
        if (apply) { if (!apply(() => accept(updated))) return } else accept(updated)
      }
      const project = () => { owner.patch({ execution: value, readable: true, error: '', checked: owner.getSnapshot().checked.filter(key => value.control.checkpoints.some(checkpoint => checkpoint.attemptId === key)) }); adopt(value.control.model) }
      if (apply) apply(project); else project()
      void initializeModel()
    } catch (cause) { if (ticket.current()) { owner.patch({ readable: false }); owner.fail(cause, '执行状态暂时无法读取，请重试。'); if (strict) throw cause } }
    finally { if (ticket.current()) { owner.patch({ refreshing: false }); schedule() } }
  }
  async function load() {
    if (owner.canLeave().kind !== 'ALLOW') return
    cancelPoll(); const ticket = owner.ticket('plan'); owner.patch({ loading: true, readable: false, error: '' })
    try {
      const value = await workflowRuns.get(id)
      if (!ticket.current()) return
      accept(value); await refresh(true)
    } catch (cause) { if (ticket.current()) owner.fail(cause, '需求计划无法读取，请重新加载。') }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function initializeModel(force = false) {
    const s = owner.getSnapshot()
    if (!s.readable || terminal() || !s.graph.nodes.some(node => node.kind === 'WORK') || s.model || s.modelLoading || modelAttempted && !force) return
    modelAttempted = true; const ticket = owner.ticket('default-model'), serial = ++modelSerial
    owner.patch({ modelLoading: true, modelError: '' })
    try {
      const settings = await api.getSettings()
      if (!ticket.current() || serial !== modelSerial || owner.getSnapshot().model) return
      if (!settings.openCode.provider || !settings.openCode.model) throw new Error('尚未配置默认执行模型，请在“需求与资料”中选择模型。')
      modelSource = 'default'; owner.patch({ model: { providerId: settings.openCode.provider, modelId: settings.openCode.model, thinking: null } })
    } catch { if (ticket.current() && serial === modelSerial && !owner.getSnapshot().model) owner.patch({ modelError: '默认执行模型暂时无法读取，请重试或在“需求与资料”中选择模型。' }) }
    finally { if (ticket.current() && serial === modelSerial) owner.patch({ modelLoading: false }) }
  }
  async function loadModels() {
    const ticket = owner.ticket('model-catalog'); owner.patch({ modelCatalogLoading: true })
    try { const models = await api.getSettingsModels(); if (ticket.current()) owner.patch({ models, modelError: '' }) }
    catch { if (ticket.current()) owner.patch({ modelError: '模型列表暂时无法读取，请检查运行环境后重试。' }) }
    finally { if (ticket.current()) owner.patch({ modelCatalogLoading: false }) }
  }
  function change(graph: WorkflowGraph, layout = owner.getSnapshot().layout) {
    if (!planning() || locked()) return false
    const s = owner.getSnapshot()
    owner.edit({ graph: clone(graph), layout: clone(layout), undo: [...s.undo.slice(-99), clone({ graph: s.graph, layout: s.layout })], redo: [] }); void initializeModel(); return true
  }
  const graphDirty = () => JSON.stringify(owner.getSnapshot().graph) !== JSON.stringify(owner.getSnapshot().base?.graph)
  async function save() {
    const s = owner.getSnapshot()
    if (!s.base || !s.execution || !canStartWrite() || !s.readable || proposalReadonly()) return
    if (s.proposal && !s.proposal.sourceCompleted && !s.execution.execution.nodes.some(node => node.nodeKey === s.proposal!.nodeKey && node.latestAttemptId === s.proposal!.attemptId && node.state === 'SUCCEEDED')) { owner.fail(new Error('来源节点尚未成功收尾，请等待后再应用候选。')); return }
    if (!plan) plan = preparePlanSave({ ...s.base, version: s.execution.execution.version, state: s.execution.execution.state }, s.graph, s.layout, s.proposal)
    const operation = plan, current = owner.capture()
    owner.ticket('execution')
    if (!operation.graphReceipt) {
      owner.patch({ planStage: 'graph' })
      const body = { requestKey: operation.graphKey, expectedVersion: operation.version, expectedRevision: operation.revision, graph: operation.graph, ...(operation.candidate ? { expectedCandidateVersion: operation.candidate.version } : {}) }
      await owner.mutate({ label: '保存计划结构', input: { endpoint: operation.candidate ? `/workflows/requirements/${encodeURIComponent(id)}/candidates/${encodeURIComponent(operation.candidate.id)}/apply` : `/workflows/requirements/${encodeURIComponent(id)}/plan${operation.active ? '/apply' : ''}`, method: operation.candidate || operation.active ? 'POST' : 'PUT', requestKey: body.requestKey, body, versions: { version: operation.version, revision: operation.revision, ...(operation.candidate ? { candidateVersion: operation.candidate.version } : {}) } },
        write: original => operation.candidate ? workflowRuns.applyCandidate(id, operation.candidate.id, { ...original, expectedCandidateVersion: operation.candidate.version }) : (operation.active ? workflowRuns.applyPlan : workflowRuns.revise)(id, original),
        read: async (receipt, context) => { requireIdentity(receipt.id, id, '计划回执'); if (receipt.revision < operation.revision) throw new Error('计划回执早于原版本，请保留原身份。'); context.apply(() => { operation.graphReceipt = receipt; owner.patch({ graphReceipt: receipt }) }) },
      })
      if (current.isCurrent() && !operation.graphReceipt && owner.getSnapshot().command.phase === 'SETTLED' && !owner.getSnapshot().command.accepted) { plan = null; owner.patch({ planStage: '' }); return }
    }
    if (!current.isCurrent() || !operation.graphReceipt || owner.locked()) return
    if (!operation.layoutReceipt) {
      owner.patch({ planStage: 'layout', graphReceipt: operation.graphReceipt })
      const body = { requestKey: operation.layoutKey, expectedRevision: operation.graphReceipt.revision, expectedLayoutVersion: operation.graphReceipt.layoutVersion, layout: operation.layout }
      await owner.mutate({ label: '保存计划布局', input: { endpoint: `/workflows/requirements/${encodeURIComponent(id)}/layout`, method: 'PUT', requestKey: body.requestKey, body, versions: { revision: body.expectedRevision, layoutVersion: body.expectedLayoutVersion } }, write: original => workflowRuns.layout(id, original), rejected: () => false,
        read: async (receipt, context) => {
          requireIdentity(receipt.id, id, '布局回执'); if (receipt.revision !== operation.graphReceipt!.revision || receipt.layoutVersion < body.expectedLayoutVersion) throw new Error('布局回执与原计划版本不一致，请保留原身份。')
          if (!context.apply(() => { operation.layoutReceipt = receipt; owner.patch({ layoutReceipt: receipt, planStage: 'read' }) })) return
          const value = await workflowRuns.get(id)
          if (!context.isCurrent()) return
          requireIdentity(value.id, id); if (value.revision < receipt.revision || value.layoutVersion < receipt.layoutVersion) throw new Error('读取结果早于已接受的布局，请重新读取原结果。')
          if (!context.apply(() => { accept(value); owner.patch({ planStage: '', graphReceipt: operation.graphReceipt, layoutReceipt: receipt }) })) return
          await refresh(true, context.apply, receipt); context.apply(() => owner.patch({ notice: operation.active && operation.changesPlan ? '计划已应用，请选择执行方式继续。' : '计划已保存。' }))
        },
      })
    }
  }
  async function confirm() {
    const s = owner.getSnapshot(); if (!s.base || !s.execution || locked() || graphDirty() || state() !== 'PLANNING') return
    owner.ticket('execution')
    const body = { requestKey: crypto.randomUUID(), expectedVersion: s.execution.execution.version }, layout = clone(s.layout), layoutChanged = JSON.stringify(layout) !== JSON.stringify(s.base.layout)
    await owner.mutate({ label: '确认计划', input: { endpoint: `/workflows/requirements/${encodeURIComponent(id)}/confirm`, method: 'POST', requestKey: body.requestKey, body, versions: { version: body.expectedVersion } }, write: original => workflowRuns.confirm(id, original),
      read: async (receipt, context) => { requireIdentity(receipt.id, id); const value = await workflowRuns.get(id); requireIdentity(value.id, id, '确认后的计划'); if (value.revision < receipt.revision || value.version < receipt.version || value.version === receipt.version && value.state !== receipt.state) throw new Error('计划读取尚未追上已接受的原回执，请继续读取原结果。'); if (!context.apply(() => { accept(value); if (layoutChanged) owner.patch({ layout, dirty: true }) })) return; await refresh(true, context.apply, receipt); context.apply(() => owner.patch({ notice: '计划已确认，选择执行方式即可开始。' })) },
    })
  }
  const executable = () => { const s = owner.getSnapshot(); return !!s.base && !locked() && !s.editing && !s.proposal && !graphDirty() && s.execution?.control.reasonCode !== 'WORKFLOW_PLAN_REVIEW_REQUIRED' && s.execution?.control.checkpoints.every(value => s.checked.includes(value.attemptId)) && ['PENDING_START', 'RUNNING', 'PAUSED', 'STALLED'].includes(state() ?? '') }
  async function run(mode: WorkflowRunMode, target: string | null = null) {
    const s = owner.getSnapshot(), control = s.execution?.control
    if (!executable() || !control || s.modelLoading && (mode !== 'SINGLE' || s.graph.nodes.find(node => node.id === target)?.kind === 'WORK')) return
    try {
      if (mode !== 'CONTINUOUS' && !target) throw new Error('请先选择一个节点。')
      owner.ticket('execution')
      const body = { requestKey: crypto.randomUUID(), expectedVersion: control.version, expectedControlVersion: control.controlVersion, mode, targetKey: target, inputs: inputsFrozen() ? null : readValues(s.graph.inputs, s.inputValues), model: clone(s.model), checkpointAttempts: control.checkpoints.map(checkpoint => checkpoint.attemptId) }
      await owner.mutate({ label: '执行流程', input: { endpoint: `/workflows/requirements/${encodeURIComponent(id)}/control/start`, method: 'POST', requestKey: body.requestKey, body, versions: { version: control.version, controlVersion: control.controlVersion } }, write: original => workflowRuns.start(id, original), read: async (receipt, context) => { requireIdentity(receipt.id, id); await refresh(true, context.apply, { revision: receipt.revision, version: receipt.version, controlVersion: receipt.controlVersion }) } })
    } catch (cause) { owner.fail(cause) }
  }
  async function pause(editing = false) {
    const s = owner.getSnapshot(), control = s.execution?.control; if (!control || locked()) return
    owner.ticket('execution')
    const body = { requestKey: crypto.randomUUID(), expectedControlVersion: control.controlVersion }
    await owner.mutate({ label: editing ? '暂停派发并调整计划' : '暂停派发', input: { endpoint: `/workflows/requirements/${encodeURIComponent(id)}/control/pause`, method: 'POST', requestKey: body.requestKey, body, versions: { controlVersion: control.controlVersion } }, write: original => workflowRuns.pause(id, original), read: async (receipt, context) => { requireIdentity(receipt.id, id); await refresh(true, context.apply, { revision: receipt.revision, version: receipt.version, controlVersion: receipt.controlVersion }); if (editing) context.apply(() => owner.patch({ editing: true })) } })
  }
  async function beginEdit() {
    const s = owner.getSnapshot(); if (locked() || terminal() || state() === 'STOPPING' || beforeStart() || s.editing) return
    if (s.execution?.control.configured && ['ACTIVE', 'WAITING'].includes(s.execution.control.state)) await pause(true)
    else { const token = owner.capture(); await refresh(); if (token.isCurrent() && owner.getSnapshot().readable && !terminal()) owner.patch({ editing: true }) }
  }
  async function cancel() {
    const s = owner.getSnapshot(); if (!beforeStart() || !s.execution || locked()) return
    owner.ticket('execution')
    const body = { requestKey: crypto.randomUUID(), expectedVersion: s.execution.execution.version }
    await owner.mutate({ label: '取消需求', input: { endpoint: `/workflows/requirements/${encodeURIComponent(id)}/cancel`, method: 'POST', requestKey: body.requestKey, body }, write: original => workflowRuns.cancel(id, original), read: async (receipt, context) => { requireIdentity(receipt.id, id); await refresh(true, context.apply, { revision: receipt.revision, version: receipt.version, state: receipt.state }) } })
  }
  function contextDecision(): LeaveDecision { for (const child of children) { const d = child.canLeave(); if (d.kind !== 'ALLOW') return d } return owner.locked() ? owner.canLeave() : { kind: 'ALLOW' } }
  owner.setStart(() => { void load() })
  const ownRetire = owner.retire.bind(owner), recoverOriginal = owner.recover.bind(owner)
  return Object.assign(owner, { id, state, terminal, beforeStart, inputsFrozen, proposalReadonly, planning, protectedKeys, canStartWrite, lockedForUi: locked, graphDirty, executable, load, refresh, save, confirm, run, pause, beginEdit, cancel, initializeModel, loadModels, contextDecision,
    registerChild(child: RequirementChild) { children.add(child); let previous = JSON.stringify(child.canLeave()); const unsubscribe = child.subscribe?.(() => { const next = JSON.stringify(child.canLeave()); if (next !== previous) { previous = next; owner.patch({ childRevision: owner.getSnapshot().childRevision + 1 }) } }); return () => { unsubscribe?.(); children.delete(child) } },
    setExportOwner(child: RequirementChild | undefined) { exportOwner = child },
    discardContextDrafts() { if (contextDecision().kind === 'BLOCK') return false; for (const child of children) if (child.canLeave().kind === 'CONFIRM_DISCARD') child.discardDraft?.(); return contextDecision().kind === 'ALLOW' },
    retire(forced = false) { const decision = owner.canLeave(); if (!forced && decision.kind !== 'ALLOW') return decision; const failures: unknown[] = []; try { ownRetire(forced) } catch (cause) { failures.push(cause) }; for (const child of children) try { child.retire(true) } catch (cause) { failures.push(cause) }; try { cancelPoll() } catch (cause) { failures.push(cause) }; modelSerial++; if (failures.length) { const error = new Error('需求资源清理失败，所有作用域均已逐项退休。'); Object.assign(error, { failures }); throw error }; return { kind: 'ALLOW' } as const },
    async recover() { await recoverOriginal(); if (owner.capture().isCurrent() && plan && plan.graphReceipt && !plan.layoutReceipt && !owner.locked()) await save() },
    change, patchNode(node: WorkflowNode) { if (!protectedKeys().has(node.id)) change(replaceReviewSource(owner.getSnapshot().graph, node, !inputsFrozen())) },
    history(back: boolean) { if (locked() || !planning()) return; const s = owner.getSnapshot(), from = [...(back ? s.undo : s.redo)], item = from.pop(); if (!item) return; owner.edit({ graph: item.graph, layout: item.layout, undo: back ? from : [...s.undo, clone({ graph: s.graph, layout: s.layout })], redo: back ? [...s.redo, clone({ graph: s.graph, layout: s.layout })] : from }) },
    select(selected: string, edgeId = '') { if (contextDecision().kind !== 'ALLOW') return false; owner.patch({ selected, edgeId, surface: 'none', presets: false, connecting: edgeId ? '' : owner.getSnapshot().connecting }); return true },
    dismiss() { if (contextDecision().kind !== 'ALLOW') return false; owner.patch({ selected: '', edgeId: '', connecting: '', surface: 'none', presets: false }); return true },
    toggle(surface: ReturnType<typeof owner.getSnapshot>['surface']) { if (contextDecision().kind !== 'ALLOW') return; owner.patch({ surface: owner.getSnapshot().surface === surface ? 'none' : surface, selected: '', edgeId: '', connecting: '', presets: false }) },
    add(module: 'free.readonly' | 'free.write' | 'human') { const s = owner.getSnapshot(), node = newNode(module); if (change({ ...s.graph, nodes: [...s.graph.nodes, node] }, { ...s.layout, positions: { ...s.layout.positions, [node.id]: { x: 40, y: s.graph.nodes.length * 150 } } })) owner.patch({ selected: node.id, surface: 'none' }) },
    addPreset(graph: WorkflowGraph, node: WorkflowNode) { const s = owner.getSnapshot(); if (change(graph, { ...s.layout, positions: { ...s.layout.positions, [node.id]: autoLayout(graph)[node.id]! } })) owner.patch({ selected: node.id, presets: false }) },
    joinPair(from: string, to: string) { if (protectedKeys().has(to)) { owner.fail(new Error('这个节点已执行或属于保留区域，不能改变它的前置依赖。')); return }; try { if (change(connect(owner.getSnapshot().graph, from, to))) owner.patch({ connecting: '', error: '' }) } catch (cause) { owner.fail(cause) } },
    join(key: string) { const from = owner.getSnapshot().connecting; if (from) this.joinPair(from, key); else if (planning() && !locked()) owner.patch({ connecting: key }) },
    canRemove(key: string) { const s = owner.getSnapshot(); return beforeStart() || !!s.base && !s.execution?.execution.nodes.some(run => run.nodeKey === key && run.state === 'ACTIVE') && !s.execution?.control.checkpoints.some(check => check.nodeKey === key) && !protectedNodes(s.base.graph, (s.execution?.execution.nodes ?? []).filter(run => run.nodeKey !== key), s.proposal).has(key) },
    remove(key: string) { if (locked() || !planning() || !this.canRemove(key)) return; try { const s = owner.getSnapshot(), graph = removeNode(s.graph, key), layout = clone(s.layout); delete layout.positions[key]; change(graph, layout); owner.patch({ selected: '', edgeId: '' }) } catch (cause) { owner.fail(cause) } },
    edgeOutcome(key: string, outcome: string | null) { const s = owner.getSnapshot(), edge = s.graph.edges.find(item => item.id === key); if (!edge || !planning() || locked() || protectedKeys().has(edge.to) || outcome !== null && !s.graph.nodes.find(item => item.id === edge.from)?.outcomes.includes(outcome)) return false; return change({ ...s.graph, edges: s.graph.edges.map(item => item.id === key ? { ...item, outcome } : item) }) },
    removeEdge(key: string) { const s = owner.getSnapshot(), edge = s.graph.edges.find(item => item.id === key); if (edge && !protectedKeys().has(edge.to)) { change({ ...s.graph, edges: s.graph.edges.filter(item => item.id !== key) }); owner.patch({ edgeId: '' }) } },
    setLayout(layout: WorkflowLayout) { if (locked()) return; if (planning()) change(owner.getSnapshot().graph, layout); else owner.edit({ layout: clone(layout) }) },
    review(proposal: WorkflowCandidate) { const s = owner.getSnapshot(); if (!s.base || owner.canLeave().kind !== 'ALLOW') return; const positions = { ...autoLayout(proposal.graph), ...s.base.layout.positions }; Object.keys(positions).forEach(key => { if (!proposal.graph.nodes.some(node => node.id === key)) delete positions[key] }); owner.edit({ proposal: clone(proposal), graph: clone(proposal.graph), layout: { ...clone(s.base.layout), positions }, editing: true, selected: '', edgeId: '', surface: 'none', undo: [], redo: [] }) },
    discard() { if (!locked() && owner.getSnapshot().base) accept(owner.getSnapshot().base!); owner.patch({ notice: '', selected: '', edgeId: '', surface: 'none' }) },
    setInput(name: string, value: string, caller?: RequirementChild) { if (!inputsFrozen() && !owner.locked() && (caller ? children.has(caller) && canStartWrite(caller) : !locked())) owner.patch({ inputValues: { ...owner.getSnapshot().inputValues, [name]: value } }) },
    chooseModel(model: WorkflowModel | null) { if (locked()) return; modelSerial++; modelSource = 'user'; owner.patch({ model: clone(model), modelLoading: false, modelError: '' }) },
    checkpoint(id: string, checked: boolean) { if (locked()) return; const values = new Set(owner.getSnapshot().checked); checked ? values.add(id) : values.delete(id); owner.patch({ checked: [...values] }) },
  })
}
export type RequirementController = ReturnType<typeof createRequirementController>
