import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkflowCanvasView } from './WorkflowCanvasReact'
import type { WorkflowCanvasHandle } from './types'
import { canvas, connected, fixture, hitTarget, installPointerEnvironment, node, trackGestureResources, type PointerEnvironment } from './workflowPointerTestHelpers'

let input: PointerEnvironment
beforeEach(() => { input = installPointerEnvironment() })
afterEach(() => { cleanup(); vi.restoreAllMocks(); input.restore() })
function pointer(target: HTMLElement | Document | Window, type: string, x: number, y: number, _browserWindow?: Window) {
  return input.pointer(target, type, x, y)
}

describe('real React Flow workflow canvas', () => {
  it('renders native nodes and selectable edges, preserves outcomes and moves by keyboard without changing the graph', async () => {
    const props = fixture({ selectedEdge: 'edge', states: { review: 'RUNNING' } })
    props.graph.nodes[0]!.outcomes = ['pass']; props.graph.edges[0]!.outcome = 'pass'
    const before = JSON.stringify(props.graph), layout = JSON.stringify(props.layout)
    const { container } = render(<WorkflowCanvasView {...props} />)
    expect(container.querySelector('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')).toBeTruthy()
    expect(container.querySelectorAll('.react-flow__node')).toHaveLength(2)
    await connected(container)
    expect(container.querySelector('.workflow-edge-label')?.textContent).toBe('通过')
    expect(container.querySelector('.workflow-node-state')?.textContent).toBe('执行中')
    const wire = container.querySelector('.workflow-wire-hit')!
    expect(wire.getAttribute('aria-pressed')).toBe('true')
    fireEvent.keyDown(wire, { key: ' ' }); expect(props.onEdge).toHaveBeenCalledWith('edge')
    fireEvent.click(node(container)); expect(props.onSelect).toHaveBeenCalledWith('review')
    fireEvent.keyDown(node(container), { key: 'ArrowRight' })
    expect(props.onLayout).toHaveBeenLastCalledWith(expect.objectContaining({ positions: { review: { x: 24, y: 0 } } }))
    fireEvent.keyDown(node(container), { key: 'Delete' }); expect(props.onRemove).toHaveBeenCalledWith('review')
    expect(JSON.stringify(props.graph)).toBe(before); expect(JSON.stringify(props.layout)).toBe(layout)
  })

  it('immediately retains measured selectable edges across selection and equivalent graph projections', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const wire = view.container.querySelector('.workflow-wire-hit')!
    expect(wire).toBeTruthy()
    for (const selected of ['review', 'later', undefined]) {
      const onEdge = vi.fn()
      view.rerender(<WorkflowCanvasView {...props} graph={structuredClone(props.graph)} layout={structuredClone(props.layout)}
        selected={selected} selectedEdge={selected ? undefined : 'edge'} onEdge={onEdge} onSelect={vi.fn()} />)
      // All assertions run in this synchronous turn after the new projection.
      // No observer callback, timer or wait may repair missing handle bounds.
      expect(view.container.querySelectorAll('.react-flow__edge')).toHaveLength(1)
      expect(view.container.querySelector('.workflow-wire-hit')).toBe(wire)
      expect(wire.getAttribute('aria-pressed')).toBe(String(!selected))
      expect(view.container.querySelectorAll('.workflow-node.selected')).toHaveLength(selected ? 1 : 0)
      if (selected) expect(view.container.querySelector('.workflow-node.selected')?.getAttribute('data-node-id')).toBe(selected)
      fireEvent.click(wire); expect(onEdge).toHaveBeenCalledExactlyOnceWith('edge')
      expect(view.container.querySelector('.workflow-wire-hit')).toBe(wire)
    }
    expect(props.graph.nodes.every(node => !('measured' in node))).toBe(true)
    expect(props.onLayout).not.toHaveBeenCalled()
  })

  it('retains the two-click connection action and connects directly to a target without selecting it first', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    expect(view.container.querySelector('.workflow-port')).toBeNull()
    view.rerender(<WorkflowCanvasView {...props} selected="review" />)
    fireEvent.click(screen.getByRole('button', { name: '从人工验收连接后续节点' }))
    expect(props.onConnect).toHaveBeenCalledWith('review')
    view.rerender(<WorkflowCanvasView {...props} selected="review" connecting="review" />)
    fireEvent.click(node(view.container, 'later'))
    expect(props.onConnect).toHaveBeenLastCalledWith('later'); expect(props.onSelect).not.toHaveBeenCalled()
    expect(props.graph.edges).toHaveLength(1); expect(props.onCancel).not.toHaveBeenCalled()
  })

  it.each(['forward', 'reverse', 'keyboard'] as const)('preserves Handle %s connections through a single pair callback', async direction => {
    const props = fixture({ selected: 'review' }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const source = node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')!
    const target = node(view.container, 'later').querySelector<HTMLElement>('.react-flow__handle.target')!
    if (direction === 'keyboard') { fireEvent.keyDown(source, { key: 'Enter' }); fireEvent.keyDown(target, { key: ' ' }) }
    else {
      const first = direction === 'reverse' ? target : source, second = direction === 'reverse' ? source : target
      for (const port of [first, second]) {
        const restore = hitTarget(port)
        try {
          pointer(port, 'pointerdown', 100, 100)
          expect(canvas(view.container).dataset.pointerGesture).toBe('connect'); expect(input.captures.get(1)).toBe(port)
          pointer(port, 'pointerup', 100, 100)
          expect(canvas(view.container).dataset.pointerGesture).toBeUndefined(); expect(input.captures.size).toBe(0)
          fireEvent.click(port)
        } finally { restore() }
      }
    }
    expect(props.onConnectPair).toHaveBeenCalledExactlyOnceWith('review', 'later')
    expect(props.onConnect).not.toHaveBeenCalled(); expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onLayout).not.toHaveBeenCalled()
    expect(props.graph.edges).toHaveLength(1)
  })

  it.each(['node', 'connection', 'pan', 'blur'] as const)('clears an old Handle tap when a new %s intent takes ownership', async intent => {
    const props = fixture({ selected: 'review' }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const root = canvas(view.container), article = node(view.container)
    const source = article.querySelector<HTMLElement>('.react-flow__handle.source')!
    const target = node(view.container, 'later').querySelector<HTMLElement>('.react-flow__handle.target')!
    const restoreInitial = hitTarget(source)
    pointer(source, 'pointerdown', 100, 100); pointer(source, 'pointerup', 100, 100); fireEvent.click(source)
    restoreInitial()
    expect(root.classList.contains('workflow-connection-dragging')).toBe(true)
    expect(props.onConnectPair).not.toHaveBeenCalled()
    if (intent === 'blur') fireEvent(window, new Event('blur'))
    else {
      const dragSource = intent === 'node' ? article : intent === 'pan' ? view.container.querySelector<HTMLElement>('.react-flow__pane')! : source, restore = hitTarget(target)
      try {
        pointer(dragSource, 'pointerdown', 100, 100); pointer(dragSource, 'pointermove', 205, 120)
        expect(root.dataset.pointerGesture).toBe(intent === 'node' ? 'drag' : intent === 'pan' ? 'pan' : 'connect')
        pointer(dragSource, 'pointerup', 205, 120)
        fireEvent.click(dragSource) // the browser click following a drag is consumed
      } finally { restore() }
    }
    const staleTap = root.classList.contains('workflow-connection-dragging')
    const before = vi.mocked(props.onConnectPair!).mock.calls.length
    expect(before).toBe(intent === 'connection' ? 1 : 0)
    fireEvent.click(target)
    expect(props.onConnectPair).toHaveBeenCalledTimes(before)
    expect(staleTap).toBe(false)
  })

  it('separates readonly movement from graph edits and keeps viewport controls available', async () => {
    const props = fixture({ readonly: true }), view = render(<WorkflowCanvasView {...props} />)
    fireEvent.keyDown(node(view.container), { key: 'ArrowRight' }); fireEvent.keyDown(node(view.container), { key: 'Delete' })
    expect(props.onLayout).not.toHaveBeenCalled(); expect(props.onRemove).not.toHaveBeenCalled()
    expect(view.container.querySelectorAll('.react-flow__handle.connectable')).toHaveLength(0)
    expect(view.container.querySelectorAll('.react-flow__node.draggable')).toHaveLength(0)
    fireEvent.click(screen.getByRole('button', { name: '放大画布' }))
    expect(props.onLayout).toHaveBeenLastCalledWith(expect.objectContaining({ zoom: 1.2 }))
    view.rerender(<WorkflowCanvasView {...props} movable />)
    fireEvent.keyDown(node(view.container), { key: 'ArrowRight' }); fireEvent.keyDown(node(view.container), { key: 'Backspace' })
    expect(props.onLayout).toHaveBeenLastCalledWith(expect.objectContaining({ positions: { review: { x: 24, y: 0 } } }))
    expect(props.onRemove).not.toHaveBeenCalled()
    expect(view.container.querySelectorAll('.react-flow__node.draggable')).toHaveLength(0)
    pointer(node(view.container), 'pointerdown', 100, 100)
    expect(canvas(view.container).dataset.pointerGesture).toBe('drag')
    pointer(node(view.container), 'pointermove', 124, 100); pointer(node(view.container), 'pointerup', 124, 100)
    expect(props.onLayout).toHaveBeenLastCalledWith(expect.objectContaining({ positions: { review: { x: 44, y: 0 } } }))
    const source = node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')!
    pointer(source, 'pointerdown', 100, 100)
    expect(canvas(view.container).dataset.pointerGesture).toBeUndefined(); expect(props.onConnectPair).not.toHaveBeenCalled()
  })

  it('locates and focuses nodes without saving and accepts a subsequent saved viewport', async () => {
    let handle: WorkflowCanvasHandle | undefined
    const props = fixture({ onReady: value => { handle = value } })
    props.layout.positions.later = { x: 6000, y: 7000 }
    const view = render(<WorkflowCanvasView {...props} />)
    const initial = view.container.querySelector('.react-flow__viewport')!.getAttribute('style')
    act(() => { handle!.reveal('later'); handle!.focus('later') })
    expect(document.activeElement).toBe(node(view.container, 'later'))
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).not.toBe(initial)
    expect(props.onLayout).not.toHaveBeenCalled()
    const revealed = view.container.querySelector('.react-flow__viewport')!.getAttribute('style')
    view.rerender(<WorkflowCanvasView {...props} layout={structuredClone(props.layout)} selected="later" />)
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toBe(revealed)
    view.rerender(<WorkflowCanvasView {...props} layout={{ ...props.layout, x: 10, y: 20, zoom: .5 }} />)
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(10px,20px) scale(0.5)')
    view.unmount(); expect(handle).toBeUndefined()
  })

  it.each([false, true])('preserves controlled Ctrl+wheel zoom while ordinary wheel is inactive (readonly=%s)', async readonly => {
    const props = fixture({ readonly }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!
    fireEvent.wheel(pane, { deltaY: -120, ctrlKey: false, clientX: 200, clientY: 200 })
    expect(props.onLayout).not.toHaveBeenCalled()
    const wheel = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: -120, ctrlKey: true, clientX: 200, clientY: 200 })
    fireEvent(pane, wheel); expect(wheel.defaultPrevented).toBe(true)
    expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ zoom: 1.1, positions: {} }))
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('scale(1.1)')
    expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onConnectPair).not.toHaveBeenCalled(); expect(props.onCancel).not.toHaveBeenCalled()
    expect(props.layout).toMatchObject({ x: 32, y: 36, zoom: 1, positions: {} })
  })

  it('cancels on blank clicks and Escape while controls, selection and a pan retain context', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    fireEvent.click(node(view.container)); fireEvent.click(screen.getByRole('button', { name: '放大画布' }))
    expect(props.onCancel).not.toHaveBeenCalled()
    const canvas = screen.getByRole('region', { name: '流程画布' })
    const pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!
    pointer(pane, 'pointerdown', 20, 20); expect(canvas.dataset.pointerGesture).toBe('pan')
    pointer(pane, 'pointermove', 60, 70); pointer(pane, 'pointerup', 60, 70)
    fireEvent.click(canvas); expect(props.onCancel).not.toHaveBeenCalled()
    pointer(pane, 'pointerdown', 20, 20); pointer(pane, 'pointerup', 20, 20); fireEvent.click(canvas)
    fireEvent.keyDown(node(view.container), { key: 'Escape' }); expect(props.onCancel).toHaveBeenCalledTimes(2)
  })

  it('uses captured Pointer dragging to persist a single node move in canvas coordinates', async () => {
    const props = fixture(); props.layout.zoom = 2
    const view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const article = node(view.container), browserWindow = article.ownerDocument.defaultView!
    pointer(article, 'pointerdown', 100, 100, browserWindow)
    pointer(browserWindow, 'pointermove', 110, 110, browserWindow)
    pointer(browserWindow, 'pointermove', 190, 170, browserWindow)
    pointer(browserWindow, 'pointerup', 190, 170, browserWindow)
    await waitFor(() => expect(props.onLayout).toHaveBeenCalledTimes(1))
    expect(props.onLayout).toHaveBeenLastCalledWith(expect.objectContaining({ positions: { review: { x: 45, y: 35 } }, zoom: 2 }))
    expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('review'); expect(props.onCancel).not.toHaveBeenCalled()
    expect(props.layout.positions).toEqual({})
  })

  it('creates Pointer drag connections and lets Escape cancel before pointer release', async () => {
    const props = fixture({ selected: 'review' }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const source = node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')!
    const target = node(view.container, 'later').querySelector<HTMLElement>('.react-flow__handle.target')!
    const browserWindow = source.ownerDocument.defaultView!, original = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => target })
    try {
      pointer(source, 'pointerdown', 150, 150, browserWindow); pointer(document, 'pointermove', 200, 250, browserWindow)
      expect(view.container.querySelector('.workflow-connection-dragging')).toBeTruthy()
      fireEvent.keyDown(source, { key: 'Escape' }); pointer(document, 'pointerup', 200, 250, browserWindow)
      expect(props.onConnectPair).not.toHaveBeenCalled(); expect(props.onCancel).toHaveBeenCalledTimes(1)
      expect(view.container.querySelector('.workflow-connection-dragging')).toBeNull()
      pointer(source, 'pointerdown', 150, 150, browserWindow); pointer(document, 'pointermove', 200, 250, browserWindow)
      pointer(document, 'pointerup', 200, 250, browserWindow)
      expect(props.onConnectPair).toHaveBeenCalledExactlyOnceWith('review', 'later')
      expect(props.onLayout).not.toHaveBeenCalled(); expect(props.graph.edges).toHaveLength(1)
    } finally { if (original) Object.defineProperty(document, 'elementFromPoint', original); else Reflect.deleteProperty(document, 'elementFromPoint') }
  })

  it('persists a captured Pointer viewport pan once without dismissing the selected context', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!, browserWindow = pane.ownerDocument.defaultView!
    pointer(pane, 'pointerdown', 200, 200, browserWindow); pointer(browserWindow, 'pointermove', 260, 250, browserWindow)
    pointer(browserWindow, 'pointerup', 260, 250, browserWindow)
    await waitFor(() => expect(props.onLayout).toHaveBeenCalledTimes(1))
    expect(props.onLayout).toHaveBeenLastCalledWith(expect.objectContaining({ x: 92, y: 86, zoom: 1 }))
    expect(props.onCancel).not.toHaveBeenCalled(); expect(props.layout).toMatchObject({ x: 32, y: 36, zoom: 1 })
  })

  it('owns middle-button pan from a node and saves the first 105px / 20px screen delta exactly once', async () => {
    const props = fixture(), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const root = canvas(view.container), article = node(view.container), resources = trackGestureResources([root])
    const down = input.pointer(article, 'pointerdown', 100, 100, { button: 1, buttons: 4 })
    expect(down.defaultPrevented).toBe(true); expect(root.dataset.pointerGesture).toBe('pan'); expect(input.captures.get(1)).toBe(root)
    expect(resources.listeners()).toHaveLength(5)
    input.pointer(article, 'pointermove', 205, 120, { button: -1, buttons: 4 })
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(137px,56px) scale(1)')
    input.pointer(article, 'pointerup', 205, 120, { button: 1, buttons: 0 })
    expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ x: 137, y: 56, zoom: 1, positions: {} }))
    expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
    fireEvent.click(article); input.pointer(article, 'pointerup', 205, 120, { button: 1, buttons: 0 })
    expect(props.onLayout).toHaveBeenCalledTimes(1); expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onConnectPair).not.toHaveBeenCalled()
    expect(article.parentElement!.style.transform).toBe('translate(0px,0px)')
  })

  it('keeps an authoritative viewport when the previous pan continues and finishes late', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!, browserWindow = pane.ownerDocument.defaultView!
    pointer(pane, 'pointerdown', 200, 200, browserWindow)
    pointer(browserWindow, 'pointermove', 260, 250, browserWindow)
    const accepted = { ...props.layout, x: 400, y: 500, zoom: .5 }
    view.rerender(<WorkflowCanvasView {...props} layout={accepted} />)
    pointer(browserWindow, 'pointermove', 280, 270, browserWindow)
    pointer(browserWindow, 'pointerup', 280, 270, browserWindow)
    expect(props.onLayout).not.toHaveBeenCalled()
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(400px,500px) scale(0.5)')
    pointer(pane, 'pointerdown', 200, 200, browserWindow)
    pointer(browserWindow, 'pointermove', 220, 230, browserWindow)
    pointer(browserWindow, 'pointerup', 220, 230, browserWindow)
    await waitFor(() => expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ x: 420, y: 530, zoom: .5 })))
  })

  it('does not turn a programmatic reveal during a pan into a saved viewport edit', async () => {
    let handle: WorkflowCanvasHandle | undefined
    const props = fixture({ onReady: value => { handle = value } }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!, browserWindow = pane.ownerDocument.defaultView!
    pointer(pane, 'pointerdown', 200, 200, browserWindow)
    pointer(browserWindow, 'pointermove', 260, 250, browserWindow)
    act(() => handle!.reveal('later'))
    const revealed = view.container.querySelector('.react-flow__viewport')!.getAttribute('style')
    pointer(browserWindow, 'pointermove', 280, 270, browserWindow)
    pointer(browserWindow, 'pointerup', 280, 270, browserWindow)
    await act(async () => { await new Promise(done => setTimeout(done, 0)) })
    expect(props.onLayout).not.toHaveBeenCalled()
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toBe(revealed)
  })

  it.each(['node', 'connection'] as const)('does not allocate auto-pan frames for an active Pointer %s gesture before or after unmount', async gesture => {
    const props = fixture({ selected: 'review' }), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const resources = trackGestureResources([canvas(view.container)])
    const source = gesture === 'connection' ? node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')! : node(view.container)
    const restore = hitTarget(null)
    try {
      pointer(source, 'pointerdown', 100, 100); pointer(source, 'pointermove', 200, 250)
      expect(canvas(view.container).dataset.pointerGesture).toBe(gesture === 'node' ? 'drag' : 'connect')
      expect(input.captures.get(1)).toBe(source)
      if (gesture === 'connection') expect(view.container.querySelector('.workflow-connection-preview')).toBeTruthy()
      else expect(node(view.container).parentElement!.style.transform).toBe('translate(100px,150px)')
      expect(resources.frames.size).toBe(0)
      view.unmount()
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
    } finally { restore() }
  })

  it.each([false, true])('immediately releases Pointer connection listeners without further input (started=%s)', async started => {
    const props = fixture({ selected: 'review' }), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const root = canvas(view.container), source = node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')!
    const resources = trackGestureResources([root]), restore = hitTarget(null)
    try {
      pointer(source, 'pointerdown', 100, 100)
      const initialPath = view.container.querySelector('.workflow-connection-preview path')?.getAttribute('d')
      if (started) pointer(source, 'pointermove', 205, 120)
      expect(root.dataset.pointerGesture).toBe('connect'); expect(input.captures.get(1)).toBe(source)
      if (started) { expect(view.container.querySelector('.workflow-connection-preview path')).toBeTruthy(); expect(view.container.querySelector('.workflow-connection-preview path')!.getAttribute('d')).not.toBe(initialPath) }
      expect(resources.listeners()).toHaveLength(5)
      view.unmount()
      // No move/up, timer or repeated unmount precedes the strict snapshot.
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      view.unmount()
      expect(props.onConnectPair).not.toHaveBeenCalled(); expect(props.onCancel).not.toHaveBeenCalled(); expect(props.onLayout).not.toHaveBeenCalled()
    } finally { restore() }
  })

  it.each([.5, 1, 2])('preserves the first 105px / 20px pointer delta at zoom %s', async zoom => {
    const props = fixture({ layout: { ...fixture().layout, zoom } }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const browserWindow = node(view.container).ownerDocument.defaultView!
    pointer(node(view.container), 'pointerdown', 100, 100, browserWindow)
    pointer(browserWindow, 'pointermove', 205, 120, browserWindow)
    expect(node(view.container).parentElement!.style.transform).toBe(`translate(${105 / zoom}px,${20 / zoom}px)`)
    pointer(browserWindow, 'pointerup', 205, 120, browserWindow)
    expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ positions: { review: { x: 105 / zoom, y: 20 / zoom } }, zoom }))
    expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('review')
  })

  it.each(['node', 'viewport', 'connection'] as const)('disposes an active %s gesture across repeated StrictMode mounts', async gesture => {
    const original = Object.getOwnPropertyDescriptor(document, 'elementFromPoint'), below = vi.fn<() => HTMLElement | null>(() => null)
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: below })
    try {
    for (let cycle = 0; cycle < 3; cycle++) {
      const props = fixture({ selected: 'review' }), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
      await connected(view.container)
      vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
      const browserWindow = node(view.container).ownerDocument.defaultView!
      const source = gesture === 'viewport' ? view.container.querySelector<HTMLElement>('.react-flow__pane')!
        : gesture === 'connection' ? node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')! : node(view.container)
      below.mockReturnValue(gesture === 'connection' ? node(view.container, 'later').querySelector<HTMLElement>('.react-flow__handle.target') : null)
      pointer(source, 'pointerdown', 100, 100, browserWindow)
      pointer(gesture === 'connection' ? document : browserWindow, 'pointermove', 150, 120, browserWindow)
      expect(canvas(view.container).dataset.pointerGesture).toBe(gesture === 'viewport' ? 'pan' : gesture === 'connection' ? 'connect' : 'drag')
      vi.mocked(props.onSelect).mockClear()
      view.unmount()
      pointer(gesture === 'connection' ? document : browserWindow, 'pointermove', 205, 120, browserWindow)
      pointer(gesture === 'connection' ? document : browserWindow, 'pointerup', 205, 120, browserWindow)
      expect(props.onLayout).not.toHaveBeenCalled(); expect(props.onSelect).not.toHaveBeenCalled()
      expect(props.onConnectPair).not.toHaveBeenCalled(); expect(props.onCancel).not.toHaveBeenCalled()
    }
    } finally { if (original) Object.defineProperty(document, 'elementFromPoint', original); else Reflect.deleteProperty(document, 'elementFromPoint') }
  })

  it('rolls back an in-progress drag when movement is locked, preserving reveal and the next keyboard edit', async () => {
    let handle: WorkflowCanvasHandle | undefined
    const props = fixture({ onReady: value => { handle = value } }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const browserWindow = node(view.container).ownerDocument.defaultView!
    pointer(node(view.container), 'pointerdown', 100, 100, browserWindow)
    pointer(browserWindow, 'pointermove', 110, 110, browserWindow); pointer(browserWindow, 'pointermove', 190, 170, browserWindow)
    expect(node(view.container).parentElement!.style.transform).toBe('translate(90px,70px)')
    act(() => handle!.reveal('later'))
    const revealed = view.container.querySelector('.react-flow__viewport')!.getAttribute('style')
    view.rerender(<WorkflowCanvasView {...props} readonly />)
    pointer(browserWindow, 'pointerup', 190, 170, browserWindow)
    expect(props.onLayout).not.toHaveBeenCalled()
    expect(node(view.container).parentElement!.style.transform).toBe('translate(0px,0px)')
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toBe(revealed)
    view.rerender(<WorkflowCanvasView {...props} layout={structuredClone(props.layout)} />)
    fireEvent.keyDown(node(view.container), { key: 'ArrowRight' })
    expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ positions: { review: { x: 24, y: 0 } } }))
    expect(props.layout.positions).toEqual({})
  })

  it('accepts an authoritative layout during dragging without letting a late drag completion overwrite it', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const browserWindow = node(view.container).ownerDocument.defaultView!
    pointer(node(view.container), 'pointerdown', 100, 100, browserWindow)
    pointer(browserWindow, 'pointermove', 110, 110, browserWindow); pointer(browserWindow, 'pointermove', 190, 170, browserWindow)
    const accepted = { ...props.layout, positions: { review: { x: 300, y: 400 } } }
    view.rerender(<WorkflowCanvasView {...props} layout={accepted} />)
    pointer(browserWindow, 'pointerup', 190, 170, browserWindow)
    expect(props.onLayout).not.toHaveBeenCalled()
    expect(node(view.container).parentElement!.style.transform).toBe('translate(300px,400px)')
    fireEvent.keyDown(node(view.container), { key: 'ArrowRight' })
    expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ positions: { review: { x: 324, y: 400 } } }))
  })

  it('rolls back a one-pixel pane jitter and lets its following background click cancel once', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const root = canvas(view.container), pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!
    pointer(pane, 'pointerdown', 100, 100)
    expect(root.dataset.pointerGesture).toBe('pan'); expect(input.captures.get(1)).toBe(root)
    pointer(pane, 'pointermove', 101, 100); pointer(pane, 'pointerup', 101, 100)
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(32px,36px) scale(1)')
    expect(props.onLayout).not.toHaveBeenCalled(); expect(props.onCancel).not.toHaveBeenCalled()
    fireEvent.click(pane); expect(props.onCancel).toHaveBeenCalledTimes(1)
  })

  it('keeps pointer press and small jitter from selecting or saving a node', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const browserWindow = node(view.container).ownerDocument.defaultView!
    pointer(node(view.container), 'pointerdown', 100, 100, browserWindow)
    expect(canvas(view.container).dataset.pointerGesture).toBe('drag'); expect(input.captures.get(1)).toBe(node(view.container))
    expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onLayout).not.toHaveBeenCalled()
    pointer(browserWindow, 'pointermove', 101, 100, browserWindow); pointer(browserWindow, 'pointerup', 101, 100, browserWindow)
    expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onLayout).not.toHaveBeenCalled()
    expect(node(view.container).parentElement!.style.transform).toBe('translate(0px,0px)')
    fireEvent.click(node(view.container)); expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('review')
  })
})
