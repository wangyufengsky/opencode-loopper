import { workflowRuns } from '@/api/workflowRuns'
import type { WorkflowCandidate, WorkflowGraph, WorkflowLayout, WorkflowReceipt, WorkflowRequirement } from '@/types/domain'
import { clone } from './graph'
export function preparePlanSave(base: WorkflowRequirement, graph: WorkflowGraph, layout: WorkflowLayout, candidate: WorkflowCandidate | null = null) {
  return { id: base.id, active: !['PLANNING', 'PENDING_START'].includes(base.state), changesPlan: !!candidate || JSON.stringify(base.graph) !== JSON.stringify(graph), candidate: candidate ? { id: candidate.id, version: candidate.version } : null, graph: clone(graph), layout: clone(layout), version: base.version, revision: base.revision,
    graphKey: crypto.randomUUID(), layoutKey: crypto.randomUUID(),
    graphReceipt: !candidate && JSON.stringify(base.graph) === JSON.stringify(graph) ? { id: base.id, revision: base.revision, version: base.version, layoutVersion: base.layoutVersion, state: base.state } : null as WorkflowReceipt | null,
    layoutReceipt: null as WorkflowReceipt | null }
}
export type WorkflowPlanSave = ReturnType<typeof preparePlanSave>
export async function savePlan(operation: WorkflowPlanSave) {
  if (!operation.graphReceipt) {
    const body = { requestKey: operation.graphKey, expectedVersion: operation.version, expectedRevision: operation.revision, graph: operation.graph }
    operation.graphReceipt = operation.candidate ? await workflowRuns.applyCandidate(operation.id, operation.candidate.id, { ...body, expectedCandidateVersion: operation.candidate.version }) : await (operation.active ? workflowRuns.applyPlan : workflowRuns.revise)(operation.id, body)
  }
  if (!operation.layoutReceipt) operation.layoutReceipt = await workflowRuns.layout(operation.id, { requestKey: operation.layoutKey, expectedRevision: operation.graphReceipt.revision, expectedLayoutVersion: operation.graphReceipt.layoutVersion, layout: operation.layout })
  return workflowRuns.get(operation.id)
}
