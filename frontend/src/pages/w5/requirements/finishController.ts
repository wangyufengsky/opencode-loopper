import { ApiError } from '@/api/client'
import { definitiveW4Rejection } from '@/pages/w4/shared/core'
import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowFinish, WorkflowFinishTarget, WorkflowRequirementState } from '@/types/domain'
import { createRequirementScope, ownedState, requireIdentity } from './core'

export function createFinishController(requirement: string, input: { version: number; state: WorkflowRequirementState }) {
  let context = input, cancelPoll = () => {}
  const owner = createRequirementScope('requirement-finish', requirement, { ...ownedState(), value: null as WorkflowFinish | null, open: false, target: 'CANCELLED' as WorkflowFinishTarget, reason: '' })
  const available = () => { const value = owner.getSnapshot().value; return !!value && !value.intent && !['STOPPING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(context.state) && !['STOPPING', 'COMPLETED', 'FAILED', 'CANCELLED'].includes(value.state) }
  function schedule() { cancelPoll(); const s = owner.getSnapshot(); if (s.value?.intent && !s.value.intent.finalizedAt || context.state === 'STOPPING') cancelPoll = owner.delay(() => { if (owner.locked()) schedule(); else void refresh() }, 2500) }
  async function refresh(strict = false, apply?: (callback: () => void) => boolean) {
    if (!owner.active()) return
    const ticket = owner.ticket('finish'); owner.patch({ loading: true }); cancelPoll()
    try { const value = await workflowRuns.finishStatus(requirement); if (!ticket.current()) return; requireIdentity(value.requirementId, requirement, '结束记录'); const project = () => owner.patch({ value, error: '', open: value.intent ? false : owner.getSnapshot().open }); if (apply) apply(project); else project() }
    catch (cause) { if (ticket.current()) { owner.fail(cause, '结束记录暂时无法读取，请重试。'); if (strict) throw cause } }
    finally { if (ticket.current()) { owner.patch({ loading: false }); schedule() } }
  }
  async function submit() {
    const s = owner.getSnapshot(); if (!available() || owner.locked() || s.loading || s.error || !s.reason.trim() || s.reason.length > 4000) return
    const body = { requestKey: crypto.randomUUID(), expectedVersion: context.version, target: s.target, reason: s.reason.trim() }
    await owner.mutate({ label: '结束需求', input: { endpoint: `/workflows/requirements/${encodeURIComponent(requirement)}/finish`, method: 'POST', requestKey: body.requestKey, body, versions: { version: context.version } }, rejected: cause => definitiveW4Rejection(cause) || cause instanceof ApiError && cause.status === 409 && cause.code === 'WORKFLOW_VERSION_CONFLICT', write: original => workflowRuns.finish(requirement, original),
      read: async (receipt, read) => { requireIdentity(receipt.id, requirement, '结束回执'); await refresh(true, read.apply); if (!read.isCurrent()) return; await owner.refreshParent(); read.apply(() => owner.patch({ reason: '', dirty: false, open: false })) },
    })
  }
  owner.setStart(() => { void refresh() })
  return Object.assign(owner, { available, refresh, submit,
    updateContext(next: typeof input) { const changed = next.version !== context.version || next.state !== context.state; context = next; if (changed && owner.active() && !owner.locked()) void refresh() },
    show() { if (available() && !owner.locked() && !owner.getSnapshot().loading && !owner.getSnapshot().error) owner.patch({ open: true }) },
    close() { if (owner.canLeave().kind === 'ALLOW') owner.patch({ open: false }) },
    discard() { if (!owner.locked()) owner.patch({ open: false, reason: '', dirty: false, draftRevision: owner.getSnapshot().draftRevision + 1 }) },
    discardDraft() { this.discard() },
    change(values: { reason?: string; target?: WorkflowFinishTarget }) { owner.edit(values) },
  })
}
