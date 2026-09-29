import type { WorkflowAttempt, WorkflowCandidate, WorkflowExecution, WorkflowRequirement } from '@/types/domain'
import { template } from './workflowTestFixtures'
export function requirement(overrides: Partial<WorkflowRequirement> = {}): WorkflowRequirement {
  const flow = template()
  return { id: 'req', projectId: 'project', title: '交付需求', objective: '核对并交付目标成果', state: 'PLANNING', headRevision: 2, revision: 2, version: 3, layoutVersion: 4, sourceTemplateId: flow.id, sourceRevision: flow.revision, graph: flow.graph, layout: flow.layout, diagnostics: [], ...overrides }
}
export function execution(state: WorkflowRequirement['state'] = 'PLANNING'): WorkflowExecution {
  return { execution: { id: 'req', revision: 2, version: 3, state, nodes: [] }, control: { id: 'req', revision: 2, version: 3, configured: false, controlVersion: -1, mode: null, targetKey: null, state: 'PAUSED', reasonCode: 'WORKFLOW_NOT_STARTED', model: null, checkpoints: [] } }
}
export function attempt(overrides: Partial<WorkflowAttempt> = {}): WorkflowAttempt {
  return { id: 'run', ordinal: 1, planRevision: 2, state: 'WAITING_INPUT', version: 1, createdAt: '2026-09-28T00:00:00Z', updatedAt: '2026-09-28T00:00:00Z', roleName: null, roleRevisionNumber: null, deliveryAccepted: false, stopConfirmed: false, modelState: null, modelVersion: null, commandState: null, commandVersion: null, suspended: false, errorCode: null, queueState: null, workspaceState: null, ...overrides }
}

export function candidate(overrides: Partial<WorkflowCandidate> = {}): WorkflowCandidate {
  const graph = structuredClone(requirement().graph), next = structuredClone(graph.nodes[0]!); next.id = 'next'; next.title = '候选后续检查'; graph.nodes.push(next); graph.edges.push({ id: 'next-edge', from: 'review', to: 'next', outcome: null })
  return { id: 'candidate', attemptId: 'source-attempt', nodeKey: 'review', sourceTitle: '设计节点', baseRevision: 2, state: 'PENDING', version: 0, appliedRevision: null, decisionReason: null, stale: false, sourceCompleted: true, graph, originalGraph: requirement().graph, changes: { added: ['next'], changed: [], removed: [], affected: ['next'], protectedNodes: [] }, diagnostics: [], ...overrides }
}
