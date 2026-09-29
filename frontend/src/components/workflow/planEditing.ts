import type { WorkflowCandidate, WorkflowGraph, WorkflowNodeSummary } from '@/types/domain'
export function protectedNodes(graph: WorkflowGraph, runs: WorkflowNodeSummary[], candidate: WorkflowCandidate | null): Set<string> {
  const protectedKeys = new Set(runs.filter(node => ['ACTIVE', 'SUCCEEDED'].includes(node.state)).map(node => node.nodeKey))
  let added = true
  while (added) { added = false; for (const edge of graph.edges) if (protectedKeys.has(edge.to) && !protectedKeys.has(edge.from)) { protectedKeys.add(edge.from); added = true } }
  if (candidate) {
    const descendants = new Set([candidate.nodeKey]); added = true
    while (added) { added = false; for (const edge of graph.edges) if (descendants.has(edge.from) && !descendants.has(edge.to)) { descendants.add(edge.to); added = true } }
    graph.nodes.filter(node => node.id === candidate.nodeKey || !descendants.has(node.id)).forEach(node => protectedKeys.add(node.id))
  }
  return protectedKeys
}
