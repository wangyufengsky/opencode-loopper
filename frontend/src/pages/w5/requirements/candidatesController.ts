import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowCandidate, WorkflowCandidateSummary } from '@/types/domain'
import { createRequirementScope, ownedState, requireIdentity } from './core'

export function createCandidatesController(requirement: string) {
  const owner = createRequirementScope('requirement-candidates', requirement, { ...ownedState(), rows: [] as WorkflowCandidateSummary[], cursor: null as string | null, filter: 'PENDING', selected: null as WorkflowCandidate | null, reason: '', open: false })
  async function list(more = false, strict = false, apply?: (callback: () => void) => boolean) {
    if (!owner.active()) return
    const ticket = owner.ticket('candidates'); owner.patch({ loading: true })
    try { const s = owner.getSnapshot(), page = await workflowRuns.candidates(requirement, s.filter, more ? s.cursor ?? '' : ''); if (!ticket.current()) return; const project = () => owner.patch({ rows: more ? [...s.rows, ...page.items] : page.items, cursor: page.nextCursor ?? null, error: '' }); if (apply) apply(project); else project() }
    catch (cause) { if (ticket.current()) { owner.fail(cause, '候选列表无法读取，请重试。'); if (strict) throw cause } }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function read(key: string) {
    if (!owner.active() || owner.canLeave().kind !== 'ALLOW') return
    const ticket = owner.ticket('candidate'); owner.patch({ loading: true, selected: null })
    try { const selected = await workflowRuns.candidate(requirement, key); if (ticket.current()) { requireIdentity(selected.id, key, '候选内容'); owner.patch({ selected, reason: '', error: '', dirty: false }) } }
    catch (cause) { if (ticket.current()) owner.fail(cause, '候选内容无法读取，请重新选择。') }
    finally { if (ticket.current()) owner.patch({ loading: false }) }
  }
  async function reject() {
    const s = owner.getSnapshot(), selected = s.selected; if (!selected || selected.state !== 'PENDING' || owner.locked() || s.loading) return
    const body = { requestKey: crypto.randomUUID(), expectedCandidateVersion: selected.version, reason: s.reason.trim() }
    await owner.mutate({ label: '退回候选计划', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/candidates/${encodeURIComponent(selected.id)}/reject`, method: 'POST', requestKey: body.requestKey, body, versions: { candidateVersion: selected.version } }, write: original => workflowRuns.rejectCandidate(requirement, selected.id, original),
      read: async (receipt, context) => { requireIdentity(receipt.id, requirement, '候选操作回执'); await list(false, true, context.apply); if (!context.isCurrent()) return; await owner.refreshParent(); context.apply(() => owner.patch({ selected: null, reason: '', dirty: false })) },
    })
  }
  return Object.assign(owner, { list, read, reject,
    show() { owner.patch({ open: true }); if (!owner.getSnapshot().rows.length) void list() },
    discardDraft() { if (!owner.locked()) owner.patch({ reason: '', dirty: false, draftRevision: owner.getSnapshot().draftRevision + 1 }) },
    changeReason(reason: string) { owner.edit({ reason }) },
    filter(value: string) { if (owner.canLeave().kind === 'ALLOW') { owner.patch({ filter: value, selected: null }); void list() } },
    close(discard = false) { if (!owner.locked() && (discard || owner.canLeave().kind === 'ALLOW')) owner.patch({ open: false, selected: null, reason: '', dirty: false }) },
  })
}
