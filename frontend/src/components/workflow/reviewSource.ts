import type { WorkflowGraph, WorkflowNode } from '@/types/domain'

/** Keep mode-specific public dates aligned while editing an unfrozen flow; shared inputs remain intact. */
export function replaceReviewSource(graph: WorkflowGraph, value: WorkflowNode, editableInputs = true): WorkflowGraph {
  const previous = graph.nodes.find(node => node.id === value.id)
  const nodes = graph.nodes.map(node => node.id === value.id ? value : node)
  if (!editableInputs || previous?.moduleId !== 'system.review.snapshot' || value.moduleId !== previous.moduleId
    || (previous.parameters.reviewMode || 'DATE_INCREMENTAL') === (value.parameters.reviewMode || 'DATE_INCREMENTAL')) return { ...graph, nodes }
  const dates = (node: WorkflowNode) => node.inputs.filter(input => input.source === 'REQUIREMENT' && ['startDate', 'endDate'].includes(input.name))
  const former = new Set(dates(previous).map(input => input.sourceId))
  const referenced = new Set(nodes.flatMap(node => node.inputs.filter(input => input.source === 'REQUIREMENT').map(input => input.sourceId)))
  const inputs = graph.inputs.filter(input => !former.has(input.name) || referenced.has(input.name))
  for (const input of dates(value)) if (!inputs.some(field => field.name === input.sourceId)) inputs.push({ name: input.sourceId,
    title: input.name === 'startDate' ? '开始日期（YYYY-MM-DD）' : '结束日期（YYYY-MM-DD）', kind: 'TEXT', required: true })
  return { ...graph, nodes, inputs }
}
