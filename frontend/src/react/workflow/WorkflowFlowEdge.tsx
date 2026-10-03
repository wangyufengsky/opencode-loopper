import { getBezierPath, type Edge, type EdgeProps } from '@xyflow/react'

export type WorkflowFlowEdge = Edge<{
  label: string
  description: string
  choose(id: string): void
}, 'workflow'>

export default function WorkflowFlowEdgeView(props: EdgeProps<WorkflowFlowEdge>) {
  const [path, labelX, labelY] = getBezierPath(props)
  return <g className={props.selected ? 'workflow-edge-selected' : undefined}>
    <path d={path} className="workflow-wire-hit" tabIndex={0} role="button" aria-pressed={!!props.selected}
      aria-label={props.data?.description} onPointerDown={event => event.stopPropagation()}
      onClick={event => { event.stopPropagation(); props.data?.choose(props.id) }}
      onKeyDown={event => { if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation(); props.data?.choose(props.id) } }} />
    <path d={path} className="workflow-wire" />
    {props.data?.label && <text x={labelX} y={labelY - 8} className="workflow-edge-label">{props.data.label}</text>}
  </g>
}
