import { useCallback, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type RefObject } from 'react'
import type { WorkflowCanvasProps } from './types'
import type { WorkflowLayout, WorkflowPoint } from '@/types/domain'
import { copyLayout, positionOf } from './layout'
import { ownResources } from '@/react/gestures/pointerResources'

type Port = 'source' | 'target'
type Sample = { point: WorkflowPoint; target: HTMLElement }
type PanAnchor = { layout: WorkflowLayout; center: WorkflowPoint; distance: number }
type Session = {
  kind: 'drag' | 'connect' | 'pan'
  button: number
  pointers: Map<number, Sample>
  origin: WorkflowPoint
  layout: WorkflowLayout
  graph: string
  incomingLayout: string
  moved: boolean
  id?: string
  port?: Port
  position?: WorkflowPoint
  anchor?: WorkflowPoint
  pan?: PanAnchor
  dispose(): void
  capture(target: HTMLElement, id: number): boolean
  release(id: number): void
}
export type ConnectionPreview = { source: WorkflowPoint; target: WorkflowPoint }
interface Options {
  root: RefObject<HTMLElement | null>
  props: WorkflowCanvasProps
  layout(): WorkflowLayout
  update(layout: WorkflowLayout, save?: boolean): void
  point(point: WorkflowPoint): WorkflowPoint
}
const pointOf = (event: { clientX: number; clientY: number }): WorkflowPoint => ({ x: event.clientX, y: event.clientY })
const distance = (a: WorkflowPoint, b: WorkflowPoint) => Math.hypot(a.x - b.x, a.y - b.y)
const moved = (a: WorkflowPoint, b: WorkflowPoint) => Math.abs(a.x - b.x) + Math.abs(a.y - b.y) >= 3

export function useWorkflowPointerGestures(options: Options) {
  const latest = useRef(options); latest.current = options
  const active = useRef<Session | undefined>(undefined), mounted = useRef(false), suppressClick = useRef(false)
  const clickPort = useRef<{ id: string; port: Port } | undefined>(undefined)
  const clickCleanup = useRef<(() => void) | undefined>(undefined)
  const [kind, setKind] = useState<Session['kind']>(), [preview, setPreview] = useState<ConnectionPreview>()
  const [tapped, setTapped] = useState<{ id: string; port: Port }>()
  const clear = useCallback((rollback = true) => {
    const session = active.current; active.current = undefined
    clickPort.current = undefined
    clickCleanup.current?.(); clickCleanup.current = undefined
    if (mounted.current) setTapped(undefined)
    if (!session) return
    suppressClick.current = true
    session.dispose()
    if (!mounted.current) return
    setKind(undefined); setPreview(undefined)
    if (rollback && session.kind === 'drag') latest.current.update({ ...latest.current.layout(), positions: session.layout.positions })
    if (rollback && session.kind === 'pan') latest.current.update({ ...latest.current.layout(), x: session.layout.x, y: session.layout.y, zoom: session.layout.zoom })
  }, [])
  useLayoutEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; clear(false) }
  }, [clear])
  function valid(session: Session) {
    const current = latest.current.props
    return mounted.current && active.current === session && session.graph === JSON.stringify(current.graph)
      && session.incomingLayout === JSON.stringify(current.layout)
      && (session.kind === 'pan' || (session.kind === 'drag' ? !current.readonly || !!current.movable : !current.readonly))
  }
  function center(samples: Map<number, Sample>): WorkflowPoint {
    const values = [...samples.values()]
    return values.reduce((point, value) => ({ x: point.x + value.point.x / values.length, y: point.y + value.point.y / values.length }), { x: 0, y: 0 })
  }
  function panAnchor(session: Session) {
    const values = [...session.pointers.values()]
    session.pan = { layout: copyLayout(latest.current.layout()), center: center(session.pointers), distance: values.length === 2 ? distance(values[0]!.point, values[1]!.point) : 0 }
  }
  function connectionTarget(point: WorkflowPoint, session: Session) {
    const root = latest.current.root.current, hit = root?.ownerDocument.elementFromPoint(point.x, point.y)
    if (!hit || !root?.contains(hit)) return
    const handle = hit.closest('.react-flow__handle')
    if (handle && !handle.classList.contains(session.port === 'source' ? 'target' : 'source')) return
    const id = hit.closest<HTMLElement>('.workflow-node')?.dataset.nodeId
    return latest.current.props.graph.nodes.some(node => node.id === id) ? id : undefined
  }
  function move(event: PointerEvent) {
    const session = active.current, sample = session?.pointers.get(event.pointerId)
    if (!session || !sample) return
    if (!valid(session)) { clear(); return }
    if (event.type === 'pointermove' && event.pointerType === 'mouse' && !(event.buttons & (session.button === 1 ? 4 : 1))) { clear(); return }
    event.preventDefault()
    sample.point = pointOf(event)
    const crossed = moved(session.origin, sample.point)
    if (!session.moved && crossed) {
      session.moved = true; suppressClick.current = true
      clickPort.current = undefined; clickCleanup.current?.(); clickCleanup.current = undefined; setTapped(undefined)
      if (session.kind === 'drag' && !latest.current.props.connecting) latest.current.props.onSelect(session.id!)
      if (!valid(session)) return
    }
    if (session.kind === 'drag') {
      if (!session.moved) return
      const point = latest.current.point(sample.point), origin = latest.current.point(session.origin)
      latest.current.update({ ...latest.current.layout(), positions: { ...latest.current.layout().positions,
        [session.id!]: { x: session.position!.x + point.x - origin.x, y: session.position!.y + point.y - origin.y } } })
    } else if (session.kind === 'connect') {
      const point = latest.current.point(sample.point), anchor = session.anchor!
      setPreview(session.port === 'source' ? { source: anchor, target: point } : { source: point, target: anchor })
    } else {
      const anchor = session.pan!, centroid = center(session.pointers), values = [...session.pointers.values()]
      const ratio = values.length === 2 && anchor.distance > 0 ? distance(values[0]!.point, values[1]!.point) / anchor.distance : 1
      const zoom = Math.min(4, Math.max(.1, anchor.layout.zoom * ratio)), factor = zoom / anchor.layout.zoom
      const bounds = latest.current.root.current!.getBoundingClientRect()
      const x = centroid.x - bounds.left, y = centroid.y - bounds.top
      const startX = anchor.center.x - bounds.left, startY = anchor.center.y - bounds.top
      if (ratio !== 1) { session.moved = true; suppressClick.current = true }
      latest.current.update({ ...latest.current.layout(), zoom, x: x - (startX - anchor.layout.x) * factor, y: y - (startY - anchor.layout.y) * factor })
    }
  }
  function up(event: PointerEvent) {
    const session = active.current
    if (!session?.pointers.has(event.pointerId)) return
    if (!valid(session)) { clear(); return }
    // Include the final sample; neither a threshold nor a first move resets the
    // original pointer anchor, so the whole screen delta survives at every zoom.
    move(event)
    if (active.current !== session) return
    session.pointers.delete(event.pointerId)
    if (session.kind === 'pan' && session.pointers.size) { session.release(event.pointerId); panAnchor(session); return }
    const layout = copyLayout(latest.current.layout()), target = session.kind === 'connect' ? connectionTarget(pointOf(event), session) : undefined
    active.current = undefined; session.dispose(); setKind(undefined); setPreview(undefined)
    suppressClick.current = session.moved
    if (!mounted.current) return
    if (session.kind === 'connect') {
      if (session.moved && target) latest.current.props.onConnectPair?.(session.port === 'source' ? session.id! : target, session.port === 'source' ? target : session.id!)
    } else if (session.moved && JSON.stringify(layout) !== JSON.stringify(session.layout)) latest.current.update(layout, true)
    else if (session.kind === 'pan') latest.current.update({ ...layout, x: session.layout.x, y: session.layout.y, zoom: session.layout.zoom })
  }
  function cancelled(event?: PointerEvent) {
    if (!event || active.current?.pointers.has(event.pointerId)) clear()
  }
  function begin(event: ReactPointerEvent<HTMLElement>, kind: Session['kind'], id?: string, port?: Port) {
    if ((kind === 'pan' ? event.button !== 0 && event.button !== 1 : event.button !== 0) || event.isPrimary === false && event.pointerType !== 'touch') return
    const current = latest.current, root = current.root.current
    if (!mounted.current || !root || kind === 'drag' && current.props.readonly && !current.props.movable || kind === 'connect' && current.props.readonly) return
    if (active.current) {
      const session = active.current
      if (kind === 'pan' && session.kind === 'pan' && event.pointerType === 'touch' && session.pointers.size < 2 && valid(session)) {
        event.preventDefault(); event.stopPropagation()
        if (session.capture(root, event.pointerId)) { session.pointers.set(event.pointerId, { point: pointOf(event), target: root }); panAnchor(session) }
      }
      return
    }
    event.preventDefault(); event.stopPropagation(); suppressClick.current = false
    const target = kind === 'pan' ? root : event.currentTarget, resources = ownResources(root, move, up, cancelled)
    const point = pointOf(event), layout = copyLayout(current.layout())
    const session: Session = { kind, button: event.button, pointers: new Map([[event.pointerId, { point, target }]]), origin: point, layout,
      graph: JSON.stringify(current.props.graph), incomingLayout: JSON.stringify(current.props.layout), moved: false, id, port, ...resources }
    active.current = session
    if (!session.capture(target, event.pointerId)) { clear(false); suppressClick.current = false; return }
    if (kind === 'drag') { session.position = { ...positionOf(current.props.graph, layout, id!) }; target.focus({ preventScroll: true }) }
    if (kind === 'connect') {
      const bounds = target.getBoundingClientRect()
      session.anchor = current.point({ x: bounds.x + bounds.width / 2, y: bounds.y + bounds.height / 2 })
      setPreview({ source: session.anchor, target: session.anchor })
      target.focus({ preventScroll: true })
    }
    if (kind === 'pan') panAnchor(session)
    setKind(kind)
  }
  function tap(id: string, port: Port) {
    const current = latest.current.props
    if (!mounted.current || current.readonly) return
    if (active.current) clear()
    const from = clickPort.current
    if (from && from.port !== port) {
      clickPort.current = undefined; setTapped(undefined)
      clickCleanup.current?.(); clickCleanup.current = undefined
      current.onConnectPair?.(port === 'target' ? from.id : id, port === 'target' ? id : from.id)
    } else if (!from && current.connecting) current.onConnect(id)
    else {
      clickPort.current = { id, port }; setTapped({ id, port })
      if (!clickCleanup.current) {
        const ownerWindow = latest.current.root.current?.ownerDocument.defaultView, blur = () => clear()
        ownerWindow?.addEventListener('blur', blur)
        clickCleanup.current = () => ownerWindow?.removeEventListener('blur', blur)
      }
    }
  }
  return {
    kind, preview, tapped, cancel: clear,
    node: (event: ReactPointerEvent<HTMLElement>, id: string) => begin(event, 'drag', id),
    connection: (event: ReactPointerEvent<HTMLElement>, id: string, port: Port) => begin(event, 'connect', id, port),
    pane: (event: ReactPointerEvent<HTMLElement>) => begin(event, 'pan'),
    tap,
    consumeClick: () => { const value = suppressClick.current; suppressClick.current = false; return value },
  }
}
