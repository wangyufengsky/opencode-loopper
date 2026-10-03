import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowActivity, WorkflowAttempt, WorkflowCommandEvidence, WorkflowInputs, WorkflowNode, WorkflowNodeSummary, WorkflowResult } from '@/types/domain'
import { readValues } from '@/components/workflow/values'
import { createRequirementScope, ownedState, requireIdentity } from './core'

export type NodeBody = 'input' | 'result' | 'activity' | 'command' | 'knowledge' | 'partial'
export function createNodeController(requirement: string, initial: { version: number; node: WorkflowNode; summary?: WorkflowNodeSummary }) {
  let context = initial, cancelPoll = () => {}, selectedGeneration = 0
  const owner = createRequirementScope('requirement-node', `${requirement}/${initial.node.id}`, { ...ownedState(), history: [] as WorkflowAttempt[], cursor: null as string | null,
    selected: '', metadata: null as WorkflowAttempt | null, definition: null as WorkflowNode | null, readable: false,
    input: null as WorkflowInputs | null, result: null as WorkflowResult | null, activity: null as WorkflowActivity | null, commandEvidence: null as WorkflowCommandEvidence | null,
    open: null as NodeBody | null, humanSummary: '', outcome: '', values: {} as Record<string, string>,
  })
  const details = () => owner.getSnapshot().definition ?? context.node
  const current = () => owner.getSnapshot().selected === context.summary?.latestAttemptId
  const terminal = () => ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(owner.getSnapshot().metadata?.state ?? '')
  const human = () => current() && owner.getSnapshot().metadata?.state === 'WAITING_INPUT' && details().kind === 'HUMAN'
  const runPath = (run: string) => `/workflows/requirements/${encodeURIComponent(requirement)}/nodes/${encodeURIComponent(context.node.id)}/attempts/${encodeURIComponent(run)}`
  function schedule() { cancelPoll(); if (!owner.active() || terminal()) return; cancelPoll = owner.delay(() => { if (document.hidden || owner.locked()) schedule(); else void refresh() }, 2500) }
  async function list(more = false) {
    if (!owner.active()) return
    const ticket = owner.ticket('attempt-list')
    try { const page = await workflowRuns.attempts(requirement, context.node.id, more ? owner.getSnapshot().cursor ?? '' : ''); if (ticket.current()) owner.patch({ history: more ? [...owner.getSnapshot().history, ...page.items] : page.items, cursor: page.nextCursor ?? null }) }
    catch (cause) { if (ticket.current()) owner.fail(cause, '执行历史无法读取，请重试。') }
  }
  async function refresh(strict = false, apply?: (callback: () => void) => boolean) {
    const run = owner.getSnapshot().selected, generation = selectedGeneration; if (!run || !owner.active() || owner.getSnapshot().loading && !strict) return
    const ticket = owner.ticket('attempt'); owner.patch({ loading: true })
    const same = () => ticket.current() && generation === selectedGeneration && owner.getSnapshot().selected === run
    try {
      const metadata = await workflowRuns.attempt(requirement, context.node.id, run)
      if (!same()) return
      requireIdentity(metadata.id, run, '节点记录')
      const project = () => owner.patch({ metadata, readable: true, error: '', history: owner.getSnapshot().history.map(item => item.id === metadata.id ? metadata : item) })
      if (apply) { if (!apply(project)) return } else project()
      if (!owner.getSnapshot().definition) {
        const definition = await workflowRuns.definition(requirement, context.node.id, run)
        if (!same()) return
        requireIdentity(definition.id, context.node.id, '冻结节点')
        if (apply) { if (!apply(() => owner.patch({ definition }))) return } else owner.patch({ definition })
      }
      if (owner.getSnapshot().open) await loadBody(owner.getSnapshot().open!)
    } catch (cause) { if (same()) { owner.patch({ readable: false }); owner.fail(cause, '节点记录无法读取，请重试。'); if (strict) throw cause } }
    finally { if (same()) { owner.patch({ loading: false }); schedule() } }
  }
  async function choose(run: string, discard = false) {
    if (!run || owner.getSnapshot().selected === run || owner.locked() || !discard && owner.canLeave().kind !== 'ALLOW') return false
    selectedGeneration++; cancelPoll(); owner.patch({ selected: run, metadata: null, definition: null, readable: false, loading: false, input: null, result: null, activity: null, commandEvidence: null, open: null, humanSummary: '', outcome: '', values: {}, dirty: false, error: '', draftRevision: owner.getSnapshot().draftRevision + 1 }); await refresh(); return true
  }
  async function loadBody(kind: NodeBody) {
    if (!owner.active()) return
    const s = owner.getSnapshot(), run = s.selected, generation = selectedGeneration; if (!run) return
    const ticket = owner.ticket(`body-${kind}`), same = () => ticket.current() && generation === selectedGeneration && owner.getSnapshot().selected === run
    try {
      if (kind === 'input' && !s.input) {
        const input = await workflowRuns.inputs(requirement, context.node.id, run)
        if (!same()) return
        requireIdentity(input.requirementId, requirement, '固定输入'); requireIdentity(input.nodeId, context.node.id, '固定输入节点')
        owner.patch({ input })
      }
      if (kind === 'result' && s.metadata?.deliveryAccepted && !s.result) {
        const result = await workflowRuns.result(requirement, context.node.id, run); if (!same()) return; requireIdentity(result.attemptId, run, '交付物'); owner.patch({ result })
      }
      if (kind === 'command' && (!s.commandEvidence || !terminal() || !s.commandEvidence.result)) {
        const commandEvidence = await workflowRuns.commandEvidence(requirement, context.node.id, run); if (!same()) return; requireIdentity(commandEvidence.attemptId, run, '执行证据'); owner.patch({ commandEvidence })
      }
      if (kind === 'activity') {
        const activity = await workflowRuns.activity(requirement, context.node.id, run); if (same()) owner.patch({ activity: activity.connected || !owner.getSnapshot().activity ? activity : { ...activity, parts: owner.getSnapshot().activity!.parts } })
      }
    } catch (cause) { if (same()) owner.fail(cause, '节点内容无法读取，请重试。') }
  }
  async function complete() {
    const s = owner.getSnapshot(); if (!human() || owner.locked() || !s.readable || s.loading || !s.metadata) return
    try {
      if (!s.humanSummary.trim()) throw new Error('请填写本次结果说明。')
      if (details().outcomes.length && !s.outcome) throw new Error('请选择本次业务结果。')
      const node = context.node.id, run = s.selected, body = { requestKey: crypto.randomUUID(), expectedVersion: context.version, attemptId: run, expectedAttemptVersion: s.metadata.version, delivery: { summary: s.humanSummary.trim(), outcome: s.outcome || null, outputs: readValues(details().outputs, s.values) } }
      owner.ticket('attempt')
      await owner.mutate({ label: '提交人工结果', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/nodes/${encodeURIComponent(node)}/human/complete`, method: 'POST', requestKey: body.requestKey, body, versions: { requirementVersion: context.version, attemptVersion: s.metadata.version } }, write: original => workflowRuns.complete(requirement, node, original),
        read: async (_receipt, read) => { await refresh(true, read.apply); if (!read.isCurrent()) return; await owner.refreshParent(); read.apply(() => owner.patch({ humanSummary: '', outcome: '', values: {}, dirty: false })) },
      })
    } catch (cause) { owner.fail(cause) }
  }
  async function process(action: 'stop' | 'resume') {
    const s = owner.getSnapshot(), metadata = s.metadata, isCommand = !!metadata?.commandState, version = isCommand ? metadata?.commandVersion : metadata?.modelVersion
    if (owner.locked() || s.loading || !s.readable || version == null || !current() || terminal()) return
    if (action === 'stop' && (metadata?.commandState || metadata?.modelState) === 'STOPPING') return
    const run = s.selected, node = context.node.id, body = { requestKey: crypto.randomUUID(), expectedVersion: version }
    owner.ticket('attempt')
    await owner.mutate({ label: action === 'stop' ? '停止节点' : '恢复原尝试', input: { endpoint: `${runPath(run)}/${isCommand ? 'command' : 'model'}/${action}`, method: 'POST', requestKey: body.requestKey, body, versions: { processVersion: version } }, write: original => (isCommand ? workflowRuns.commandAction : workflowRuns.modelAction)(requirement, node, run, action, original),
      read: async (_receipt, read) => { await refresh(true, read.apply); if (read.isCurrent()) await owner.refreshParent() },
    })
  }
  owner.setStart(() => { void list(); if (!owner.getSnapshot().selected && context.summary?.latestAttemptId) void choose(context.summary.latestAttemptId); else if (owner.getSnapshot().selected) void refresh() })
  return Object.assign(owner, { requirement, nodeId: initial.node.id, list, refresh, choose, loadBody, complete, process, details, human, current, terminal,
    discardDraft() { if (!owner.locked()) owner.patch({ humanSummary: '', outcome: '', values: {}, dirty: false, draftRevision: owner.getSnapshot().draftRevision + 1 }) },
    updateContext(value: typeof initial) { if (value.node.id !== initial.node.id) return; const previous = context.summary?.latestAttemptId; context = value; if (!owner.active() || owner.locked()) return; if (value.summary?.latestAttemptId && (!owner.getSnapshot().selected || owner.getSnapshot().selected === previous) && owner.canLeave().kind === 'ALLOW') void choose(value.summary.latestAttemptId); void list() },
    change(values: Partial<Pick<ReturnType<typeof owner.getSnapshot>, 'humanSummary' | 'outcome' | 'values'>>) { owner.edit(values) },
    show(open: NodeBody) { owner.patch({ open: owner.getSnapshot().open === open ? null : open }); if (owner.getSnapshot().open) void loadBody(open) },
  })
}
export type NodeController = ReturnType<typeof createNodeController>
