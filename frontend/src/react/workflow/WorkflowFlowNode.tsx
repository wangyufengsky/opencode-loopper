import { Handle, Position, type Node, type NodeProps } from '@xyflow/react'
import type { KeyboardEvent } from 'react'
import type { WorkflowNode } from '@/types/domain'
import { workflowStateLabel } from '@/utils/displayLabels'
import { ReactIcon } from '@/react/ReactIcon'

export type WorkflowFlowNode = Node<{
  node: WorkflowNode
  index: number
  active: boolean
  connecting: boolean
  readonly: boolean
  roleName?: string
  state?: string
  showState: boolean
  choose(id: string): void
  connect(id: string): void
  keys(event: KeyboardEvent<HTMLElement>, id: string): void
}, 'workflow'>

export default function WorkflowFlowNodeView({ data }: NodeProps<WorkflowFlowNode>) {
  const { node } = data
  const icon = node.kind === 'HUMAN' ? 'lucide:user-round-check' : node.kind === 'SYSTEM' ? 'lucide:shield-check'
    : ['free.write', 'source.test-write'].includes(node.moduleId || '') ? 'lucide:code-xml' : 'lucide:scan-text'
  return <article className={`workflow-node${data.active ? ' selected' : ''}${data.connecting ? ' connect-source' : ''}`}
    data-node-id={node.id} tabIndex={0}
    aria-label={`${node.title}，${node.kind === 'HUMAN' ? '人工节点' : node.kind === 'SYSTEM' ? '程序节点' : '工作节点'}`}
    onPointerDown={event => { if (!(event.target as Element).closest('button, .react-flow__handle')) event.currentTarget.focus({ preventScroll: true }) }}
    onClick={event => { event.stopPropagation(); if (!(event.target as Element).closest('.react-flow__handle')) data.choose(node.id) }} onKeyDown={event => data.keys(event, node.id)}>
    <Handle type="target" position={Position.Top} id="incoming" isConnectable={!data.readonly} isConnectableStart={!data.readonly} isConnectableEnd={!data.readonly}
      aria-label={`连接到${node.title}`} title="接收前置节点连接" />
    <header><span className="workflow-node-icon"><ReactIcon icon={icon} /></span><strong>{node.title || `节点 ${data.index + 1}`}</strong></header>
    <p>{node.kind === 'HUMAN' ? '人工确认' : node.kind === 'SYSTEM' ? '程序执行' : node.roleId ? data.roleName || '已配置角色' : '待选择角色'}</p>
    <footer><span className={data.showState ? 'workflow-node-state' : undefined} data-state={data.state}>
      {data.showState ? workflowStateLabel(data.state || 'PENDING') : `${node.outputs.length} 项交付物`}
    </span>{node.pauseAfter && node.kind !== 'HUMAN' && <span><ReactIcon icon="lucide:pause" />执行后暂停</span>}</footer>
    <Handle type="source" position={Position.Bottom} id="outgoing" isConnectable={!data.readonly} isConnectableStart={!data.readonly} isConnectableEnd={!data.readonly}
      className={data.active || data.connecting ? 'workflow-handle-visible' : ''}
      aria-label={`从${node.title}拖动连接后续节点`} title="拖动连接后续节点" />
    {!data.readonly && (data.active || data.connecting) && <button className="workflow-port nodrag nopan"
      aria-label={`从${node.title}连接后续节点`} title="连接后续节点"
      onPointerDown={event => event.stopPropagation()} onClick={event => { event.stopPropagation(); data.connect(node.id) }}>
      <ReactIcon icon="lucide:plus" width={14} />
    </button>}
  </article>
}
