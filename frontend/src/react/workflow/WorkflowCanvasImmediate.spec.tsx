import { cleanup, fireEvent, render } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { WorkflowCanvasView } from './WorkflowCanvasReact'
import { canvas, connected, fixture, hitTarget, installPointerEnvironment, node, trackGestureResources, type PointerEnvironment } from './workflowPointerTestHelpers'

let input: PointerEnvironment
beforeEach(() => { input = installPointerEnvironment() })
afterEach(() => { cleanup(); vi.restoreAllMocks(); input.restore() })
type Gesture = 'connection-pending' | 'connection-started' | 'node-drag' | 'viewport-pan'
const scenarios: Gesture[] = ['connection-pending', 'connection-started', 'node-drag', 'viewport-pan']

function start(container: HTMLElement, gesture: Gesture, pointerId = 1, pointerType = 'mouse') {
  const root = canvas(container), article = node(container)
  const source = gesture === 'node-drag' ? article : gesture === 'viewport-pan'
    ? container.querySelector<HTMLElement>('.react-flow__pane')! : article.querySelector<HTMLElement>('.react-flow__handle.source')!
  const options = { pointerId, pointerType, isPrimary: pointerId === 1 }
  input.pointer(source, 'pointerdown', 100, 100, options)
  expect(root.dataset.pointerGesture).toBe(gesture === 'node-drag' ? 'drag' : gesture === 'viewport-pan' ? 'pan' : 'connect')
  expect(input.captures.get(pointerId)).toBe(gesture === 'viewport-pan' ? root : source)
  const initialPath = container.querySelector('.workflow-connection-preview path')?.getAttribute('d')
  if (gesture !== 'connection-pending') input.pointer(source, 'pointermove', 205, 120, options)
  // These visible changes prove this is a live Pointer path, rather than an
  // ignored MouseEvent that would make a zero-resource assertion vacuous.
  if (gesture === 'node-drag') expect(article.parentElement!.style.transform).toBe('translate(105px,20px)')
  else if (gesture === 'viewport-pan') expect(container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(137px,56px) scale(1)')
  else if (gesture === 'connection-started') {
    expect(container.querySelector('.workflow-connection-preview path')).toBeTruthy()
    expect(container.querySelector('.workflow-connection-preview path')!.getAttribute('d')).not.toBe(initialPath)
  }
  return source
}

function noWrites(props: ReturnType<typeof fixture>) {
  expect(props.onLayout).not.toHaveBeenCalled(); expect(props.onConnectPair).not.toHaveBeenCalled()
  expect(props.onRemove).not.toHaveBeenCalled(); expect(props.onEdge).not.toHaveBeenCalled()
}

describe('strict immediate cleanup of actual Pointer workflow gestures', () => {
  it('renders the connection preview in its own root with the exact viewport transform, without a second portal host', async () => {
    const props = fixture({ selected: 'review', layout: { x: 72, y: -31, zoom: 1.75, positions: {} } })
    const view = render(<WorkflowCanvasView {...props} />); await connected(view.container)
    const source = start(view.container, 'connection-started')
    const preview = view.container.querySelector('.workflow-connection-preview')!
    expect(preview.parentElement).toBe(canvas(view.container))
    expect(preview.closest('.react-flow__viewport')).toBeNull()
    expect(preview.querySelector('g')?.getAttribute('transform')).toBe('translate(72,-31) scale(1.75)')
    expect(preview.querySelector('path')?.getAttribute('d')).toBeTruthy()
    input.pointer(source, 'pointercancel', 205, 120)
    expect(view.container.querySelector('.workflow-connection-preview')).toBeNull(); noWrites(props)
  })
  it.each(scenarios.flatMap(gesture => ['mouse', 'touch'].map(pointerType => ({ gesture, pointerType }))))(
    'immediately owns zero listeners and RAF after $pointerType $gesture root unmount', async ({ gesture, pointerType }) => {
      const props = fixture({ selected: 'review' }), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
      await connected(view.container)
      const root = canvas(view.container), resources = trackGestureResources([root]), restore = hitTarget(null)
      try {
        const source = start(view.container, gesture, 1, pointerType)
        const before = resources.listeners()
        expect(before).toHaveLength(5)
        expect(before.filter(entry => entry.target === 'root-0').map(entry => entry.type).sort()).toEqual(['lostpointercapture', 'pointercancel', 'pointermove', 'pointerup'])
        expect(before.filter(entry => entry.target === 'window').map(entry => entry.type)).toEqual(['blur'])
        vi.mocked(props.onSelect).mockClear()
        view.unmount()
        // This is the first operation after unmount. No release, cancel,
        // timer, repeat unmount, or later input may repair a failing result.
        const after = resources.listeners()
        console.info(JSON.stringify({ gesture, pointerType, before, after, pendingFrames: resources.frames.size, captures: input.captures.size }))
        expect(after).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
        noWrites(props); expect(props.onCancel).not.toHaveBeenCalled()
        view.unmount()
        input.pointer(source, 'pointermove', 250, 160, { pointerType }); input.pointer(source, 'pointerup', 250, 160, { pointerType })
        input.pointer(document, 'pointercancel', 250, 160, { pointerType })
        noWrites(props); expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onCancel).not.toHaveBeenCalled()
      } finally { restore(); resources.restore() }
    },
  )

  it.each(scenarios)('cleans %s in three StrictMode mount/unmount cycles without late writes', async gesture => {
    const restore = hitTarget(null)
    try {
      for (let cycle = 0; cycle < 3; cycle++) {
        const props = fixture({ selected: 'review' }), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
        await connected(view.container)
        const resources = trackGestureResources([canvas(view.container)])
        const source = start(view.container, gesture)
        expect(resources.listeners()).toHaveLength(5)
        vi.mocked(props.onSelect).mockClear()
        view.unmount()
        expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
        input.pointer(source, 'pointermove', 250, 180); input.pointer(source, 'pointerup', 250, 180)
        noWrites(props); expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onCancel).not.toHaveBeenCalled()
        resources.restore()
      }
    } finally { restore() }
  })

  it.each(['node-drag', 'connection-started', 'viewport-pan'] as const)('unmounts one %s island while another gesture keeps its own listeners and capture', async gesture => {
    const first = fixture({ selected: 'review' }), second = fixture({ selected: 'review' })
    const one = render(<StrictMode><WorkflowCanvasView {...first} /></StrictMode>), two = render(<StrictMode><WorkflowCanvasView {...second} /></StrictMode>)
    await connected(one.container); await connected(two.container)
    const sentinel = vi.fn(), restore = hitTarget(null)
    window.addEventListener('pointermove', sentinel)
    const resources = trackGestureResources([canvas(one.container), canvas(two.container)])
    try {
      const sourceOne = start(one.container, gesture, 1, 'touch')
      const ownedOne = resources.listeners()
      const sourceTwo = start(two.container, gesture, 2, 'touch')
      const ownedTwo = resources.listeners().filter(entry => !ownedOne.some(firstEntry => JSON.stringify(firstEntry) === JSON.stringify(entry)))
      expect(ownedOne).toHaveLength(5); expect(ownedTwo).toHaveLength(5)
      vi.mocked(first.onSelect).mockClear(); vi.mocked(second.onSelect).mockClear()
      one.unmount()
      expect(resources.listeners()).toEqual(ownedTwo); expect(resources.frames.size).toBe(0)
      expect(input.captures.has(1)).toBe(false); expect(input.captures.has(2)).toBe(true)
      expect(canvas(two.container).dataset.pointerGesture).toBe(gesture === 'node-drag' ? 'drag' : gesture === 'viewport-pan' ? 'pan' : 'connect')
      input.pointer(sourceOne, 'pointermove', 240, 140, { pointerId: 1, pointerType: 'touch' }); input.pointer(sourceOne, 'pointerup', 240, 140, { pointerId: 1, pointerType: 'touch' })
      noWrites(first); noWrites(second); expect(first.onSelect).not.toHaveBeenCalled(); expect(second.onSelect).not.toHaveBeenCalled()
      // Normal release of the surviving island commits exactly its own edit.
      input.pointer(sourceTwo, 'pointermove', 240, 140, { pointerId: 2, pointerType: 'touch' })
      if (gesture === 'connection-started') {
        restore()
        const restoreTarget = hitTarget(node(two.container, 'later').querySelector('.react-flow__handle.target'))
        input.pointer(sourceTwo, 'pointerup', 240, 140, { pointerId: 2, pointerType: 'touch' })
        restoreTarget()
        expect(second.onConnectPair).toHaveBeenCalledExactlyOnceWith('review', 'later'); expect(second.onLayout).not.toHaveBeenCalled()
      } else {
        input.pointer(sourceTwo, 'pointerup', 240, 140, { pointerId: 2, pointerType: 'touch' })
        expect(second.onLayout).toHaveBeenCalledTimes(1); expect(second.onConnectPair).not.toHaveBeenCalled()
      }
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      two.unmount(); expect(resources.listeners()).toEqual([])
      const calls = sentinel.mock.calls.length
      input.pointer(window, 'pointermove', 0, 0, { pointerId: 3 })
      expect(sentinel).toHaveBeenCalledTimes(calls + 1)
    } finally { restore(); resources.restore(); window.removeEventListener('pointermove', sentinel) }
  })

  it.each(['node-drag', 'connection-started', 'viewport-pan'] as const)('cleans %s when the nested island leaves while its React parent stays mounted', async gesture => {
    const props = fixture({ selected: 'review' })
    const page = (visible: boolean) => <StrictMode><main data-testid="page">{visible && <WorkflowCanvasView {...props} />}</main></StrictMode>
    const view = render(page(true)); await connected(view.container)
    const resources = trackGestureResources([canvas(view.container)]), restore = hitTarget(null)
    try {
      const source = start(view.container, gesture)
      expect(resources.listeners()).toHaveLength(5)
      vi.mocked(props.onSelect).mockClear()
      view.rerender(page(false))
      expect(view.container.querySelector('[data-testid="page"]')).toBeTruthy()
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      input.pointer(source, 'pointerup', 240, 140)
      noWrites(props); expect(props.onSelect).not.toHaveBeenCalled()
    } finally { restore(); resources.restore() }
  })

  it.each(['node-drag', 'connection-started', 'viewport-pan'] as const)('immediately cancels %s on pointercancel, lost capture and blur without committing', async gesture => {
    const restore = hitTarget(null)
    try {
      for (const interruption of ['pointercancel', 'lostpointercapture', 'blur'] as const) {
        const props = fixture({ selected: 'review' }), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
        await connected(view.container)
        const root = canvas(view.container), resources = trackGestureResources([root])
        const source = start(view.container, gesture, 1, interruption === 'pointercancel' ? 'touch' : 'mouse')
        expect(resources.listeners()).toHaveLength(5)
        vi.mocked(props.onSelect).mockClear()
        if (interruption === 'blur') fireEvent(window, new Event('blur'))
        else input.pointer(source, interruption, 205, 120, { pointerType: interruption === 'pointercancel' ? 'touch' : 'mouse' })
        expect(root.dataset.pointerGesture).toBeUndefined()
        expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
        expect(node(view.container).parentElement!.style.transform).toBe('translate(0px,0px)')
        expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(32px,36px) scale(1)')
        expect(view.container.querySelector('.workflow-connection-preview')).toBeNull()
        input.pointer(source, 'pointerup', 250, 180); input.pointer(source, 'pointerup', 250, 180)
        noWrites(props); expect(props.onSelect).not.toHaveBeenCalled()
        view.unmount(); expect(resources.listeners()).toEqual([]); resources.restore()
      }
    } finally { restore() }
  })

  it.each(['node-drag', 'connection-started', 'viewport-pan'] as const)('invalidates %s immediately when an authoritative layout arrives', async gesture => {
    const props = fixture({ selected: 'review' }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const root = canvas(view.container), resources = trackGestureResources([root]), restore = hitTarget(null)
    try {
      const source = start(view.container, gesture)
      expect(resources.listeners()).toHaveLength(5)
      const accepted = { ...props.layout, x: 400, y: 500, zoom: .5, positions: { review: { x: 300, y: 400 } } }
      view.rerender(<WorkflowCanvasView {...props} layout={accepted} />)
      expect(root.dataset.pointerGesture).toBeUndefined()
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      input.pointer(source, 'pointermove', 260, 190); input.pointer(source, 'pointerup', 260, 190)
      expect(node(view.container).parentElement!.style.transform).toBe('translate(300px,400px)')
      expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(400px,500px) scale(0.5)')
      noWrites(props)
    } finally { restore(); resources.restore() }
  })

  it.each(['node-drag', 'connection-started'] as const)('immediately cancels %s when edits become locked', async gesture => {
    const props = fixture({ selected: 'review' }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const root = canvas(view.container), resources = trackGestureResources([root]), restore = hitTarget(null)
    try {
      const source = start(view.container, gesture)
      expect(resources.listeners()).toHaveLength(5)
      view.rerender(<WorkflowCanvasView {...props} readonly />)
      expect(root.dataset.pointerGesture).toBeUndefined()
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      input.pointer(source, 'pointerup', 260, 190)
      expect(node(view.container).parentElement!.style.transform).toBe('translate(0px,0px)')
      noWrites(props)
    } finally { restore(); resources.restore() }
  })

  it('does not start an unowned drag when pointer capture is unavailable, and preserves the following click', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const root = canvas(view.container), article = node(view.container), resources = trackGestureResources([root])
    vi.spyOn(article, 'setPointerCapture').mockImplementation(() => { throw new DOMException('unsupported', 'NotSupportedError') })
    input.pointer(article, 'pointerdown', 100, 100)
    expect(root.dataset.pointerGesture).toBeUndefined(); expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0)
    input.pointer(article, 'pointermove', 205, 120); input.pointer(article, 'pointerup', 205, 120)
    noWrites(props)
    fireEvent.click(article); expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('review')
  })

  it('immediately removes the waiting Handle tap blur listener on unmount without later input', async () => {
    const props = fixture({ selected: 'review' }), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const root = canvas(view.container), source = node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')!
    const target = node(view.container, 'later').querySelector<HTMLElement>('.react-flow__handle.target')!
    const resources = trackGestureResources([root]), restore = hitTarget(source)
    try {
      input.pointer(source, 'pointerdown', 100, 100)
      expect(root.dataset.pointerGesture).toBe('connect'); expect(resources.listeners()).toHaveLength(5)
      input.pointer(source, 'pointerup', 100, 100); fireEvent.click(source)
      expect(root.dataset.pointerGesture).toBeUndefined(); expect(root.classList.contains('workflow-connection-dragging')).toBe(true)
      expect(resources.listeners().map(entry => ({ target: entry.target, type: entry.type }))).toEqual([{ target: 'window', type: 'blur' }])
      view.unmount()
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      fireEvent.click(target); fireEvent(window, new Event('blur'))
      noWrites(props); expect(props.onCancel).not.toHaveBeenCalled()
    } finally { restore(); resources.restore() }
  })

  it('disposes the live drag when its threshold selection callback synchronously unmounts the canvas', async () => {
    let view: ReturnType<typeof render>
    const props = fixture({ onSelect: vi.fn(() => view.unmount()) })
    view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const root = canvas(view.container), article = node(view.container), resources = trackGestureResources([root])
    input.pointer(article, 'pointerdown', 100, 100)
    expect(root.dataset.pointerGesture).toBe('drag'); expect(input.captures.get(1)).toBe(article); expect(resources.listeners()).toHaveLength(5)
    input.pointer(article, 'pointermove', 205, 120)
    expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('review')
    expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
    noWrites(props)
    input.pointer(article, 'pointerup', 205, 120)
    noWrites(props); expect(props.onSelect).toHaveBeenCalledTimes(1)
  })

  it.each(['hasPointerCapture', 'releasePointerCapture'] as const)('removes every listener and remaining capture when %s throws for a detached touch', async failingApi => {
    const props = fixture(), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const root = canvas(view.container), pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!
    const resources = trackGestureResources([root])
    input.pointer(pane, 'pointerdown', 100, 100, { pointerId: 1, pointerType: 'touch' })
    input.pointer(pane, 'pointerdown', 100, 200, { pointerId: 2, pointerType: 'touch', isPrimary: false })
    input.pointer(root, 'pointermove', 100, 300, { pointerId: 2, pointerType: 'touch', isPrimary: false })
    expect(root.dataset.pointerGesture).toBe('pan'); expect(input.captures.size).toBe(2); expect(resources.listeners()).toHaveLength(5)
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('scale(2)')
    const originalHas = root.hasPointerCapture.bind(root), originalRelease = root.releasePointerCapture.bind(root)
    const has = vi.spyOn(root, 'hasPointerCapture').mockImplementation(id => {
      if (id === 1 && failingApi === 'hasPointerCapture') {
        // Native capture can already have disappeared when a detached target
        // is queried. Simulate that race, rather than invent a live capture
        // that the browser refuses to release permanently.
        input.captures.delete(id); throw new DOMException('detached', 'NotFoundError')
      }
      return originalHas(id)
    })
    const release = vi.spyOn(root, 'releasePointerCapture').mockImplementation(id => {
      if (id === 1 && failingApi === 'releasePointerCapture') {
        input.captures.delete(id); throw new DOMException('detached', 'NotFoundError')
      }
      originalRelease(id)
    })
    view.unmount()
    expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
    expect(has.mock.calls.map(([id]) => id)).toEqual([1, 2])
    expect(release.mock.calls.map(([id]) => id)).toEqual(failingApi === 'releasePointerCapture' ? [1, 2] : [2])
    noWrites(props)
  })

  it.each(['release', 'cancel', 'unmount'] as const)('owns a real two-touch pinch and cleans both captures on %s', async ending => {
    const props = fixture(), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const root = canvas(view.container), pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!
    const resources = trackGestureResources([root])
    input.pointer(pane, 'pointerdown', 100, 100, { pointerId: 1, pointerType: 'touch' })
    input.pointer(pane, 'pointerdown', 100, 200, { pointerId: 2, pointerType: 'touch', isPrimary: false })
    expect(root.dataset.pointerGesture).toBe('pan'); expect(input.captures.get(1)).toBe(root); expect(input.captures.get(2)).toBe(root)
    expect(resources.listeners()).toHaveLength(5)
    input.pointer(root, 'pointermove', 100, 300, { pointerId: 2, pointerType: 'touch', isPrimary: false })
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(-36px,-28px) scale(2)')
    noWrites(props)
    // An unrelated touch cancellation does not own either of this session's
    // pointers and must leave their listeners and captures intact.
    input.pointer(root, 'pointercancel', 0, 0, { pointerId: 99, pointerType: 'touch', isPrimary: false })
    expect(root.dataset.pointerGesture).toBe('pan'); expect(resources.listeners()).toHaveLength(5); expect(input.captures.size).toBe(2)
    if (ending === 'unmount') {
      view.unmount()
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      noWrites(props)
    } else if (ending === 'cancel') {
      input.pointer(root, 'pointercancel', 100, 300, { pointerId: 2, pointerType: 'touch', isPrimary: false })
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(32px,36px) scale(1)')
      noWrites(props)
    } else {
      input.pointer(root, 'pointerup', 100, 100, { pointerId: 1, pointerType: 'touch' })
      expect(resources.listeners()).toHaveLength(5); expect(input.captures.has(1)).toBe(false); expect(input.captures.get(2)).toBe(root)
      noWrites(props)
      input.pointer(root, 'pointermove', 100, 320, { pointerId: 2, pointerType: 'touch', isPrimary: false })
      input.pointer(root, 'pointerup', 100, 320, { pointerId: 2, pointerType: 'touch', isPrimary: false })
      expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ x: -36, y: -8, zoom: 2 }))
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
      input.pointer(root, 'pointerup', 100, 320, { pointerId: 2, pointerType: 'touch', isPrimary: false })
      expect(props.onLayout).toHaveBeenCalledTimes(1)
    }
    expect(props.onConnectPair).not.toHaveBeenCalled(); expect(props.layout).toMatchObject({ x: 32, y: 36, zoom: 1, positions: {} })
  })

  it('leaves no native middle-button pan listeners after a Pointer press and its browser compatibility mousedown', async () => {
    const props = fixture(), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const root = canvas(view.container), article = node(view.container), resources = trackGestureResources([root])
    const press = input.pointer(article, 'pointerdown', 100, 100, { button: 1, buttons: 4 })
    // A real browser emits compatibility mousedown unless pointerdown was
    // cancelled. Deliver that native event only under the same condition.
    if (!press.defaultPrevented) {
      const event = new MouseEvent('mousedown', { bubbles: true, cancelable: true, button: 1, buttons: 4, clientX: 100, clientY: 100 })
      Object.defineProperty(event, 'view', { value: article.ownerDocument.defaultView })
      fireEvent(article, event)
    }
    expect(root.dataset.pointerGesture).toBe('pan'); expect(input.captures.get(1)).toBe(root)
    input.pointer(article, 'pointermove', 205, 120, { button: -1, buttons: 4 })
    expect(view.container.querySelector('.react-flow__viewport')!.getAttribute('style')).toContain('translate(137px,56px) scale(1)')
    const before = resources.listeners()
    expect(before).toHaveLength(5)
    // The island owns this middle pan, including its first screen delta;
    // native d3 bindings must never remain after synchronous disposal.
    view.unmount()
    const after = resources.listeners()
    console.info(JSON.stringify({ gesture: 'middle-button-compatibility', pointerCancelled: press.defaultPrevented, before, after, pendingFrames: resources.frames.size }))
    expect(after).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
    noWrites(props)
  })

  it.each(['node-drag', 'viewport-pan'] as const)('Ctrl+wheel cancels %s before saving one zoom and rejects late drag completion', async gesture => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const root = canvas(view.container), resources = trackGestureResources([root])
    const source = start(view.container, gesture)
    expect(resources.listeners()).toHaveLength(5)
    const wheel = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: -120, ctrlKey: true })
    fireEvent(view.container.querySelector('.react-flow__pane')!, wheel)
    expect(wheel.defaultPrevented).toBe(true); expect(root.dataset.pointerGesture).toBeUndefined()
    expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0); expect(input.captures.size).toBe(0)
    expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ zoom: 1.1, positions: {} }))
    expect(node(view.container).parentElement!.style.transform).toBe('translate(0px,0px)')
    input.pointer(source, 'pointermove', 260, 160); input.pointer(source, 'pointerup', 260, 160)
    expect(props.onLayout).toHaveBeenCalledTimes(1); expect(props.onConnectPair).not.toHaveBeenCalled()
    expect(props.layout).toMatchObject({ x: 32, y: 36, zoom: 1, positions: {} })
  })

  it('removes the active native non-passive wheel callback by its exact identity on StrictMode unmount', async () => {
    const add = vi.spyOn(Element.prototype, 'addEventListener'), remove = vi.spyOn(Element.prototype, 'removeEventListener')
    const props = fixture(), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const root = canvas(view.container)
    const calls = add.mock.calls.flatMap((call, index) => add.mock.contexts[index] === root && call[0] === 'wheel' ? [call] : [])
    const current = calls.at(-1)!
    expect(current).toBeDefined(); expect(current[2]).toEqual({ passive: false })
    const removals = () => remove.mock.calls.filter((call, index) => remove.mock.contexts[index] === root && call[0] === 'wheel' && call[1] === current[1])
    expect(removals()).toEqual([])
    const wheel = new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: -120, ctrlKey: true })
    fireEvent(root, wheel); expect(wheel.defaultPrevented).toBe(true); expect(props.onLayout).toHaveBeenCalledTimes(1)
    vi.mocked(props.onLayout).mockClear()
    view.unmount()
    expect(removals()).toHaveLength(1); expect(removals()[0]![2]).toBeUndefined()
    fireEvent(root, new WheelEvent('wheel', { bubbles: true, cancelable: true, deltaY: -120, ctrlKey: true }))
    expect(props.onLayout).not.toHaveBeenCalled()
    view.unmount(); expect(removals()).toHaveLength(1)
  })
})
