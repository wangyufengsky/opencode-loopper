import { useMemo } from 'react'
import type { Node, NodeProps } from '@xyflow/react'
import type { Stage } from '@/types/domain'
import { rolePackLabel, statusLabel, testPolicyLabel } from '@/utils/displayLabels'
import { projectStages } from '@/domain/diagrams/projections'
import { ReactIcon } from '@/react/ReactIcon'
import { ReadonlyDiagramFlow, SequencePorts } from './ReadonlyDiagramFlow'

type StageNodeType = Node<{ stage: Stage }, 'stage'>
function stageIcon(status: Stage['status']) {
  return status === 'SUCCEEDED' ? 'lucide:check' : status === 'RUNNING' ? 'lucide:radio-tower'
    : status === 'FAILED' ? 'lucide:triangle-alert' : status === 'CANCELLED' ? 'lucide:circle-x'
      : status === 'PAUSED' ? 'lucide:pause' : 'lucide:circle-dashed'
}
function StageNode({ data }: NodeProps<StageNodeType>) {
  const stage = data.stage, count = stage.attemptCount ?? stage.attempts.length
  return <article className={`phase-card is-${stage.status.toLowerCase()}`} aria-label={`阶段 ${stage.ordinal}，${statusLabel(stage.status)}`}>
    <SequencePorts />
    <header><div><span className="phase-index">阶段 {String(stage.ordinal).padStart(2, '0')}</span><strong>阶段 {stage.ordinal}</strong></div>
      <span className="phase-status"><ReactIcon icon={stageIcon(stage.status)} width={13} />{statusLabel(stage.status)}</span></header>
    <div className="phase-objective"><span>阶段目标</span><p>{stage.objective}</p></div>
    <footer>{stage.rolePackId && <span><ReactIcon icon="lucide:package-check" width={12} />{rolePackLabel(stage.rolePackId)}{stage.testPolicy && ` · ${testPolicyLabel(stage.testPolicy)}`}</span>}
      <span><ReactIcon icon="lucide:rotate-cw" width={12} />{count ? `${count} 次尝试` : '尚未尝试'}</span></footer>
  </article>
}
const nodeTypes = { stage: StageNode }
export function StageDiagram({ stages }: { stages: Stage[] }) {
  const projection = useMemo(() => projectStages(stages), [stages])
  const nodes = projection.nodes.map(node => ({ ...node, type: 'stage', ariaLabel: `阶段 ${node.data.stage.ordinal}，${statusLabel(node.data.stage.status)}` }))
  return <ReadonlyDiagramFlow nodes={nodes} edges={projection.edges} nodeTypes={nodeTypes} height={projection.height} label="阶段进度" kind="stages" className="stage-map stage-diagram" />
}
