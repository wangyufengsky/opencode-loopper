import { act, cleanup, fireEvent, render } from '@testing-library/react'
import { StrictMode } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { pptDeck } from '@/components/ppt/pptTestFixtures'
import { installPointerEnvironment, trackGestureResources, type PointerEnvironment } from '@/react/workflow/workflowPointerTestHelpers'
import { PptCanvasView, type PptCanvasViewProps } from './PptCanvasView'

let input: PointerEnvironment
const observers: { active: Set<Element>; disconnects: number; notify: ResizeObserverCallback }[] = []
beforeEach(() => {
  input = installPointerEnvironment()
  observers.length = 0
  vi.stubGlobal('ResizeObserver', class {
    state: typeof observers[number]
    constructor(notify: ResizeObserverCallback) {
      this.state = { active: new Set(), disconnects: 0, notify }; observers.push(this.state)
    }
    observe = (target: Element) => this.state.active.add(target)
    unobserve = (target: Element) => this.state.active.delete(target)
    disconnect = () => { this.state.active.clear(); this.state.disconnects++ }
  })
})
afterEach(() => { cleanup(); input.restore(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

function mountCanvas() {
  const deck = pptDeck()
  const props: PptCanvasViewProps = { deck, slide: deck.slides[0]!, selected: 'text-1', revision: 3, editing: true,
    onSelect: vi.fn(), onPatch: vi.fn(), onRemove: vi.fn() }
  const view = render(<StrictMode><PptCanvasView {...props} /></StrictMode>)
  const root = view.container.querySelector<HTMLElement>('[data-canvas-kind="ppt"]')!
  const surface = root.querySelector<HTMLElement>('.ppt-canvas')!
  const object = root.querySelector<HTMLElement>('.ppt-canvas-object')!
  vi.spyOn(surface, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 480, 270))
  return { ...view, root, surface, object, props }
}

function start(view: ReturnType<typeof mountCanvas>, resize: boolean, pointerId = 1, pointerType = 'mouse') {
  const target = resize ? view.object.querySelector<HTMLElement>('.ppt-resize-handle')! : view.object
  const previous = view.object.getAttribute('style')
  input.pointer(target, 'pointerdown', 100, 100, { pointerId, pointerType, isPrimary: pointerId === 1 })
  input.pointer(target, 'pointermove', 145, 125, { pointerId, pointerType, isPrimary: pointerId === 1 })
  expect(view.object.classList.contains('dragging')).toBe(true)
  expect(view.object.getAttribute('style')).not.toBe(previous)
  expect(input.captures.get(pointerId)).toBe(target)
  expect(view.props.onPatch).not.toHaveBeenCalled()
  return target
}

describe('PPT active object gestures release their own resources immediately', () => {
  it.each([false, true])('cleans active resize=%s in three StrictMode root cycles before any later input', resize => {
    for (let cycle = 0; cycle < 3; cycle++) {
      const view = mountCanvas(), resources = trackGestureResources([view.root])
      try {
        fireEvent.keyDown(view.root.querySelector('.ppt-canvas-viewport')!, { key: '+' })
        expect(view.root.querySelector('.ppt-zoom-value')!.textContent).toBe('110%')
        const target = start(view, resize)
        // Also exercise an already-queued size observation while the gesture
        // freezes the editing scale. Unmount must disconnect that exact owner.
        act(() => observers.at(-1)!.notify([{ contentRect: { width: 320, height: 240 } } as ResizeObserverEntry], {} as ResizeObserver))
        view.unmount()
        const immediate = { listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size,
          observed: observers.reduce((count, observer) => count + observer.active.size, 0) }
        console.info(JSON.stringify({ resize, cycle, immediate }))
        expect(immediate).toEqual({ listeners: [], raf: 0, capture: 0, observed: 0 })
        for (const observer of observers) expect(observer.disconnects).toBe(1)
        expect(view.props.onPatch).not.toHaveBeenCalled(); expect(view.props.onSelect).not.toHaveBeenCalled()
        // First-snapshot assertions above precede every late event and repeat
        // unmount, so none of these can repair a cleanup failure.
        input.pointer(target, 'pointermove', 160, 150); input.pointer(target, 'pointerup', 160, 150)
        view.unmount()
        expect(view.props.onPatch).not.toHaveBeenCalled(); expect(view.props.onRemove).not.toHaveBeenCalled()
      } finally { resources.restore() }
    }
  })

  it.each([false, true])('unmounts one resize=%s island without cancelling a second captured touch or its observer', resize => {
    const first = mountCanvas(), second = mountCanvas(), resources = trackGestureResources([first.root, second.root])
    try {
      const one = start(first, resize, 1, 'touch'), two = start(second, resize, 2, 'touch')
      expect(input.captures.size).toBe(2)
      first.unmount()
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0)
      expect(input.captures.has(1)).toBe(false); expect(input.captures.get(2)).toBe(two)
      expect(observers.reduce((count, observer) => count + observer.active.size, 0)).toBe(1)
      input.pointer(one, 'pointerup', 145, 125, { pointerId: 1, pointerType: 'touch' })
      expect(first.props.onPatch).not.toHaveBeenCalled()
      input.pointer(two, 'pointerup', 145, 125, { pointerId: 2, pointerType: 'touch' })
      expect(second.props.onPatch).toHaveBeenCalledTimes(1)
      expect(second.props.onPatch).toHaveBeenLastCalledWith('text-1', resize
        ? { x: 80, y: 70, width: 490, height: 140 }
        : { x: 170, y: 120, width: 400, height: 90 }, 3)
      expect(input.captures.size).toBe(0)
      second.unmount()
      expect(resources.listeners()).toEqual([]); expect(resources.frames.size).toBe(0)
      expect(observers.every(observer => observer.active.size === 0)).toBe(true)
    } finally { resources.restore() }
  })
})
