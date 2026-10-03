import { MarkerType, type Node, type NodeProps } from '@xyflow/react'
import type { RoleSlotBinding } from '@/types/domain'
import { groupRoleWorkflows, projectRoleSteps } from '@/domain/diagrams/projections'
import { ReactIcon } from '@/react/ReactIcon'
import { ReadonlyDiagramFlow, SequencePorts } from './ReadonlyDiagramFlow'

type RoleStepNode = Node<{ label: string; current: boolean; ordinal: number }, 'roleStep'>
function RoleStep({ data }: NodeProps<RoleStepNode>) {
  return <article className={`role-step${data.current ? ' current' : ''}`} aria-label={`${data.label}${data.current ? '，当前角色负责' : ''}`}>
    <SequencePorts /><span className="step-number">{String(data.ordinal).padStart(2, '0')}</span><strong>{data.label}</strong>
  </article>
}
const nodeTypes = { roleStep: RoleStep }
export interface RoleDiagramProps { bindings: RoleSlotBinding[]; latestRevisionId: string; onRevision: (id: string) => void }
export function RoleDiagram({ bindings, latestRevisionId, onRevision }: RoleDiagramProps) {
  return <div className="workflow-diagrams role-diagrams">{groupRoleWorkflows(bindings).map((group, index) => {
    const projection = projectRoleSteps(group)
    return <section key={index} className="workflow-card" aria-label={`${group.name}流程图`}>
      <header><span><ReactIcon icon="lucide:workflow" width={17} />{group.name}</span><small><i />当前角色负责</small></header>
      <ReadonlyDiagramFlow nodes={projection.nodes.map(node => ({ ...node, type: 'roleStep', ariaLabel: `${node.data.label}${node.data.current ? '，当前角色负责' : ''}` }))} edges={projection.edges.map(edge => ({ ...edge, markerEnd: { type: MarkerType.ArrowClosed, color: 'var(--color-text-muted)' } }))} nodeTypes={nodeTypes} height={projection.height} label={`${group.name}步骤`} kind="roles" className="workflow-track" />
      <ul className="slot-list">{group.bindings.map(binding => <li key={binding.slot}><span>{binding.label || '角色阶段'}</span><small>{binding.activeRevisionId === latestRevisionId ? '使用最新发布版本' : '使用其他已发布版本'}</small><button type="button" className="inline-button" onClick={() => onRevision(binding.activeRevisionId)}>查看此阶段使用的版本</button></li>)}</ul>
    </section>
  })}</div>
}
