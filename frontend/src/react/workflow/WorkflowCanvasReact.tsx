import { useCallback, useLayoutEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { MiniMap, ReactFlow, ReactFlowProvider, useStoreApi, type Connection, type NodeChange, type Viewport } from '@xyflow/react'
import { autoLayout, NODE_HEIGHT, NODE_WIDTH, outcomeTitle } from '@/components/workflow/graph'
import type { WorkflowLayout, WorkflowPoint } from '@/types/domain'
import { ReactIcon } from '@/react/ReactIcon'
import WorkflowFlowNodeView, { type WorkflowFlowNode } from './WorkflowFlowNode'
import WorkflowFlowEdgeView, { type WorkflowFlowEdge } from './WorkflowFlowEdge'
import { copyLayout, fitLayout, positionOf, revealLayout, zoomLayout } from './layout'
import type { WorkflowCanvasHandle, WorkflowCanvasProps } from './types'
import '@xyflow/react/dist/style.css'
import './workflow-react.css'

const nodeTypes = { workflow: WorkflowFlowNodeView }, edgeTypes = { workflow: WorkflowFlowEdgeView }
const shifts: Record<string, WorkflowPoint> = { ArrowLeft: { x: -24, y: 0 }, ArrowRight: { x: 24, y: 0 }, ArrowUp: { x: 0, y: -24 }, ArrowDown: { x: 0, y: 24 } }

function WorkflowCanvasContent(props: WorkflowCanvasProps) {
  const flowStore = useStoreApi<WorkflowFlowNode, WorkflowFlowEdge>()
  const root = useRef<HTMLElement>(null), latest = useRef(props), layoutRef = useRef(copyLayout(props.layout))
  latest.current = props
  const [local, setLocal] = useState(layoutRef.current), [minimap, setMinimap] = useState(false), [connectionDragging, setConnectionDragging] = useState(false)
  const incomingLayout = JSON.stringify(props.layout), acceptedLayout = useRef(incomingLayout)
  const pointerStart = useRef<WorkflowPoint | undefined>(undefined), gestureMoved = useRef(false)
  const viewportStart = useRef<Viewport | undefined>(undefined)
  const nodeStart = useRef<{ id: string; position: WorkflowPoint; positions: WorkflowLayout['positions']; moved: boolean } | undefined>(undefined)
  const connectionCancelled = useRef(false)
  useLayoutEffect(() => { root.current?.querySelector('.react-flow__viewport')?.classList.add('workflow-canvas-world') }, [])
  const updateLayout = useCallback((layout: WorkflowLayout, save = false) => {
    layoutRef.current = layout; setLocal(layout)
    if (save) latest.current.onLayout(copyLayout(layout))
  }, [])
  useLayoutEffect(() => {
    if (acceptedLayout.current !== incomingLayout) {
      nodeStart.current = undefined
      acceptedLayout.current = incomingLayout; updateLayout(copyLayout(props.layout))
    }
  }, [incomingLayout, props.layout, updateLayout])
  useLayoutEffect(() => {
    if (props.readonly && !props.movable && nodeStart.current) {
      const { positions } = nodeStart.current; nodeStart.current = undefined
      // Discard uncommitted node movement while retaining the local viewport,
      // including a reveal that did not edit the saved layout.
      updateLayout({ ...layoutRef.current, positions })
    }
  }, [props.readonly, props.movable, updateLayout])
  const dimensions = useCallback(() => ({ width: root.current?.clientWidth || 800, height: root.current?.clientHeight || 600 }), [])
  const focus = useCallback((id?: string) => {
    const node = id ? [...(root.current?.querySelectorAll<HTMLElement>('[data-node-id]') || [])].find(element => element.dataset.nodeId === id) : null
    ;(node || root.current)?.focus({ preventScroll: true })
  }, [])
  const fit = useCallback(() => { const { width, height } = dimensions(); updateLayout(fitLayout(latest.current.graph, layoutRef.current, width, height), true) }, [dimensions, updateLayout])
  const reveal = useCallback((id: string) => {
    const { width, height } = dimensions(); updateLayout(revealLayout(latest.current.graph, layoutRef.current, id, width, height))
  }, [dimensions, updateLayout])
  const controller = useMemo<WorkflowCanvasHandle>(() => ({ fit, focus, reveal }), [fit, focus, reveal])
  const cancel = useCallback(() => {
    connectionCancelled.current = true; setConnectionDragging(false)
    flowStore.getState().cancelConnection(); flowStore.setState({ connectionClickStartHandle: null })
    latest.current.onCancel()
  }, [flowStore])
  useLayoutEffect(() => { props.onReady?.(controller); return () => props.onReady?.(undefined) }, [controller, props.onReady])
  const scale = useCallback((amount: number) => { const { width, height } = dimensions(); updateLayout(zoomLayout(layoutRef.current, amount, width, height), true) }, [dimensions, updateLayout])
  const choose = useCallback((id: string) => {
    if (gestureMoved.current) { gestureMoved.current = false; return }
    focus(id); latest.current.connecting ? latest.current.onConnect(id) : latest.current.onSelect(id)
  }, [focus])
  const keys = useCallback((event: KeyboardEvent<HTMLElement>, id: string) => {
    if (event.target !== event.currentTarget) return
    const current = latest.current
    if (event.key === 'Enter' || event.key === ' ') { event.preventDefault(); event.stopPropagation(); current.connecting ? current.onConnect(id) : current.onSelect(id); return }
    if (current.readonly && !current.movable) return
    if (event.key === 'Delete' || event.key === 'Backspace') { if (!current.readonly) { event.preventDefault(); event.stopPropagation(); current.onRemove(id) }; return }
    const delta = shifts[event.key]
    if (delta) { event.preventDefault(); event.stopPropagation(); const point = positionOf(current.graph, layoutRef.current, id)
      updateLayout({ ...copyLayout(layoutRef.current), positions: { ...layoutRef.current.positions, [id]: { x: point.x + delta.x, y: point.y + delta.y } } }, true) }
  }, [updateLayout])
  const nodes = useMemo<WorkflowFlowNode[]>(() => {
    const defaults = autoLayout(props.graph)
    return props.graph.nodes.map((node, index) => ({ id: node.id, type: 'workflow', position: local.positions[node.id] ?? defaults[node.id]!,
      width: NODE_WIDTH, height: NODE_HEIGHT, ariaLabel: node.title, selected: props.selected === node.id, draggable: !props.readonly || !!props.movable,
      measured: flowStore.getState().nodeLookup.get(node.id)?.measured,
      data: { node, index, active: props.selected === node.id, connecting: props.connecting === node.id, readonly: !!props.readonly,
        roleName: node.roleId ? props.roleNames?.[node.roleId] : undefined, state: props.states?.[node.id], showState: !!props.states,
        choose, keys, connect: props.onConnect } }))
  }, [props.graph, props.selected, props.connecting, props.readonly, props.movable, props.roleNames, props.states, props.onConnect, local.positions, choose, keys, flowStore])
  const edges = useMemo<WorkflowFlowEdge[]>(() => props.graph.edges.map(edge => ({ id: edge.id, source: edge.from, target: edge.to,
    sourceHandle: 'outgoing', targetHandle: 'incoming', type: 'workflow', selected: props.selectedEdge === edge.id,
    ariaLabel: `连接：${props.graph.nodes.find(node => node.id === edge.from)?.title} 到 ${props.graph.nodes.find(node => node.id === edge.to)?.title}`,
    data: { label: edge.outcome ? outcomeTitle(props.graph.nodes.find(node => node.id === edge.from), edge.outcome) : '',
      description: `编辑连接：${props.graph.nodes.find(node => node.id === edge.from)?.title} 到 ${props.graph.nodes.find(node => node.id === edge.to)?.title}`,
      choose: props.onEdge } })), [props.graph, props.selectedEdge, props.onEdge])
  function changes(values: NodeChange<WorkflowFlowNode>[]) {
    if (!nodeStart.current || latest.current.readonly && !latest.current.movable) return
    const positions = { ...layoutRef.current.positions }
    let changed = false
    for (const change of values) if (change.type === 'position' && change.position) { positions[change.id] = { ...change.position }; changed = true }
    if (changed) updateLayout({ ...layoutRef.current, positions })
  }
  function connect(connection: Connection) {
    if (!connectionCancelled.current && !latest.current.readonly && connection.source && connection.target) latest.current.onConnectPair?.(connection.source, connection.target)
  }
  function startGesture(x: number, y: number) { pointerStart.current = { x, y }; gestureMoved.current = false }
  function moveGesture(x: number, y: number) {
    const start = pointerStart.current
    if (start && Math.abs(x - start.x) + Math.abs(y - start.y) >= 3) gestureMoved.current = true
  }
  return <section ref={root} className={`workflow-canvas workflow-canvas-react${connectionDragging ? ' workflow-connection-dragging' : ''}`}
    aria-label="流程画布" tabIndex={0} data-canvas-runtime="react" data-canvas-kind="workflow"
    onPointerDownCapture={event => startGesture(event.clientX, event.clientY)} onPointerMoveCapture={event => moveGesture(event.clientX, event.clientY)}
    onMouseDownCapture={event => startGesture(event.clientX, event.clientY)} onMouseMoveCapture={event => moveGesture(event.clientX, event.clientY)}
    onClick={event => { if (event.target === event.currentTarget) { if (!gestureMoved.current) cancel(); gestureMoved.current = false } }}
    onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); cancel() } }}>
    <ReactFlow<WorkflowFlowNode, WorkflowFlowEdge> nodes={nodes} edges={edges} nodeTypes={nodeTypes} edgeTypes={edgeTypes}
      defaultViewport={{ x: props.layout.x, y: props.layout.y, zoom: props.layout.zoom }}
      viewport={{ x: local.x, y: local.y, zoom: local.zoom }} onViewportChange={viewport => updateLayout({ ...layoutRef.current, ...viewport })}
      onMoveStart={(event, viewport) => { if (event?.type) viewportStart.current = { ...viewport } }}
      onMoveEnd={(event, viewport) => {
        const start = viewportStart.current
        // Controlled viewport synchronization can carry an internal object as
        // sourceEvent. Only browser input events represent a layout edit.
        if (event?.type && start && (start.x !== viewport.x || start.y !== viewport.y || start.zoom !== viewport.zoom)) updateLayout({ ...layoutRef.current, ...viewport }, true)
        viewportStart.current = undefined
      }}
      onNodesChange={changes} onNodeDragStart={(_event, node) => {
        if (latest.current.readonly && !latest.current.movable) return
        nodeStart.current = { id: node.id, position: { ...node.position }, positions: copyLayout(layoutRef.current).positions, moved: false }
      }}
      onNodeDrag={(_event, node) => {
        const start = nodeStart.current
        if (start?.id === node.id && !start.moved && (Math.abs(node.position.x - start.position.x) + Math.abs(node.position.y - start.position.y)) * layoutRef.current.zoom >= 3) {
          start.moved = true
          if (!latest.current.connecting) latest.current.onSelect(node.id)
        }
      }}
      onNodeDragStop={(_event, node) => {
        const start = nodeStart.current; nodeStart.current = undefined
        if (start?.id !== node.id) return
        if (!start.moved || latest.current.readonly && !latest.current.movable) { updateLayout({ ...layoutRef.current, positions: start.positions }); return }
        if (start.position.x !== node.position.x || start.position.y !== node.position.y)
          updateLayout({ ...layoutRef.current, positions: { ...layoutRef.current.positions, [node.id]: { ...node.position } } }, true)
      }}
      onConnect={connect} onConnectStart={() => { connectionCancelled.current = false; setConnectionDragging(true) }} onConnectEnd={() => setConnectionDragging(false)}
      onClickConnectStart={() => { connectionCancelled.current = false; setConnectionDragging(true) }} onClickConnectEnd={() => setConnectionDragging(false)}
      onPaneClick={() => { if (!gestureMoved.current) cancel(); gestureMoved.current = false }}
      minZoom={.1} maxZoom={4} nodeDragThreshold={0} nodeClickDistance={3} nodesDraggable={!props.readonly || !!props.movable} nodesConnectable={!props.readonly}
      nodesFocusable={false} edgesFocusable={false} disableKeyboardA11y deleteKeyCode={null} selectionKeyCode={null} multiSelectionKeyCode={null}
      selectNodesOnDrag={false} panActivationKeyCode={null} zoomOnScroll={false} zoomOnPinch zoomOnDoubleClick={false} attributionPosition="top-right">
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
