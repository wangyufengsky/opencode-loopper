import { useMemo, useRef } from 'react'
import { BaseEdge, ControlButton, Controls, getSmoothStepPath, getViewportForBounds, Handle, Position, ReactFlow, ReactFlowProvider, useReactFlow, type Edge, type EdgeProps, type Node, type NodeTypes } from '@xyflow/react'
import { ReactIcon } from '@/react/ReactIcon'
import { DIAGRAM_MAX_ZOOM, DIAGRAM_MIN_ZOOM, useDiagramViewportGestures } from './viewportGestures'
import '@xyflow/react/dist/style.css'
import './diagrams.css'

function SequenceEdge(props: EdgeProps) {
  const [path] = getSmoothStepPath({ ...props, borderRadius: 12 })
  const tone = String(props.data?.tone ?? 'connector-pending')
  return <BaseEdge id={props.id} path={path} markerEnd={props.markerEnd} className={`stage-connector ${tone}`} />
}
const edgeTypes = { sequence: SequenceEdge }
export function SequencePorts() {
  return <><Handle type="target" position={Position.Left} isConnectable={false} isConnectableStart={false} /><Handle type="source" position={Position.Right} isConnectable={false} isConnectableStart={false} /></>
}

interface DiagramProps {
  nodes: Node[]; edges: Edge[]; nodeTypes: NodeTypes; label: string; height: number; kind: string; className?: string
}
function DiagramContent({ nodes, edges, nodeTypes, label, height, kind, className = '' }: DiagramProps) {
  const root = useRef<HTMLDivElement>(null), flow = useReactFlow()
  const scope = JSON.stringify({ nodes, edges, height, kind })
  const gestures = useDiagramViewportGestures(root, scope)
  const readonlyNodes = useMemo(() => nodes.map(node => ({ ...node, draggable: false, connectable: false, selectable: false,
    className: `${node.className ?? ''} nopan`, style: { ...node.style, pointerEvents: 'all' as const, userSelect: 'text' as const },
    // Controlled DTOs do not contain the engine's measured handle geometry.
    measured: node.measured ?? flow.getInternalNode(node.id)?.measured,
  })), [nodes, flow])
  function fit() {
    gestures.change(value => {
      const visible = nodes.filter(node => !node.hidden), { width, height } = gestures.dimensions()
      return visible.length ? getViewportForBounds(flow.getNodesBounds(visible), width, height, DIAGRAM_MIN_ZOOM, DIAGRAM_MAX_ZOOM, .1) : value
    })
  }
  return <div ref={root} className={`readonly-diagram ${className}`} style={{ height }} aria-label={label} data-canvas-runtime="react" data-canvas-kind={kind}
    data-pointer-gesture={gestures.kind} onPointerDownCapture={gestures.pointerDown}
    onMouseDownCapture={event => { if (event.button === 1) event.stopPropagation() }}
    onDoubleClick={event => {
      const target = event.target as Element
      if (event.button !== 0 || event.ctrlKey || target.closest('.nopan, button, input, select, textarea, a, [contenteditable], .react-flow__controls, .react-flow__attribution')) return
      event.preventDefault(); event.stopPropagation(); gestures.zoom(event.shiftKey ? .5 : 2, { x: event.clientX, y: event.clientY })
    }}
    onKeyDown={event => { if (event.key === 'Escape') { if (gestures.kind) event.stopPropagation(); gestures.cancel() } }}
    onFocusCapture={event => {
      const node = event.target as HTMLElement
      if (!node.classList.contains('react-flow__node') || !node.matches(':focus-visible')) return
      const input = nodes.find(value => value.id === node.dataset.id)
      if (!input) return
      gestures.change(value => {
        const bounds = flow.getNodesBounds([input]), { width, height } = gestures.dimensions()
        const left = bounds.x * value.zoom + value.x, top = bounds.y * value.zoom + value.y
        if (left + bounds.width * value.zoom > 0 && left < width && top + bounds.height * value.zoom > 0 && top < height) return value
        return { zoom: value.zoom, x: width / 2 - (bounds.x + bounds.width / 2) * value.zoom,
          y: height / 2 - (bounds.y + bounds.height / 2) * value.zoom }
      })
    }}>
    <ReactFlow
      nodes={readonlyNodes}
      edges={edges.map(edge => ({ ...edge, type: 'sequence', reconnectable: false, selectable: false, focusable: false }))} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
      nodesDraggable={false} nodesConnectable={false} nodesFocusable autoPanOnNodeFocus={false} edgesFocusable={false} elementsSelectable={false}
      deleteKeyCode={null} selectionKeyCode={null} multiSelectionKeyCode={null} selectionOnDrag={false} selectNodesOnDrag={false}
      panOnDrag={false} panOnScroll={false} panActivationKeyCode={null} zoomActivationKeyCode={null}
      zoomOnScroll={false} zoomOnPinch={false} zoomOnDoubleClick={false} preventScrolling={false}
      autoPanOnNodeDrag={false} autoPanOnConnect={false} autoPanOnSelection={false} edgesReconnectable={false} connectOnClick={false}
      minZoom={DIAGRAM_MIN_ZOOM} maxZoom={DIAGRAM_MAX_ZOOM} viewport={gestures.viewport}
      defaultViewport={{ x: 14, y: 12, zoom: 1 }}
      ariaLabelConfig={{ 'node.a11yDescription.default': '已保存的步骤与状态。', 'node.a11yDescription.keyboardDisabled': '已保存的步骤与状态。', 'edge.a11yDescription.default': '步骤之间的顺序连接。', 'controls.ariaLabel': '流程图视图操作', 'controls.zoomIn.ariaLabel': '放大流程图', 'controls.zoomOut.ariaLabel': '缩小流程图', 'controls.fitView.ariaLabel': '适应流程图', 'handle.ariaLabel': '节点连接位置' }}
    ><Controls showZoom={false} showFitView={false} showInteractive={false} position="bottom-left">
      <ControlButton className="react-flow__controls-zoomin" aria-label="放大流程图" title="放大流程图" disabled={gestures.viewport.zoom >= DIAGRAM_MAX_ZOOM} onClick={() => gestures.zoom(1.2)}><ReactIcon icon="lucide:plus" width={12} /></ControlButton>
      <ControlButton className="react-flow__controls-zoomout" aria-label="缩小流程图" title="缩小流程图" disabled={gestures.viewport.zoom <= DIAGRAM_MIN_ZOOM} onClick={() => gestures.zoom(1 / 1.2)}><ReactIcon icon="lucide:minus" width={12} /></ControlButton>
      <ControlButton className="react-flow__controls-fitview" aria-label="适应流程图" title="适应流程图" onClick={fit}><ReactIcon icon="lucide:scan" width={12} /></ControlButton>
    </Controls></ReactFlow>
  </div>
}

export function ReadonlyDiagramFlow(props: DiagramProps) {
  return <ReactFlowProvider initialWidth={800} initialHeight={props.height}><DiagramContent {...props} /></ReactFlowProvider>
}
