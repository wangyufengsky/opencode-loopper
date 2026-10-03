import { BaseEdge, Controls, getSmoothStepPath, Handle, Position, ReactFlow, type Edge, type EdgeProps, type Node, type NodeTypes } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import './diagrams.css'

function SequenceEdge(props: EdgeProps) {
  const [path] = getSmoothStepPath({ ...props, borderRadius: 12 })
  const tone = String(props.data?.tone ?? 'connector-pending')
  return <BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} className={`stage-connector ${tone}`} />
}
const edgeTypes = { sequence: SequenceEdge }
export function SequencePorts() {
  return <><Handle type="target" position={Position.Left} isConnectable={false} /><Handle type="source" position={Position.Right} isConnectable={false} /></>
}

export function ReadonlyDiagramFlow({ nodes, edges, nodeTypes, label, height, kind, className = '' }: {
  nodes: Node[]; edges: Edge[]; nodeTypes: NodeTypes; label: string; height: number; kind: string; className?: string
}) {
  return <div className={`readonly-diagram ${className}`} style={{ height }} aria-label={label} data-canvas-runtime="react" data-canvas-kind={kind}>
    <ReactFlow
      nodes={nodes} edges={edges.map(edge => ({ ...edge, type: 'sequence' }))} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
      nodesDraggable={false} nodesConnectable={false} nodesFocusable={false} edgesFocusable={false} elementsSelectable={false}
      deleteKeyCode={null} selectionOnDrag={false} zoomOnScroll={false} minZoom={0.2} maxZoom={2}
      defaultViewport={{ x: 14, y: 12, zoom: 1 }}
      ariaLabelConfig={{ 'node.a11yDescription.default': '已保存的步骤与状态。', 'node.a11yDescription.keyboardDisabled': '已保存的步骤与状态。', 'edge.a11yDescription.default': '步骤之间的顺序连接。', 'controls.ariaLabel': '流程图视图操作', 'controls.zoomIn.ariaLabel': '放大流程图', 'controls.zoomOut.ariaLabel': '缩小流程图', 'controls.fitView.ariaLabel': '适应流程图', 'handle.ariaLabel': '节点连接位置' }}
    ><Controls showInteractive={false} position="bottom-left" /></ReactFlow>
  </div>
}
