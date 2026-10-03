import { useCallback, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { getBezierPath, MiniMap, Position, ReactFlow, ReactFlowProvider, useReactFlow, ViewportPortal } from '@xyflow/react'
import { autoLayout, NODE_HEIGHT, NODE_WIDTH, outcomeTitle } from '@/components/workflow/graph'
import type { WorkflowLayout, WorkflowPoint } from '@/types/domain'
import { ReactIcon } from '@/react/ReactIcon'
import WorkflowFlowNodeView, { type WorkflowFlowNode } from './WorkflowFlowNode'
import WorkflowFlowEdgeView, { type WorkflowFlowEdge } from './WorkflowFlowEdge'
import { copyLayout, fitLayout, positionOf, revealLayout, zoomLayout } from './layout'
import type { WorkflowCanvasHandle, WorkflowCanvasProps } from './types'
import { useWorkflowPointerGestures } from './pointerGestures'
import '@xyflow/react/dist/style.css'
import './workflow-react.css'

const nodeTypes = { workflow: WorkflowFlowNodeView }, edgeTypes = { workflow: WorkflowFlowEdgeView }
const shifts: Record<string, WorkflowPoint> = { ArrowLeft: { x: -24, y: 0 }, ArrowRight: { x: 24, y: 0 }, ArrowUp: { x: 0, y: -24 }, ArrowDown: { x: 0, y: 24 } }

function WorkflowCanvasContent(props: WorkflowCanvasProps) {
  const flow = useReactFlow<WorkflowFlowNode, WorkflowFlowEdge>()
  const root = useRef<HTMLElement>(null), latest = useRef(props), layoutRef = useRef(copyLayout(props.layout))
  latest.current = props
  const [local, setLocal] = useState(layoutRef.current), [minimap, setMinimap] = useState(false)
  const incomingLayout = JSON.stringify(props.layout), acceptedLayout = useRef(incomingLayout)
  const incomingGraph = JSON.stringify(props.graph), acceptedGraph = useRef(incomingGraph)
  const mounted = useRef(false)
  useLayoutEffect(() => {
    mounted.current = true
    return () => { mounted.current = false }
  }, [])
  useLayoutEffect(() => { root.current?.querySelector('.react-flow__viewport')?.classList.add('workflow-canvas-world') }, [])
  const updateLayout = useCallback((layout: WorkflowLayout, save = false) => {
    if (!mounted.current) return
    layoutRef.current = layout; setLocal(layout)
    if (save) latest.current.onLayout(copyLayout(layout))
  }, [])
  const gestures = useWorkflowPointerGestures({ root, props, layout: () => layoutRef.current, update: updateLayout,
    point: point => flow.screenToFlowPosition(point, { snapToGrid: false }) })
  const abort = gestures.cancel
  useLayoutEffect(() => {
    if (acceptedLayout.current !== incomingLayout) {
      abort(false)
      acceptedLayout.current = incomingLayout; updateLayout(copyLayout(props.layout))
    }
  }, [incomingLayout, props.layout, abort, updateLayout])
  useLayoutEffect(() => {
    if (acceptedGraph.current !== incomingGraph) { abort(); acceptedGraph.current = incomingGraph }
  }, [incomingGraph, abort])
  useLayoutEffect(() => {
    if (props.readonly && (gestures.kind === 'connect' || gestures.tapped || gestures.kind === 'drag' && !props.movable)) abort()
  }, [props.readonly, props.movable, gestures.kind, gestures.tapped, abort])
  useLayoutEffect(() => { if (props.connecting && gestures.tapped) abort() }, [props.connecting, gestures.tapped, abort])
  const dimensions = useCallback(() => ({ width: root.current?.clientWidth || 800, height: root.current?.clientHeight || 600 }), [])
  const focus = useCallback((id?: string) => {
    const node = id ? [...(root.current?.querySelectorAll<HTMLElement>('[data-node-id]') || [])].find(element => element.dataset.nodeId === id) : null
    ;(node || root.current)?.focus({ preventScroll: true })
  }, [])
  const fit = useCallback(() => { abort(); const { width, height } = dimensions(); updateLayout(fitLayout(latest.current.graph, layoutRef.current, width, height), true) }, [abort, dimensions, updateLayout])
  const reveal = useCallback((id: string) => {
    abort(); const { width, height } = dimensions(); updateLayout(revealLayout(latest.current.graph, layoutRef.current, id, width, height))
  }, [abort, dimensions, updateLayout])
  const controller = useMemo<WorkflowCanvasHandle>(() => ({ fit, focus, reveal }), [fit, focus, reveal])
  const cancel = useCallback(() => {
    abort()
    latest.current.onCancel()
  }, [abort])
  useLayoutEffect(() => { props.onReady?.(controller); return () => props.onReady?.(undefined) }, [controller, props.onReady])
  const scale = useCallback((amount: number) => { abort(); const { width, height } = dimensions(); updateLayout(zoomLayout(layoutRef.current, amount, width, height), true) }, [abort, dimensions, updateLayout])
  useLayoutEffect(() => {
    const target = root.current
    if (!target) return
    const wheel = (event: WheelEvent) => {
      if (!event.ctrlKey || !event.deltaY) return
      event.preventDefault(); event.stopPropagation(); scale(event.deltaY < 0 ? 1.1 : 1 / 1.1)
    }
    target.addEventListener('wheel', wheel, { passive: false })
    return () => target.removeEventListener('wheel', wheel)
  }, [scale])
  const choose = useCallback((id: string) => {
    if (gestures.consumeClick()) return
    focus(id)
    if (latest.current.connecting) { abort(); latest.current.onConnect(id) }
    else latest.current.onSelect(id)
  }, [abort, focus, gestures.consumeClick])
  const connectClick = (id: string) => {
    if (latest.current.readonly) return
    abort()
    latest.current.onConnect(id)
  }
  const portClick = (id: string, port: 'source' | 'target', keyboard = false) => {
    if (!keyboard && gestures.consumeClick()) return
    gestures.tap(id, port)
  }
  const keys = useCallback((event: KeyboardEvent<HTMLElement>, id: string) => {
    if (event.target !== event.currentTarget) return
    const current = latest.current
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation(); abort(); current.connecting ? current.onConnect(id) : current.onSelect(id); return }
    if (current.readonly && !current.movable) return
    if (event.key === 'Delete' || event.key === 'Backspace') { if (!current.readonly) { event.preventDefault(); event.stopPropagation(); abort(); current.onRemove(id) }; return }
    const delta = shifts[event.key]
    if (delta) { event.preventDefault(); event.stopPropagation(); abort(); const point = positionOf(current.graph, layoutRef.current, id)
      updateLayout({ ...copyLayout(layoutRef.current), positions: { ...layoutRef.current.positions, [id]: { x: point.x + delta.x, y: point.y + delta.y } } }, true) }
  }, [abort, updateLayout])
  const nodes = useMemo<WorkflowFlowNode[]>(() => {
    const defaults = autoLayout(props.graph)
    return props.graph.nodes.map((node, index) => ({ id: node.id, type: 'workflow', position: local.positions[node.id] ?? defaults[node.id]!,
      width: NODE_WIDTH, height: NODE_HEIGHT, ariaLabel: node.title, selected: props.selected === node.id, draggable: false,
      // Controlled input DTOs omit engine measurements; retain measured sizes
      // through the public instance API so selection does not reset handles.
      measured: flow.getInternalNode(node.id)?.measured,
      data: { node, index, active: props.selected === node.id, connecting: props.connecting === node.id || gestures.tapped?.id === node.id, readonly: !!props.readonly,
        roleName: node.roleId ? props.roleNames?.[node.roleId] : undefined, state: props.states?.[node.id], showState: !!props.states,
        choose, keys, connect: connectClick, portClick, drag: gestures.node, dragConnection: gestures.connection } }))
  }, [props.graph, props.selected, props.connecting, props.readonly, props.movable, props.roleNames, props.states, local.positions, choose, keys, flow, gestures.node, gestures.connection, gestures.tapped])
  const edges = useMemo<WorkflowFlowEdge[]>(() => props.graph.edges.map(edge => ({ id: edge.id, source: edge.from, target: edge.to,
    sourceHandle: 'outgoing', targetHandle: 'incoming', type: 'workflow', selected: props.selectedEdge === edge.id,
    ariaLabel: `连接：${props.graph.nodes.find(node => node.id === edge.from)?.title} 到 ${props.graph.nodes.find(node => node.id === edge.to)?.title}`,
    data: { label: edge.outcome ? outcomeTitle(props.graph.nodes.find(node => node.id === edge.from), edge.outcome) : '',
      description: `编辑连接：${props.graph.nodes.find(node => node.id === edge.from)?.title} 到 ${props.graph.nodes.find(node => node.id === edge.to)?.title}`,
      choose: props.onEdge } })), [props.graph, props.selectedEdge, props.onEdge])
  const previewPath = gestures.preview ? getBezierPath({ sourceX: gestures.preview.source.x, sourceY: gestures.preview.source.y,
    targetX: gestures.preview.target.x, targetY: gestures.preview.target.y, sourcePosition: Position.Bottom, targetPosition: Position.Top })[0] : undefined
  return <section ref={root} className={`workflow-canvas workflow-canvas-react${gestures.kind === 'connect' || gestures.tapped ? ' workflow-connection-dragging' : ''}`}
    aria-label="流程画布" tabIndex={0} data-canvas-runtime="react" data-canvas-kind="workflow"
    data-pointer-gesture={gestures.kind}
    onPointerDownCapture={event => {
      const target = event.target as Element
      if (event.button === 1 && !target.closest('button, input, select, textarea, a, [data-canvas-tools]')
        && (target === event.currentTarget || target.classList.contains('react-flow__pane') || target.closest('.react-flow__node, .react-flow__edge'))) gestures.pane(event)
    }}
    onMouseDownCapture={event => {
      // XYFlow special-cases middle mousedown before its panOnDrag filter.
      // Keep unowned compatibility events from starting a second controller;
      // native defaults on inputs and links remain available.
      if (event.button === 1) event.stopPropagation()
    }}
    onPointerDown={event => { if ((event.target as Element).classList.contains('react-flow__pane') || event.target === event.currentTarget) gestures.pane(event) }}
    onClick={event => { if (event.target === event.currentTarget && !gestures.consumeClick()) cancel() }}
    onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); cancel() } }}>
    <ReactFlow<WorkflowFlowNode, WorkflowFlowEdge> nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
      defaultViewport={{ x: props.layout.x, y: props.layout.y, zoom: props.layout.zoom }}
      viewport={{ x: local.x, y: local.y, zoom: local.zoom }}
      onPaneClick={() => { if (!gestures.consumeClick()) cancel() }}
      minZoom={.1} maxZoom={4} nodesDraggable={false} nodesConnectable={!props.readonly} connectOnClick={false} edgesReconnectable={false}
      autoPanOnConnect={false} autoPanOnNodeDrag={false}
      nodesFocusable={false} edgesFocusable={false} disableKeyboardA11y deleteKeyCode={null} selectionKeyCode={null} multiSelectionKeyCode={null}
      selectNodesOnDrag={false} panOnDrag={false} panOnScroll={false} panActivationKeyCode={null} zoomActivationKeyCode={null}
      preventScrolling={false} zoomOnScroll={false} zoomOnPinch={false} zoomOnDoubleClick={false} attributionPosition="top-right">
      <ViewportPortal>{previewPath && <svg className="workflow-connection-preview" aria-hidden="true"><path d={previewPath} className="workflow-wire react-flow__connection-path" /></svg>}</ViewportPortal>
      {minimap && !!props.graph.nodes.length && <MiniMap className="workflow-minimap" ariaLabel="流程缩略图" nodeColor="var(--color-action-primary)" maskColor="var(--color-bg-canvas)" />}
    </ReactFlow>
    {!props.graph.nodes.length && <div className="workflow-canvas-empty"><span className="workflow-empty-mark"><ReactIcon icon="lucide:workflow" width={30} /></span><h2>留出空间，让想法开始。</h2><p>添加第一个节点，连接你的工作流程。</p></div>}
    {props.connecting && <p className="workflow-connecting" role="status">选择后续节点 · Esc 取消</p>}
    <div className="workflow-canvas-controls nodrag nopan" data-canvas-tools onPointerDown={event => event.stopPropagation()}>
      <button aria-label="缩小画布" title="缩小" onClick={() => scale(1 / 1.2)}><ReactIcon icon="lucide:minus" /></button><span>{Math.round(local.zoom * 100)}%</span>
      <button aria-label="放大画布" title="放大" onClick={() => scale(1.2)}><ReactIcon icon="lucide:plus" /></button>
      <button aria-label="适应画布" title="适应画布" onClick={fit}><ReactIcon icon="lucide:scan" /></button>
      <button aria-pressed={minimap} aria-label="流程缩略图" title="流程缩略图" onClick={() => setMinimap(!minimap)}><ReactIcon icon="lucide:map" /></button>
    </div>
    <span className="workflow-canvas-caption">{props.graph.nodes.length} 个节点 <span aria-hidden="true">/</span> {props.graph.edges.length} 条连接</span>
  </section>
}

export function WorkflowCanvasView(props: WorkflowCanvasProps) {
  return <ReactFlowProvider initialWidth={800} initialHeight={600}><WorkflowCanvasContent {...props} /></ReactFlowProvider>
}

export default WorkflowCanvasView
