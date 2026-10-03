import { act, cleanup, fireEvent, render, within } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { StageDiagram } from './StageDiagram'
import { installPointerEnvironment, nativeMouse, pane, ready, root, stages, trackGestureResources, trackWheelResources, transform, viewport, type PointerEnvironment } from './readonlyDiagramTestHelpers'

let input: PointerEnvironment
beforeEach(() => { input = installPointerEnvironment() })
afterEach(() => { cleanup(); vi.restoreAllMocks(); input.restore() })

const closeViewport = (actual: ReturnType<typeof viewport>, expected: ReturnType<typeof viewport>) => {
  expect(actual.x).toBeCloseTo(expected.x, 8); expect(actual.y).toBeCloseTo(expected.y, 8); expect(actual.zoom).toBeCloseTo(expected.zoom, 8)
}

describe('real readonly React Flow viewport input', () => {
  it.each(['root', 'pane', 'middle-edge', 'middle-node'])('%s preserves the exact 105px/20px first screen displacement and ends normally once', async targetKind => {
    const values = stages(), original = JSON.stringify(values), view = render(<StrictMode><StageDiagram stages={values} /></StrictMode>)
    await ready(view.container)
    const island = root(view.container), resources = trackGestureResources([island])
    const target = targetKind === 'root' ? island : targetKind === 'pane' ? pane(view.container)
      : view.container.querySelector<HTMLElement>(targetKind === 'middle-edge' ? '.react-flow__edge .react-flow__edge-path' : '.phase-objective p')!
    const options = targetKind.startsWith('middle-') ? { button: 1, buttons: 4 } : {}
    try {
      input.pointer(target, 'pointerdown', 100, 100, options)
      expect(island.dataset.pointerGesture).toBe('pan'); expect(input.captures.get(1)).toBe(island)
      input.pointer(island, 'pointermove', 205, 120, options)
      expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      input.pointer(island, 'pointerup', 205, 120, { ...options, buttons: 0 })
      expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      expect(island.dataset.pointerGesture).toBeUndefined()
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size }).toEqual({ listeners: [], raf: 0, capture: 0 })
      input.pointer(island, 'pointermove', 700, 500)
      expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      expect(JSON.stringify(values)).toBe(original)
      expect(view.container.querySelectorAll('.react-flow__node')).toHaveLength(2)
      expect(view.container.querySelectorAll('.react-flow__edge')).toHaveLength(1)
    } finally { resources.restore() }
  })

  it('retains the full delta at non-unit zoom and does not pan on a jitter click', async () => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    fireEvent.click(within(root(view.container)).getByRole('button', { name: '放大流程图' }))
    const initial = viewport(view.container), island = root(view.container)
    expect(initial.zoom).toBeCloseTo(1.2)
    input.pointer(pane(view.container), 'pointerdown', 100, 100)
    input.pointer(island, 'pointermove', 101, 101)
    expect(viewport(view.container)).toEqual(initial)
    input.pointer(island, 'pointerup', 101, 101)
    expect(viewport(view.container)).toEqual(initial); expect(input.captures.size).toBe(0)
    input.pointer(pane(view.container), 'pointerdown', 100, 100)
    input.pointer(island, 'pointermove', 205, 120)
    closeViewport(viewport(view.container), { ...initial, x: initial.x + 105, y: initial.y + 20 })
    input.pointer(island, 'pointerup', 205, 120)
    closeViewport(viewport(view.container), { ...initial, x: initial.x + 105, y: initial.y + 20 })
  })

  it('keeps left-pointer node text selectable and ignores readonly edits and excluded controls', async () => {
    const values = stages(), original = JSON.stringify(values), view = render(<StageDiagram stages={values} />)
    await ready(view.container)
    const island = root(view.container), node = view.container.querySelector<HTMLElement>('.react-flow__node')!, text = node.querySelector<HTMLElement>('.phase-objective p')!
    const initial = transform(view.container), position = node.style.transform
    const down = input.pointer(text, 'pointerdown', 100, 100)
    expect(down.defaultPrevented).toBe(false)
    input.pointer(text, 'pointermove', 205, 120); input.pointer(text, 'pointerup', 205, 120)
    expect(island.dataset.pointerGesture).toBeUndefined(); expect(input.captures.size).toBe(0)
    expect(transform(view.container)).toBe(initial)
    expect(node.style.pointerEvents).toBe('all'); expect(node.style.userSelect).toBe('text')
    for (const key of ['ArrowRight', 'Delete', 'Enter', ' ']) fireEvent.keyDown(node, { key })
    expect(node.style.transform).toBe(position); expect(node.classList.contains('selected')).toBe(false)
    for (const target of [within(island).getByRole('button', { name: '放大流程图' }), island.querySelector<HTMLElement>('.react-flow__attribution a')!]) {
      const event = input.pointer(target, 'pointerdown', 100, 100, { button: 1, buttons: 4 })
      expect(event.defaultPrevented).toBe(false); expect(island.dataset.pointerGesture).toBeUndefined()
    }
    expect(JSON.stringify(values)).toBe(original)
  })

  it('owns middle-button capture and never starts native d3 through compatibility mousedown', async () => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    const island = root(view.container), resources = trackGestureResources([island]), browserWindow = island.ownerDocument.defaultView!
    try {
      input.pointer(pane(view.container), 'pointerdown', 100, 100, { button: 1, buttons: 4 })
      const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 1, buttons: 4, clientX: 100, clientY: 100 })
      Object.defineProperty(event, 'view', { value: browserWindow }); fireEvent(pane(view.container), event)
      expect(resources.listeners()).toHaveLength(5)
      expect(resources.listeners().filter(listener => ['mousemove', 'mouseup', 'dragstart', 'selectstart'].includes(listener.type))).toEqual([])
      input.pointer(island, 'pointermove', 205, 120, { button: 1, buttons: 4 })
      expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      view.unmount()
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size }).toEqual({ listeners: [], raf: 0, capture: 0 })
    } finally { resources.restore() }
  })

  it('ignores foreign pointers and cancels a mouse session when its held button disappears', async () => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    const island = root(view.container), initial = transform(view.container), resources = trackGestureResources([island])
    try {
      input.pointer(pane(view.container), 'pointerdown', 100, 100)
      input.pointer(island, 'pointermove', 600, 600, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      input.pointer(island, 'pointerup', 600, 600, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      expect(transform(view.container)).toBe(initial); expect(input.captures.get(1)).toBe(island)
      input.pointer(island, 'pointermove', 205, 120)
      expect(transform(view.container)).not.toBe(initial)
      input.pointer(island, 'pointermove', 300, 150, { buttons: 0 })
      expect(transform(view.container)).toBe(initial); expect(island.dataset.pointerGesture).toBeUndefined()
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size }).toEqual({ listeners: [], raf: 0, capture: 0 })
    } finally { resources.restore() }
  })

  it('keeps Chinese zoom/fit controls, clamps zoom and fits the actual saved graph', async () => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    const island = root(view.container), controls = within(island)
    const zoomIn = controls.getByRole('button', { name: '放大流程图' }), zoomOut = controls.getByRole('button', { name: '缩小流程图' })
    expect(island.querySelector('.react-flow__controls')?.getAttribute('aria-label')).toBe('流程图视图操作')
    fireEvent.click(zoomIn)
    closeViewport(viewport(view.container), { x: -63.2, y: -25.6, zoom: 1.2 })
    for (let index = 0; index < 10; index++) fireEvent.click(zoomIn)
    expect(viewport(view.container).zoom).toBe(2); expect(zoomIn.hasAttribute('disabled')).toBe(true)
    for (let index = 0; index < 30; index++) fireEvent.click(zoomOut)
    expect(viewport(view.container).zoom).toBe(.2); expect(zoomOut.hasAttribute('disabled')).toBe(true)
    fireEvent.click(controls.getByRole('button', { name: '适应流程图' }))
    const fitted = viewport(view.container)
    expect(fitted.zoom).toBeGreaterThan(.2); expect(fitted.zoom).toBeLessThan(2)
    expect(fitted.x).toBeGreaterThanOrEqual(0); expect(fitted.y).toBeGreaterThanOrEqual(0)
    expect(fitted.x + 614 * fitted.zoom).toBeLessThanOrEqual(800)
    expect(fitted.y + 240 * fitted.zoom).toBeLessThanOrEqual(400)
    expect(view.container.querySelectorAll('.react-flow__edge')).toHaveLength(1)
  })

  it.each(['controls', 'wheel', 'doubleclick'])('%s aborts and rolls back an active pan before programmatic zoom, rejecting late release', async action => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    const island = root(view.container), resources = trackGestureResources([island])
    try {
      input.pointer(pane(view.container), 'pointerdown', 100, 100)
      input.pointer(island, 'pointermove', 205, 120)
      expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      if (action === 'controls') {
        fireEvent.click(within(island).getByRole('button', { name: '放大流程图' }))
        closeViewport(viewport(view.container), { x: -63.2, y: -25.6, zoom: 1.2 })
      } else if (action === 'wheel') {
        fireEvent.wheel(pane(view.container), { ctrlKey: true, deltaY: -500, clientX: 100, clientY: 100 })
        closeViewport(viewport(view.container), { x: -72, y: -76, zoom: 2 })
      } else {
        fireEvent.doubleClick(pane(view.container), { clientX: 100, clientY: 100 })
        closeViewport(viewport(view.container), { x: -72, y: -76, zoom: 2 })
      }
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size }).toEqual({ listeners: [], raf: 0, capture: 0 })
      const settled = transform(view.container)
      input.pointer(island, 'pointermove', 800, 800); input.pointer(island, 'pointerup', 800, 800)
      expect(transform(view.container)).toBe(settled)
    } finally { resources.restore() }
  })

  it('handles Ctrl-wheel with the existing curve, leaves ordinary wheel alone and removes the lifecycle listener by identity', async () => {
    const wheel = trackWheelResources(), view = render(<StrictMode><StageDiagram stages={stages()} /></StrictMode>)
    await ready(view.container)
    try {
      expect(wheel.listeners()).toHaveLength(1)
      expect(wheel.listeners()[0]!.target).toBe(root(view.container)); expect(wheel.listeners()[0]!.capture).toBe(false)
      const original = transform(view.container)
      const plain = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 100 })
      fireEvent(pane(view.container), plain)
      expect(plain.defaultPrevented).toBe(false); expect(transform(view.container)).toBe(original)
      const zero = new WheelEvent('wheel', { bubbles: true, cancelable: true, ctrlKey: true, deltaY: 0 })
      fireEvent(pane(view.container), zero)
      // XYFlow's public filter still suppresses native Ctrl-wheel page zoom,
      // including a zero delta; the viewport itself must remain unchanged.
      expect(zero.defaultPrevented).toBe(true); expect(transform(view.container)).toBe(original)
      const ctrl = new WheelEvent('wheel', { bubbles: true, cancelable: true, ctrlKey: true, deltaY: 100, clientX: 100, clientY: 100 })
      fireEvent(pane(view.container), ctrl)
      const zoom = Math.pow(2, -.2)
      expect(ctrl.defaultPrevented).toBe(true)
      closeViewport(viewport(view.container), { x: 100 - 86 * zoom, y: 100 - 88 * zoom, zoom })
      view.unmount()
      expect(wheel.listeners()).toEqual([])
    } finally { wheel.restore() }
  })

  it.each(['Control', 'Meta'])('preserves the Mac %s wheel curve around the actual pointer position', async key => {
    vi.spyOn(window.navigator, 'userAgent', 'get').mockReturnValue('Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7)')
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    fireEvent.keyDown(document, { key })
    const wheel = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: 100,
      ctrlKey: key === 'Control', metaKey: key === 'Meta', clientX: 100, clientY: 100 })
    fireEvent(pane(view.container), wheel)
    const zoom = Math.pow(2, key === 'Control' ? -2 : -.2)
    expect(wheel.defaultPrevented).toBe(true)
    closeViewport(viewport(view.container), { x: 100 - 86 * zoom, y: 100 - 88 * zoom, zoom })
    fireEvent.keyUp(document, { key })
  })

  it('preserves background double click and Shift reverse zoom but excludes readonly node text', async () => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    fireEvent.doubleClick(pane(view.container), { clientX: 100, clientY: 100 })
    expect(viewport(view.container)).toEqual({ x: -72, y: -76, zoom: 2 })
    fireEvent.doubleClick(pane(view.container), { shiftKey: true, clientX: 100, clientY: 100 })
    expect(viewport(view.container)).toEqual({ x: 14, y: 12, zoom: 1 })
    fireEvent.doubleClick(view.container.querySelector('.phase-objective p')!, { clientX: 100, clientY: 100 })
    expect(viewport(view.container)).toEqual({ x: 14, y: 12, zoom: 1 })
    // The locked React Flow EdgeWrapper adds nopan even for readonly edges;
    // preserve the original left-button and double-click exclusion.
    const edge = view.container.querySelector<HTMLElement>('.react-flow__edge .react-flow__edge-path')!
    const down = input.pointer(edge, 'pointerdown', 100, 100)
    expect(down.defaultPrevented).toBe(false); expect(root(view.container).dataset.pointerGesture).toBeUndefined()
    input.pointer(edge, 'pointermove', 205, 120); input.pointer(edge, 'pointerup', 205, 120)
    fireEvent.doubleClick(edge, { clientX: 100, clientY: 100 })
    expect(viewport(view.container)).toEqual({ x: 14, y: 12, zoom: 1 }); expect(input.captures.size).toBe(0)
  })

  it('touch pan and pinch keep centroid anchoring and the remaining finger can continue before a normal release', async () => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    const island = root(view.container), resources = trackGestureResources([island])
    try {
      input.pointer(pane(view.container), 'pointerdown', 100, 100, { pointerType: 'touch' })
      input.pointer(island, 'pointermove', 205, 120, { pointerType: 'touch' })
      expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      input.pointer(island, 'pointerup', 205, 120, { pointerType: 'touch' })
      input.pointer(pane(view.container), 'pointerdown', 100, 100, { pointerType: 'touch' })
      input.pointer(pane(view.container), 'pointerdown', 200, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      input.pointer(island, 'pointermove', 250, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      expect(viewport(view.container)).toEqual({ x: 128.5, y: -2, zoom: 1.5 })
      input.pointer(island, 'pointerup', 250, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      expect(input.captures.get(2)).toBeUndefined(); expect(input.captures.get(1)).toBe(island)
      input.pointer(island, 'pointermove', 120, 110, { pointerType: 'touch' })
      expect(viewport(view.container)).toEqual({ x: 148.5, y: 8, zoom: 1.5 })
      input.pointer(island, 'pointerup', 120, 110, { pointerType: 'touch' })
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size }).toEqual({ listeners: [], raf: 0, capture: 0 })
    } finally { resources.restore() }
  })

  it('touch double tap zooms once without timers and a two-pointer gesture clears the old tap intent', async () => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    const island = root(view.container)
    const tap = () => { input.pointer(pane(view.container), 'pointerdown', 100, 100, { pointerType: 'touch' }); input.pointer(island, 'pointerup', 100, 100, { pointerType: 'touch' }) }
    tap(); expect(viewport(view.container).zoom).toBe(1)
    tap(); expect(viewport(view.container).zoom).toBe(2)
    fireEvent.click(within(island).getByRole('button', { name: '缩小流程图' }))
    const initial = viewport(view.container)
    tap()
    input.pointer(pane(view.container), 'pointerdown', 100, 100, { pointerType: 'touch' })
    input.pointer(pane(view.container), 'pointerdown', 200, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
    input.pointer(island, 'pointerup', 200, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
    input.pointer(island, 'pointerup', 100, 100, { pointerType: 'touch' })
    tap()
    expect(viewport(view.container)).toEqual(initial)
  })

  it('keyboard focus reveals a long saved node at current zoom and aborts the older pan without moving any node', async () => {
    const values = stages(20), original = JSON.stringify(values), view = render(<StageDiagram stages={values} />)
    await ready(view.container, 19)
    const island = root(view.container), last = view.container.querySelector<HTMLElement>('[data-id="stage-19"]')!, resources = trackGestureResources([island])
    try {
      fireEvent.click(within(island).getByRole('button', { name: '放大流程图' }))
      const originalPosition = last.style.transform
      input.pointer(pane(view.container), 'pointerdown', 100, 100)
      input.pointer(island, 'pointermove', 205, 120)
      expect(island.dataset.pointerGesture).toBe('pan')
      act(() => { last.focus(); fireEvent.focus(last) })
      const revealed = viewport(view.container)
      expect(revealed.zoom).toBeCloseTo(1.2)
      expect(19 * 322 * revealed.zoom + revealed.x).toBeGreaterThan(0)
      expect((19 * 322 + 292) * revealed.zoom + revealed.x).toBeLessThan(800)
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size }).toEqual({ listeners: [], raf: 0, capture: 0 })
      const settled = transform(view.container)
      input.pointer(island, 'pointermove', 800, 800); input.pointer(island, 'pointerup', 800, 800)
      expect(transform(view.container)).toBe(settled)
      for (const key of ['ArrowRight', 'Delete', 'Enter', ' ']) fireEvent.keyDown(last, { key })
      expect(last.style.transform).toBe(originalPosition); expect(last.classList.contains('selected')).toBe(false)
      expect(JSON.stringify(values)).toBe(original); expect(view.container.querySelectorAll('.react-flow__edge')).toHaveLength(19)
    } finally { resources.restore() }
  })

  it('leaves a native left mousedown unable to create an unowned pan', async () => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    const island = root(view.container), resources = trackGestureResources([island]), initial = transform(view.container), browserWindow = island.ownerDocument.defaultView!
    try {
      nativeMouse(pane(view.container), 'mousedown', 100, 100, browserWindow)
      nativeMouse(browserWindow, 'mousemove', 205, 120, browserWindow)
      expect(transform(view.container)).toBe(initial)
      expect(resources.listeners()).toEqual([])
      view.unmount()
      expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
    } finally { resources.restore() }
  })
})
