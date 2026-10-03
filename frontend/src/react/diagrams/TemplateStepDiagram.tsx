import type { Node, NodeProps } from '@xyflow/react'
import { projectTemplateSteps, type TemplateStep } from '@/domain/diagrams/projections'
import { ReactIcon } from '@/react/ReactIcon'
import { ReadonlyDiagramFlow, SequencePorts } from './ReadonlyDiagramFlow'

type TemplateNode = Node<{ step: TemplateStep }, 'templateStep'>
function TemplateStepNode({ data }: NodeProps<TemplateNode>) {
  const step = data.step
  const icon = step.state === 'COMPLETE' ? 'lucide:check' : step.state === 'INTERRUPTED' ? 'lucide:pause' : step.state === 'UNKNOWN' ? 'lucide:minus' : 'lucide:circle'
  return <article className={`template-step ${step.state.toLowerCase()}`} aria-current={step.state === 'ACTIVE' ? 'step' : undefined} aria-label={step.label}>
    <SequencePorts /><span className="flow-icon"><ReactIcon icon={icon} width={15} /></span><strong>{step.label}</strong>{step.state === 'UNKNOWN' && <small>历史记录不足</small>}
  </article>
}
const nodeTypes = { templateStep: TemplateStepNode }
export function TemplateStepDiagram({ steps }: { steps: TemplateStep[] }) {
  const projection = projectTemplateSteps(steps)
  return <ReadonlyDiagramFlow nodes={projection.nodes.map(node => ({ ...node, type: 'templateStep', ariaLabel: node.data.step.label }))} edges={projection.edges} nodeTypes={nodeTypes} height={projection.height} label="执行流程" kind="template-progress" className="flow template-step-diagram" />
}
