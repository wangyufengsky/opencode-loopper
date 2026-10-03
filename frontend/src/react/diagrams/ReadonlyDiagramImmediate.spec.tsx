import { cleanup, fireEvent, render } from '@testing-library/react'
import { StrictMode, type ReactElement } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { RoleSlotBinding } from '@/types/domain'
import { StageDiagram } from './StageDiagram'
import { RoleDiagram } from './RoleDiagram'
import { TemplateStepDiagram } from './TemplateStepDiagram'
import { installPointerEnvironment, pane, ready, root, stages, trackGestureResources, trackWheelResources, transform, viewport, type PointerEnvironment } from './readonlyDiagramTestHelpers'

let input: PointerEnvironment
beforeEach(() => { input = installPointerEnvironment() })
afterEach(() => { cleanup(); vi.restoreAllMocks(); input.restore() })

describe('strict readonly diagram cleanup', () => {
  const binding: RoleSlotBinding = { slot: 'PACKAGE_DESIGNER', profile: 'DEFAULT', activeRoleId: 'role', activeRevisionId: 'bound', bindingVersion: 1 }
  const diagrams: Array<{ kind: string; edges: number; render: () => ReactElement }> = [
    { kind: 'stages', edges: 1, render: () => <StageDiagram stages={stages()} /> },
    { kind: 'roles', edges: 2, render: () => <RoleDiagram bindings={[binding]} latestRevisionId="new" onRevision={vi.fn()} /> },
    { kind: 'template-progress', edges: 1, render: () => <TemplateStepDiagram steps={[{ key: 'a', label: '范围准备', state: 'COMPLETE' }, { key: 'b', label: '真实验收', state: 'ACTIVE' }]} /> },
  ]

  it.each(diagrams.flatMap(diagram => [false, true].map(moved => ({ ...diagram, moved }))))('$kind moved=$moved captures a real Pointer session and cleans the first root-unmount snapshot', async ({ edges, kind, render: diagram, moved }) => {
    const wheel = trackWheelResources()
    const view = render(<StrictMode>{diagram()}</StrictMode>)
    await ready(view.container, edges)
    const island = root(view.container), resources = trackGestureResources([island])
    try {
      expect(island.dataset.canvasKind).toBe(kind)
      expect(wheel.listeners()).toHaveLength(1)
      input.pointer(pane(view.container), 'pointerdown', 100, 100)
      expect(island.dataset.pointerGesture).toBe('pan')
      expect(input.captures.get(1)).toBe(island)
      expect(resources.listeners()).toHaveLength(5)
      if (moved) {
        input.pointer(island, 'pointermove', 205, 120)
        expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      } else expect(viewport(view.container)).toEqual({ x: 14, y: 12, zoom: 1 })
      const viewportElement = view.container.querySelector<HTMLElement>('.react-flow__viewport')!, last = viewportElement.style.transform
      view.unmount()
      // No move/up/cancel, timer, await or repeated unmount may repair this
      // first snapshot. The preceding marker/capture proves a live gesture.
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size, wheel: wheel.listeners() })
        .toEqual({ listeners: [], raf: 0, capture: 0, wheel: [] })
      input.pointer(island, 'pointermove', 600, 450); input.pointer(island, 'pointerup', 600, 450)
      view.unmount()
      expect(viewportElement.style.transform).toBe(last)
      expect(resources.listeners()).toEqual([])
    } finally { resources.restore(); wheel.restore() }
  })

  it.each(['mouse', 'touch'] as const)('releases all resources through three StrictMode root cycles with active $0 pan/pinch', async pointerType => {
    const wheel = trackWheelResources()
    const stageInput = stages(), original = JSON.stringify(stageInput)
    try {
      for (let cycle = 0; cycle < 3; cycle++) {
        const view = render(<StrictMode><StageDiagram stages={stageInput} /></StrictMode>)
        await ready(view.container)
        const island = root(view.container), resources = trackGestureResources([island])
        try {
          input.pointer(pane(view.container), 'pointerdown', 100, 100, { pointerType })
          expect(island.dataset.pointerGesture).toBe('pan'); expect(input.captures.get(1)).toBe(island)
          if (pointerType === 'touch') {
            input.pointer(pane(view.container), 'pointerdown', 200, 100, { pointerType, pointerId: 2, isPrimary: false })
            input.pointer(island, 'pointermove', 250, 100, { pointerType, pointerId: 2, isPrimary: false })
            expect(input.captures.size).toBe(2)
            expect(viewport(view.container)).toEqual({ x: -29, y: -32, zoom: 1.5 })
          } else {
            input.pointer(island, 'pointermove', 205, 120, { pointerType })
            expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
          }
          expect(resources.listeners()).toHaveLength(5); expect(wheel.listeners()).toHaveLength(1)
          view.unmount()
          expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size, wheel: wheel.listeners() })
            .toEqual({ listeners: [], raf: 0, capture: 0, wheel: [] })
          expect(JSON.stringify(stageInput)).toBe(original)
        } finally { resources.restore() }
      }
    } finally { wheel.restore() }
  })

  it('unmounting one of two captured touch islands preserves the other gesture and unrelated listeners', async () => {
    const wheel = trackWheelResources()
    const first = render(<StrictMode><StageDiagram stages={stages()} /></StrictMode>)
    const second = render(<StrictMode><StageDiagram stages={stages()} /></StrictMode>)
    await ready(first.container); await ready(second.container)
    const a = root(first.container), b = root(second.container), unrelated = vi.fn()
    document.addEventListener('pointermove', unrelated)
    const resources = trackGestureResources([a, b])
    try {
      input.pointer(pane(first.container), 'pointerdown', 100, 100, { pointerType: 'touch' })
      input.pointer(pane(second.container), 'pointerdown', 100, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      input.pointer(a, 'pointermove', 205, 120, { pointerType: 'touch' })
      input.pointer(b, 'pointermove', 120, 105, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      expect(a.dataset.pointerGesture).toBe('pan'); expect(b.dataset.pointerGesture).toBe('pan')
      expect(resources.listeners()).toHaveLength(10)
      first.unmount()
      const remaining = resources.listeners()
      expect(remaining).toHaveLength(5)
      expect(remaining.filter(listener => listener.target === 'root-0')).toEqual([])
      expect(resources.frames.size).toBe(0)
      expect(input.captures.get(1)).toBeUndefined(); expect(input.captures.get(2)).toBe(b)
      expect(wheel.listeners()).toHaveLength(1); expect(wheel.listeners()[0]!.target).toBe(b)
      const beforeLate = transform(second.container)
      input.pointer(a, 'pointermove', 700, 500, { pointerType: 'touch' }); input.pointer(a, 'pointerup', 700, 500, { pointerType: 'touch' })
      expect(transform(second.container)).toBe(beforeLate)
      input.pointer(b, 'pointermove', 205, 120, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      input.pointer(b, 'pointerup', 205, 120, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      expect(viewport(second.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      expect(b.dataset.pointerGesture).toBeUndefined()
      expect(resources.listeners()).toEqual([]); expect(input.captures.size).toBe(0)
      expect(unrelated).toHaveBeenCalled()
      second.unmount()
      expect(wheel.listeners()).toEqual([])
      const count = unrelated.mock.calls.length
      fireEvent(document, new PointerEvent('pointermove', { bubbles: true }))
      expect(unrelated).toHaveBeenCalledTimes(count + 1)
    } finally { resources.restore(); wheel.restore(); document.removeEventListener('pointermove', unrelated) }
  })

  it.each(['pointercancel', 'lostpointercapture', 'blur', 'Escape'])('cancels an active two-pointer gesture via %s immediately and rejects late input', async reason => {
    const view = render(<StrictMode><StageDiagram stages={stages()} /></StrictMode>)
    await ready(view.container)
    const island = root(view.container), resources = trackGestureResources([island]), original = transform(view.container)
    try {
      input.pointer(pane(view.container), 'pointerdown', 100, 100, { pointerType: 'touch' })
      input.pointer(pane(view.container), 'pointerdown', 200, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      input.pointer(island, 'pointermove', 250, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      expect(transform(view.container)).not.toBe(original); expect(input.captures.size).toBe(2)
      if (reason === 'blur') fireEvent(window, new Event('blur'))
      else if (reason === 'Escape') fireEvent.keyDown(island, { key: 'Escape' })
      else input.pointer(island, reason, 250, 100, { pointerType: 'touch', pointerId: 2, isPrimary: false })
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size })
        .toEqual({ listeners: [], raf: 0, capture: 0 })
      expect(island.dataset.pointerGesture).toBeUndefined(); expect(transform(view.container)).toBe(original)
      input.pointer(island, 'pointermove', 700, 500, { pointerType: 'touch' }); input.pointer(island, 'pointerup', 700, 500, { pointerType: 'touch' })
      expect(transform(view.container)).toBe(original)
    } finally { resources.restore() }
  })

  it('cancels before new graph data is displayed while accepting a same-JSON projection during a gesture', async () => {
    const values = stages(), view = render(<StrictMode><StageDiagram stages={values} /></StrictMode>)
    await ready(view.container)
    const island = root(view.container), resources = trackGestureResources([island])
    try {
      const measuredEdge = view.container.querySelector('.react-flow__edge')
      input.pointer(pane(view.container), 'pointerdown', 100, 100)
      input.pointer(island, 'pointermove', 205, 120)
      view.rerender(<StrictMode><StageDiagram stages={values.map(stage => ({ ...stage, attempts: [...stage.attempts] }))} /></StrictMode>)
      expect(island.dataset.pointerGesture).toBe('pan'); expect(input.captures.get(1)).toBe(island)
      // No wait/re-measurement may conceal controlled geometry disappearing.
      expect(view.container.querySelector('.react-flow__edge')).toBe(measuredEdge)
      expect(viewport(view.container)).toEqual({ x: 119, y: 32, zoom: 1 })
      view.rerender(<StrictMode><StageDiagram stages={values.map(stage => ({ ...stage, status: 'RUNNING' }))} /></StrictMode>)
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size })
        .toEqual({ listeners: [], raf: 0, capture: 0 })
      expect(viewport(view.container)).toEqual({ x: 14, y: 12, zoom: 1 })
      expect(view.container.querySelector('.phase-card.is-running')).not.toBeNull()
      input.pointer(island, 'pointermove', 800, 800); input.pointer(island, 'pointerup', 800, 800)
      expect(viewport(view.container)).toEqual({ x: 14, y: 12, zoom: 1 })
    } finally { resources.restore() }
  })

  it.each(['missing', 'throws'])('does not activate an uncleared gesture when native capture %s', async failure => {
    const view = render(<StageDiagram stages={stages()} />)
    await ready(view.container)
    const island = root(view.container), resources = trackGestureResources([island])
    if (failure === 'missing') Object.defineProperty(island, 'setPointerCapture', { configurable: true, value: undefined })
    else vi.spyOn(island, 'setPointerCapture').mockImplementation(() => { throw new DOMException('Detached', 'NotFoundError') })
    try {
      input.pointer(pane(view.container), 'pointerdown', 100, 100)
      expect(island.dataset.pointerGesture).toBeUndefined()
      expect({ listeners: resources.listeners(), raf: resources.frames.size, capture: input.captures.size })
        .toEqual({ listeners: [], raf: 0, capture: 0 })
      input.pointer(island, 'pointermove', 205, 120); input.pointer(island, 'pointerup', 205, 120)
      expect(viewport(view.container)).toEqual({ x: 14, y: 12, zoom: 1 })
    } finally { resources.restore() }
  })
})
