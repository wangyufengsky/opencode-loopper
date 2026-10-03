import { useCallback, useLayoutEffect, useRef, useState, type PointerEvent as ReactPointerEvent, type RefObject } from 'react'
import type { Viewport, XYPosition } from '@xyflow/react'
import { ownResources } from '@/react/gestures/pointerResources'

export const DIAGRAM_MIN_ZOOM = .2, DIAGRAM_MAX_ZOOM = 2
const initialViewport: Viewport = { x: 14, y: 12, zoom: 1 }
const pointOf = (event: { clientX: number; clientY: number }) => ({ x: event.clientX, y: event.clientY })
const distance = (a: XYPosition, b: XYPosition) => Math.hypot(a.x - b.x, a.y - b.y)
const crossed = (a: XYPosition, b: XYPosition) => Math.abs(a.x - b.x) + Math.abs(a.y - b.y) >= 3
const controls = 'button, input, select, textarea, a, [contenteditable], .react-flow__controls, .react-flow__attribution'
type Anchor = { viewport: Viewport; center: XYPosition; distance: number }
type Session = ReturnType<typeof ownResources> & {
  pointers: Map<number, XYPosition>
  original: Viewport
  origin: XYPosition
  anchor: Anchor
  scope: string
  button: number
  moved: boolean
  touchTap: boolean
}

export function useDiagramViewportGestures(root: RefObject<HTMLDivElement | null>, scope: string) {
  const currentScope = useRef(scope); currentScope.current = scope
  const acceptedScope = useRef(scope), mounted = useRef(false), active = useRef<Session | undefined>(undefined)
  const viewportRef = useRef({ ...initialViewport }), lastTap = useRef<{ at: number; point: XYPosition } | undefined>(undefined)
  const [viewport, setViewport] = useState(viewportRef.current), [kind, setKind] = useState<'pan'>()
  const update = useCallback((next: Viewport) => {
    if (!mounted.current) return
    viewportRef.current = next; setViewport(next)
  }, [])
  const cancel = useCallback((rollback = true) => {
    const session = active.current; active.current = undefined; lastTap.current = undefined
    session?.dispose()
    if (!mounted.current) return
    setKind(undefined)
    if (session && rollback) update(session.original)
  }, [update])
  useLayoutEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; cancel(false) }
  }, [cancel])
  useLayoutEffect(() => {
    if (acceptedScope.current !== scope) { cancel(); acceptedScope.current = scope }
  }, [scope, cancel])
  const dimensions = useCallback(() => {
    const element = root.current, bounds = element?.getBoundingClientRect()
    return { width: element?.clientWidth || bounds?.width || 800, height: element?.clientHeight || bounds?.height || 300 }
  }, [root])
  const change = useCallback((project: (value: Viewport) => Viewport) => {
    cancel()
    if (mounted.current) update(project(viewportRef.current))
  }, [cancel, update])
  const zoom = useCallback((amount: number, screenPoint?: XYPosition) => change(value => {
    const bounds = root.current!.getBoundingClientRect(), size = dimensions()
    const point = screenPoint ? { x: screenPoint.x - bounds.left, y: screenPoint.y - bounds.top } : { x: size.width / 2, y: size.height / 2 }
    const zoom = Math.min(DIAGRAM_MAX_ZOOM, Math.max(DIAGRAM_MIN_ZOOM, value.zoom * amount)), ratio = zoom / value.zoom
    return { x: point.x - (point.x - value.x) * ratio, y: point.y - (point.y - value.y) * ratio, zoom }
  }), [change, dimensions, root])
  useLayoutEffect(() => {
    const element = root.current
    if (!element) return
    const mac = !!element.ownerDocument.defaultView?.navigator.userAgent.includes('Mac')
    const wheel = (event: WheelEvent) => {
      if ((!event.ctrlKey && !(mac && event.metaKey)) || !event.deltaY || (event.target as Element).closest('.nowheel')) return
      event.preventDefault(); event.stopPropagation()
      // Match the locked XYFlow wheel curve, including Mac trackpad pinch.
      const delta = -event.deltaY * (event.deltaMode === 1 ? .05 : event.deltaMode ? 1 : .002) * (event.ctrlKey && mac ? 10 : 1)
      zoom(Math.pow(2, delta), pointOf(event))
    }
    element.addEventListener('wheel', wheel, { passive: false })
    return () => element.removeEventListener('wheel', wheel)
  }, [root, zoom])
  function center(pointers: Map<number, XYPosition>) {
    return [...pointers.values()].reduce((point, value) => ({ x: point.x + value.x / pointers.size, y: point.y + value.y / pointers.size }), { x: 0, y: 0 })
  }
  function anchor(session: Session) {
    const values = [...session.pointers.values()]
    session.anchor = { viewport: { ...viewportRef.current }, center: center(session.pointers), distance: values.length === 2 ? distance(values[0]!, values[1]!) : 0 }
  }
  function valid(session: Session) { return mounted.current && active.current === session && session.scope === currentScope.current }
  function move(event: PointerEvent) {
    const session = active.current
    if (!session?.pointers.has(event.pointerId)) return
    if (!valid(session) || event.type === 'pointermove' && event.pointerType === 'mouse' && !(event.buttons & (session.button === 1 ? 4 : 1))) { cancel(); return }
    event.preventDefault(); session.pointers.set(event.pointerId, pointOf(event))
    const centroid = center(session.pointers), values = [...session.pointers.values()], start = session.anchor
    const ratio = values.length === 2 && start.distance > 0 ? distance(values[0]!, values[1]!) / start.distance : 1
    if (crossed(session.origin, pointOf(event)) || ratio !== 1) session.moved = true
    if (!session.moved) return
    const zoom = Math.min(DIAGRAM_MAX_ZOOM, Math.max(DIAGRAM_MIN_ZOOM, start.viewport.zoom * ratio)), factor = zoom / start.viewport.zoom
    const bounds = root.current!.getBoundingClientRect()
    const x = centroid.x - bounds.left, y = centroid.y - bounds.top
    update({ x: x - (start.center.x - bounds.left - start.viewport.x) * factor,
      y: y - (start.center.y - bounds.top - start.viewport.y) * factor, zoom })
  }
  function up(event: PointerEvent) {
    const session = active.current
    if (!session?.pointers.has(event.pointerId)) return
    if (!valid(session)) { cancel(); return }
    move(event)
    if (active.current !== session) return
    session.pointers.delete(event.pointerId)
    if (session.pointers.size) { session.release(event.pointerId); anchor(session); return }
    active.current = undefined; session.dispose()
    if (!mounted.current) return
    setKind(undefined)
    if (session.touchTap && !session.moved) {
      const prior = lastTap.current, point = pointOf(event)
      if (prior && event.timeStamp - prior.at < 500 && distance(prior.point, point) < 10) zoom(2, point)
      else lastTap.current = { at: event.timeStamp, point }
    } else lastTap.current = undefined
  }
  function interrupted(event?: PointerEvent) { if (!event || active.current?.pointers.has(event.pointerId)) cancel() }
  function pointerDown(event: ReactPointerEvent<HTMLDivElement>) {
    const element = root.current, target = event.target as Element
    if (!mounted.current || !element || target.closest(controls) || event.button !== 0 && event.button !== 1
      || event.isPrimary === false && event.pointerType !== 'touch' || event.ctrlKey && event.pointerType === 'mouse' && event.button === 0) return
    const blank = target === element || target.classList.contains('react-flow__pane'), edge = !!target.closest('.react-flow__edge')
    const node = !!target.closest('.react-flow__node')
    if (!(blank || edge || event.button === 1 && node) || event.button === 0 && !!target.closest('.nopan')) return
    const session = active.current
    if (session) {
      if (event.pointerType === 'touch' && session.pointers.size < 2 && valid(session)) {
        event.preventDefault(); event.stopPropagation()
        if (session.capture(element, event.pointerId)) { session.touchTap = false; session.pointers.set(event.pointerId, pointOf(event)); anchor(session) }
      }
      return
    }
    event.preventDefault(); event.stopPropagation()
    if (event.pointerType !== 'touch') lastTap.current = undefined
    const point = pointOf(event), original = { ...viewportRef.current }, resources = ownResources(element, move, up, interrupted)
    const next: Session = { ...resources, pointers: new Map([[event.pointerId, point]]), original, origin: point,
      anchor: { viewport: original, center: point, distance: 0 }, scope: currentScope.current, button: event.button, moved: false, touchTap: event.pointerType === 'touch' }
    active.current = next
    if (!next.capture(element, event.pointerId)) { cancel(false); return }
    setKind('pan')
  }
  return { viewport, kind, cancel, change, zoom, dimensions, getViewport: () => viewportRef.current, pointerDown }
}
