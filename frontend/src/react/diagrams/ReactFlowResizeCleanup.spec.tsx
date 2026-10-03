import { act, cleanup, fireEvent, render, within } from '@testing-library/react'
import { createRequire } from 'node:module'
import { StrictMode, type ComponentType, type ReactElement } from 'react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { ReactFlowProps } from '@xyflow/react'
import { PanOnScrollMode, XYPanZoom, type PanZoomInstance, type PanZoomUpdateOptions } from '@xyflow/system'
import { StageDiagram } from './StageDiagram'
import { RoleDiagram } from './RoleDiagram'
import { TemplateStepDiagram } from './TemplateStepDiagram'
import { installPointerEnvironment, pane, ready, root, stages, viewport, type PointerEnvironment } from './readonlyDiagramTestHelpers'
import { WorkflowCanvasView } from '../workflow/WorkflowCanvasReact'
import { canvas as workflowRoot, connected, fixture as workflowFixture } from '../workflow/workflowPointerTestHelpers'

interface Observation {
  target: Element
  owner: Element | null
}
interface ObserverRecord {
  observer: ResizeObserver
  active: Map<Element, Element | null>
  observed: Observation[]
  unobserved: Element[]
  disconnects: number
  deliver: ResizeObserverCallback
  creationStack: string
}
let records: ObserverRecord[], input: PointerEnvironment
beforeEach(() => {
  records = []; input = installPointerEnvironment()
  const OriginalObserver = globalThis.ResizeObserver
  // Forward all measurement callbacks to the existing jsdom geometry shim.
  // This records native-shaped ownership and explicit disposal, not heap/GC.
  class ObservedResizeObserver extends OriginalObserver {
    record: ObserverRecord
    constructor(deliver: ResizeObserverCallback) {
      super((entries, observer) => deliver(entries, observer))
      this.record = { observer: this, active: new Map(), observed: [], unobserved: [], disconnects: 0, deliver, creationStack: new Error('RO constructor ownership').stack ?? '' }
      records.push(this.record)
    }
    override observe(target: Element, options?: ResizeObserverOptions) {
      super.observe(target, options)
      const owner = target.closest('.readonly-diagram, [data-canvas-kind="workflow"]')
      this.record.active.set(target, owner); this.record.observed.push({ target, owner })
    }
    override unobserve(target: Element) {
      super.unobserve(target); this.record.active.delete(target); this.record.unobserved.push(target)
    }
    override disconnect() {
      super.disconnect(); this.record.active.clear(); this.record.disconnects++
    }
  }
  vi.stubGlobal('ResizeObserver', ObservedResizeObserver)
})
afterEach(() => { cleanup(); input.restore(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

const rendererRecords = (owner: Element) => records.filter(record => record.observed.some(row => row.owner === owner && row.target.classList.contains('react-flow__renderer')))
const ownedRecords = (owner: Element) => records.filter(record => record.observed.some(row => row.owner === owner))
const responsibility = (record: ObserverRecord) => record.observed.some(row => row.target.classList.contains('react-flow__node'))
  ? 'node-measurement' : record.creationStack.includes('XYPanZoom') || /at li \(.*@xyflow\/react\/dist\/umd\/index\.js:/.test(record.creationStack)
    ? 'panzoom-extent' : 'renderer-size'
function live(owner?: Element) {
  return records.flatMap((record, observerId) => [...record.active]
    .filter(([, observedOwner]) => !!observedOwner && (!owner || observedOwner === owner))
    .map(([target, observedOwner]) => ({ observerId, target, owner: observedOwner, connected: target.isConnected,
      renderer: target.classList.contains('react-flow__renderer'), disconnects: record.disconnects })))
}
function diagnose(owner: Element, label: string) {
  return { label, live: live(owner).map(row => ({ observerId: row.observerId, target: row.target.className,
    connected: row.connected, renderer: row.renderer, disconnects: row.disconnects })),
    observers: ownedRecords(owner).map(record => ({ responsibility: responsibility(record), creationStack: record.creationStack,
      renderer: record.observed.some(row => row.target.classList.contains('react-flow__renderer')),
      observed: record.observed.length, unobserved: record.unobserved.length, disconnects: record.disconnects })) }
}
function emitMeasuredTargets(owner: Element) {
  act(() => {
    for (const record of ownedRecords(owner)) {
      const entries = [...record.active].filter(([, observedOwner]) => observedOwner === owner).map(([target]) => ({ target,
        contentRect: target.getBoundingClientRect(), borderBoxSize: [], contentBoxSize: [], devicePixelContentBoxSize: [] } satisfies ResizeObserverEntry))
      if (entries.length) record.deliver(entries, record.observer)
    }
  })
}
const fixtures: Array<{ kind: string; edges: number; element: () => ReactElement }> = [
  { kind: 'stages', edges: 1, element: () => <StageDiagram stages={stages()} /> },
  { kind: 'roles', edges: 2, element: () => <RoleDiagram bindings={[{ slot: 'PACKAGE_DESIGNER', profile: 'DEFAULT', activeRoleId: 'role', activeRevisionId: 'bound', bindingVersion: 1 }]} latestRevisionId="bound" onRevision={vi.fn()} /> },
  { kind: 'template-progress', edges: 1, element: () => <TemplateStepDiagram steps={[{ key: 'a', label: '范围准备', state: 'COMPLETE' }, { key: 'b', label: '真实验收', state: 'ACTIVE' }]} /> },
]
// The CJS export is a self-contained UMD bundle with its own inlined system
// implementation; loading its exports alone cannot verify observer cleanup.
const CjsReactFlow = createRequire(import.meta.url)('@xyflow/react').ReactFlow as ComponentType<ReactFlowProps>

describe('actual React Flow renderer ResizeObserver cleanup', () => {
  it('releases the actual CJS ReactFlow root and its inlined extent observer immediately', async () => {
    const view = render(<StrictMode><div className="readonly-diagram" style={{ width: 800, height: 400 }}><CjsReactFlow
      nodes={[{ id: 'a', position: { x: 0, y: 0 }, data: { label: '真实 CJS 起点' } }, { id: 'b', position: { x: 240, y: 0 }, data: { label: '真实 CJS 终点' } }]}
      edges={[{ id: 'edge', source: 'a', target: 'b' }]} nodesDraggable={false} nodesConnectable={false}
      panOnDrag={false} zoomOnScroll={false} zoomOnPinch={false} zoomOnDoubleClick={false} /></div></StrictMode>)
    await ready(view.container)
    const owner = root(view.container), renderers = rendererRecords(owner)
    expect(renderers.length).toBeGreaterThanOrEqual(2)
    expect(live(owner).some(row => row.renderer && row.connected)).toBe(true)
    expect(live(owner).some(row => row.target.classList.contains('react-flow__node'))).toBe(true)
    expect(view.container.querySelector('.react-flow__edge-path')?.getAttribute('d')).toContain('M')
    view.unmount()
    console.info(JSON.stringify(diagnose(owner, 'CJS-inlined-system/strict-root-exit')))
    expect(live(owner)).toEqual([])
    for (const record of renderers) expect(record.disconnects).toBeGreaterThan(0)
  })

  it('releases the shared renderer and measured nodes of an actual workflow root immediately', async () => {
    const props = workflowFixture(), view = render(<StrictMode><WorkflowCanvasView {...props} /></StrictMode>)
    await connected(view.container)
    const owner = workflowRoot(view.container), renderers = rendererRecords(owner)
    expect(view.container.querySelector('.workflow-wire-hit')).toBeTruthy()
    expect(renderers.some(record => responsibility(record) === 'renderer-size')).toBe(true)
    expect(renderers.some(record => responsibility(record) === 'panzoom-extent')).toBe(true)
    expect(live(owner).some(row => row.target.classList.contains('react-flow__node'))).toBe(true)
    view.unmount()
    console.info(JSON.stringify(diagnose(owner, 'workflow/strict-root-exit')))
    expect(live(owner)).toEqual([])
    for (const record of renderers) expect(record.disconnects).toBeGreaterThan(0)
    expect(props.onLayout).not.toHaveBeenCalled(); expect(props.onConnectPair).not.toHaveBeenCalled()
  })

  it.each(fixtures.flatMap(fixture => [false, true].map(strict => ({ ...fixture, strict }))))('$kind StrictMode=$strict releases the real renderer and node targets on the first root-unmount snapshot', async fixture => {
    const view = render(fixture.strict ? <StrictMode>{fixture.element()}</StrictMode> : fixture.element())
    await ready(view.container, fixture.edges)
    const owner = root(view.container), renderers = rendererRecords(owner)
    // Ensure the upstream hook truly ran and observed its actual DOM renderer.
    expect(renderers.length).toBeGreaterThan(0)
    expect(live(owner).some(row => row.renderer && row.connected)).toBe(true)
    expect(live(owner).some(row => !row.renderer && row.target.classList.contains('react-flow__node'))).toBe(true)
    view.unmount()
    // No post-unmount callback/event/timer/second unmount or test-led cleanup
    // may precede this first sample. Ref detachment is React's real lifecycle.
    const immediate = diagnose(owner, `${fixture.kind}/strict=${fixture.strict}`)
    console.info(JSON.stringify(immediate))
    expect(live(owner)).toEqual([])
    for (const record of renderers) expect(record.disconnects).toBeGreaterThan(0)
    expect(owner.isConnected).toBe(false)
  })

  it('leaves no renderer observation from StrictMode replay while its root is still mounted', async () => {
    const view = render(<StrictMode><StageDiagram stages={stages()} /></StrictMode>)
    await ready(view.container)
    const owner = root(view.container), renderers = rendererRecords(owner)
    expect(renderers.length).toBeGreaterThanOrEqual(2)
    console.info(JSON.stringify(diagnose(owner, 'strict-replay-still-mounted')))
    const current = renderers.filter(record => record.active.size)
    // One current React size-hook owner and one current system cached-extent
    // owner are required; retired StrictMode owners must all be disconnected.
    expect(current).toHaveLength(2)
    expect(current.filter(record => responsibility(record) === 'renderer-size')).toHaveLength(1)
    expect(current.filter(record => responsibility(record) === 'panzoom-extent')).toHaveLength(1)
    const retired = renderers.filter(record => !record.active.size)
    // Witness actual effect replay: an empty retired set must not make this
    // cleanup assertion pass if StrictMode is accidentally removed.
    expect(retired.filter(record => responsibility(record) === 'renderer-size')).toHaveLength(1)
    expect(retired.filter(record => responsibility(record) === 'panzoom-extent')).toHaveLength(1)
    for (const record of retired) expect(record.disconnects).toBeGreaterThan(0)
    view.unmount()
    expect(live(owner)).toEqual([])
  })

  it.each(fixtures)('$kind releases all observer identities through three independent StrictMode root cycles', async fixture => {
    const identities = new Set<ResizeObserver>()
    for (let cycle = 0; cycle < 3; cycle++) {
      const view = render(<StrictMode>{fixture.element()}</StrictMode>)
      await ready(view.container, fixture.edges)
      const owner = root(view.container), renderers = rendererRecords(owner)
      expect(renderers.length).toBeGreaterThan(0)
      for (const record of renderers) { expect(identities.has(record.observer)).toBe(false); identities.add(record.observer) }
      view.unmount()
      console.info(JSON.stringify(diagnose(owner, `${fixture.kind}/cycle=${cycle}`)))
      expect(live()).toEqual([])
      for (const record of renderers) expect(record.disconnects).toBeGreaterThan(0)
    }
  })

  it('unmounts only its own observed island while the second keeps measured edges and zoom input', async () => {
    const first = render(<StrictMode><StageDiagram stages={stages()} /></StrictMode>)
    const second = render(<StrictMode><StageDiagram stages={stages()} /></StrictMode>)
    await ready(first.container); await ready(second.container)
    const a = root(first.container), b = root(second.container), secondBefore = live(b)
    expect(secondBefore.length).toBeGreaterThan(0)
    expect(secondBefore.every(row => row.connected)).toBe(true)
    first.unmount()
    console.info(JSON.stringify(diagnose(a, 'two-islands/first-exit')))
    expect(live(a)).toEqual([])
    expect(live(b)).toEqual(secondBefore)
    const edge = second.container.querySelector('.react-flow__edge')
    emitMeasuredTargets(b)
    expect(second.container.querySelector('.react-flow__edge')).toBe(edge)
    fireEvent.click(within(b).getByRole('button', { name: '放大流程图' }))
    expect(viewport(second.container).zoom).toBeCloseTo(1.2)
    second.unmount()
    expect(live()).toEqual([])
  })

  it.each(fixtures)('$kind retains measured node positions, real edges and the viewport across size callbacks and graph projections', async fixture => {
    const view = render(<StrictMode>{fixture.element()}</StrictMode>)
    await ready(view.container, fixture.edges)
    const owner = root(view.container), nodeStyles = [...view.container.querySelectorAll<HTMLElement>('.react-flow__node')].map(node => node.style.transform)
    const actualPaths = [...view.container.querySelectorAll('.react-flow__edge-path')].map(edge => edge.getAttribute('d'))
    expect(actualPaths).toHaveLength(fixture.edges); expect(actualPaths.every(path => path && path.includes('M'))).toBe(true)
    emitMeasuredTargets(owner)
    view.rerender(<StrictMode>{fixture.element()}</StrictMode>)
    // Do not wait for another RO notification to repair a lost measured edge.
    expect([...view.container.querySelectorAll('.react-flow__edge-path')].map(edge => edge.getAttribute('d'))).toEqual(actualPaths)
    expect([...view.container.querySelectorAll<HTMLElement>('.react-flow__node')].map(node => node.style.transform)).toEqual(nodeStyles)
    fireEvent.click(within(owner).getByRole('button', { name: '放大流程图' }))
    const initial = viewport(view.container)
    expect(initial.zoom).toBeCloseTo(1.2)
    input.pointer(pane(view.container), 'pointerdown', 100, 100)
    expect(input.captures.get(1)).toBe(owner)
    input.pointer(owner, 'pointermove', 205, 120); input.pointer(owner, 'pointerup', 205, 120)
    expect(viewport(view.container).x - initial.x).toBeCloseTo(105)
    expect(viewport(view.container).y - initial.y).toBeCloseTo(20)
    emitMeasuredTargets(owner)
    expect(viewport(view.container).zoom).toBeCloseTo(1.2)
    expect([...view.container.querySelectorAll<HTMLElement>('.react-flow__node')].map(node => node.style.transform)).toEqual(nodeStyles)
    expect([...view.container.querySelectorAll('.react-flow__edge-path')].map(edge => edge.getAttribute('d'))).toEqual(actualPaths)
    fireEvent.click(within(owner).getByRole('button', { name: '适应流程图' }))
    expect(viewport(view.container).zoom).toBeGreaterThan(.2); expect(viewport(view.container).zoom).toBeLessThanOrEqual(2)
  })
})

function systemCanvas() {
  const owner = document.createElement('div'); owner.className = 'readonly-diagram'
  const renderer = document.createElement('div'); renderer.className = 'react-flow__renderer'
  owner.append(renderer); document.body.append(owner)
  vi.spyOn(renderer, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 400))
  const flow = XYPanZoom({ domNode: renderer, minZoom: .2, maxZoom: 4, translateExtent: [[-Infinity, -Infinity], [Infinity, Infinity]],
    viewport: { x: 0, y: 0, zoom: 1 }, onDraggingChange: vi.fn() })
  const record = rendererRecords(owner).find(record => record.active.has(renderer))!
  expect(record).toBeDefined(); expect(responsibility(record)).toBe('panzoom-extent')
  return { owner, renderer, flow, record }
}
function updateSystem(flow: PanZoomInstance, userSelectionActive: boolean) {
  const options: PanZoomUpdateOptions = { noWheelClassName: 'nowheel', noPanClassName: 'nopan', panOnDrag: false, panOnScroll: false,
    panOnScrollMode: PanOnScrollMode.Free, panOnScrollSpeed: .5, preventScrolling: false, userSelectionActive,
    zoomOnPinch: false, zoomOnScroll: false, zoomOnDoubleClick: false, zoomActivationKeyPressed: false,
    lib: 'react', onTransformChange: vi.fn(), connectionInProgress: false, paneClickDistance: 1 }
  flow.update(options)
}

describe('public XYPanZoom extent observer disposal', () => {
  it('keeps extent measurement alive during selection pause and uses the resized center after resuming', async () => {
    const { owner, renderer, flow, record } = systemCanvas()
    try {
      updateSystem(flow, false)
      await flow.scaleTo(2)
      expect(flow.getViewport()).toEqual({ x: -400, y: -200, zoom: 2 })
      await flow.setViewport({ x: 0, y: 0, zoom: 1 })
      updateSystem(flow, true)
      expect(record.active.has(renderer)).toBe(true); expect(record.disconnects).toBe(0)
      record.deliver([{ target: renderer, contentRect: new DOMRect(0, 0, 400, 200), borderBoxSize: [], contentBoxSize: [], devicePixelContentBoxSize: [] }], record.observer)
      updateSystem(flow, false)
      await flow.scaleTo(2)
      // scaleTo's default center comes from the real cached extent callback;
      // retaining a stale 800x400 extent would produce -400/-200 instead.
      expect(flow.getViewport()).toEqual({ x: -200, y: -100, zoom: 2 })
      expect(record.active.has(renderer)).toBe(true); expect(record.disconnects).toBe(0)
      flow.destroy()
      console.info(JSON.stringify(diagnose(owner, 'system-selection-then-final-destroy')))
      expect(live(owner)).toEqual([]); expect(record.disconnects).toBeGreaterThan(0)
    } finally { owner.remove() }
  })

  it('disconnects only its own cached extent on public destroy and repeated calls preserve the other instance', async () => {
    const first = systemCanvas(), second = systemCanvas()
    try {
      updateSystem(first.flow, false); updateSystem(second.flow, false)
      const other = [...second.record.active]
      first.flow.destroy()
      console.info(JSON.stringify(diagnose(first.owner, 'system-two-islands/first-destroy')))
      expect(live(first.owner)).toEqual([]); expect(first.record.disconnects).toBeGreaterThan(0)
      expect([...second.record.active]).toEqual(other); expect(second.record.disconnects).toBe(0)
      first.flow.destroy()
      expect(live(first.owner)).toEqual([])
      expect([...second.record.active]).toEqual(other); expect(second.record.disconnects).toBe(0)
      second.record.deliver([{ target: second.renderer, contentRect: new DOMRect(0, 0, 400, 200), borderBoxSize: [], contentBoxSize: [], devicePixelContentBoxSize: [] }], second.record.observer)
      await second.flow.scaleTo(2)
      expect(second.flow.getViewport()).toEqual({ x: -200, y: -100, zoom: 2 })
      second.flow.destroy(); second.flow.destroy()
      expect(live()).toEqual([])
    } finally { first.owner.remove(); second.owner.remove() }
  })
})
