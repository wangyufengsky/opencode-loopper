import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { template } from '@/components/workflow/workflowTestFixtures'
import { WorkflowCanvasView } from './WorkflowCanvasReact'
import type { WorkflowCanvasHandle, WorkflowCanvasProps } from './types'

afterEach(() => { cleanup(); vi.restoreAllMocks() })
function fixture(extra: Partial<WorkflowCanvasProps> = {}): WorkflowCanvasProps {
  const value = template()
  value.graph.nodes.push({ ...value.graph.nodes[0]!, id: 'later', title: '后续检查' })
  value.graph.edges = [{ id: 'edge', from: 'review', to: 'later', outcome: null }]
  return { graph: value.graph, layout: value.layout, onSelect: vi.fn(), onEdge: vi.fn(), onConnect: vi.fn(), onConnectPair: vi.fn(),
    onLayout: vi.fn(), onRemove: vi.fn(), onCancel: vi.fn(), ...extra }
}
const node = (container: HTMLElement, id = 'review') => container.querySelector<HTMLElement>(`[data-node-id="${id}"]`)!
async function connected(container: HTMLElement) { await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(1)) }
function mouse(target: HTMLElement | Document | Window, type: string, x: number, y: number, browserWindow: Window) {
  // Vitest's Window proxy is rejected by jsdom's UIEvent constructor. Attach
  // the owning window after construction so d3 receives its native event view.
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, button: 0, buttons: type === 'mouseup' ? 0 : 1, clientX: x, clientY: y })
  Object.defineProperty(event, 'view', { value: browserWindow })
  fireEvent(target, event)
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

  it('routes a native React Flow handle connection through the pair callback without creating an edge or layout', async () => {
    const props = fixture({ selected: 'review' }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const source = node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')!
    const target = node(view.container, 'later').querySelector<HTMLElement>('.react-flow__handle.target')!
    const original = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => target })
    try { fireEvent.click(source); fireEvent.click(target) }
    finally { if (original) Object.defineProperty(document, 'elementFromPoint', original); else Reflect.deleteProperty(document, 'elementFromPoint') }
    expect(props.onConnectPair).toHaveBeenCalledExactlyOnceWith('review', 'later')
    expect(props.onConnect).not.toHaveBeenCalled(); expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onLayout).not.toHaveBeenCalled()
    expect(props.graph.edges).toHaveLength(1)
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
    expect(props.onRemove).not.toHaveBeenCalled(); expect(view.container.querySelectorAll('.react-flow__node.draggable')).toHaveLength(2)
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

  it('cancels on blank clicks and Escape while controls, selection and a pan retain context', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    fireEvent.click(node(view.container)); fireEvent.click(screen.getByRole('button', { name: '放大画布' }))
    expect(props.onCancel).not.toHaveBeenCalled()
    const canvas = screen.getByRole('region', { name: '流程画布' })
    fireEvent.mouseDown(canvas, { button: 0, clientX: 20, clientY: 20 })
    fireEvent.mouseMove(canvas, { clientX: 60, clientY: 70 }); fireEvent.mouseUp(canvas)
    fireEvent.click(canvas); expect(props.onCancel).not.toHaveBeenCalled()
    fireEvent.mouseDown(canvas, { button: 0, clientX: 20, clientY: 20 }); fireEvent.mouseUp(canvas); fireEvent.click(canvas)
    fireEvent.keyDown(node(view.container), { key: 'Escape' }); expect(props.onCancel).toHaveBeenCalledTimes(2)
  })

  it('uses native React Flow dragging to persist a single node move in canvas coordinates', async () => {
    const props = fixture(); props.layout.zoom = 2
    const view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const article = node(view.container), browserWindow = article.ownerDocument.defaultView!
    mouse(article, 'mousedown', 100, 100, browserWindow)
    mouse(browserWindow, 'mousemove', 110, 110, browserWindow)
    mouse(browserWindow, 'mousemove', 190, 170, browserWindow)
    mouse(browserWindow, 'mouseup', 190, 170, browserWindow)
    await waitFor(() => expect(props.onLayout).toHaveBeenCalledTimes(1))
    expect(props.onLayout).toHaveBeenLastCalledWith(expect.objectContaining({ positions: { review: { x: 45, y: 35 } }, zoom: 2 }))
    expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('review'); expect(props.onCancel).not.toHaveBeenCalled()
    expect(props.layout.positions).toEqual({})
  })

  it('creates native drag connections and lets Escape cancel a drag before the mouse is released', async () => {
    const props = fixture({ selected: 'review' }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const source = node(view.container).querySelector<HTMLElement>('.react-flow__handle.source')!
    const target = node(view.container, 'later').querySelector<HTMLElement>('.react-flow__handle.target')!
    const browserWindow = source.ownerDocument.defaultView!, original = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
    Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => target })
    try {
      mouse(source, 'mousedown', 150, 150, browserWindow); mouse(document, 'mousemove', 200, 250, browserWindow)
      expect(view.container.querySelector('.workflow-connection-dragging')).toBeTruthy()
      fireEvent.keyDown(source, { key: 'Escape' }); mouse(document, 'mouseup', 200, 250, browserWindow)
      expect(props.onConnectPair).not.toHaveBeenCalled(); expect(props.onCancel).toHaveBeenCalledTimes(1)
      expect(view.container.querySelector('.workflow-connection-dragging')).toBeNull()
      mouse(source, 'mousedown', 150, 150, browserWindow); mouse(document, 'mousemove', 200, 250, browserWindow)
      mouse(document, 'mouseup', 200, 250, browserWindow)
      expect(props.onConnectPair).toHaveBeenCalledExactlyOnceWith('review', 'later')
      expect(props.onLayout).not.toHaveBeenCalled(); expect(props.graph.edges).toHaveLength(1)
    } finally { if (original) Object.defineProperty(document, 'elementFromPoint', original); else Reflect.deleteProperty(document, 'elementFromPoint') }
  })

  it('persists a native viewport pan once without dismissing the selected context', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    const pane = view.container.querySelector<HTMLElement>('.react-flow__pane')!, browserWindow = pane.ownerDocument.defaultView!
    mouse(pane, 'mousedown', 200, 200, browserWindow); mouse(browserWindow, 'mousemove', 260, 250, browserWindow)
    mouse(browserWindow, 'mouseup', 260, 250, browserWindow)
    await waitFor(() => expect(props.onLayout).toHaveBeenCalledTimes(1))
    expect(props.onLayout).toHaveBeenLastCalledWith(expect.objectContaining({ x: 92, y: 86, zoom: 1 }))
    expect(props.onCancel).not.toHaveBeenCalled(); expect(props.layout).toMatchObject({ x: 32, y: 36, zoom: 1 })
  })

  it('rolls back an in-progress drag when movement is locked, preserving reveal and the next keyboard edit', async () => {
    let handle: WorkflowCanvasHandle | undefined
    const props = fixture({ onReady: value => { handle = value } }), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const browserWindow = node(view.container).ownerDocument.defaultView!
    mouse(node(view.container), 'mousedown', 100, 100, browserWindow)
    mouse(browserWindow, 'mousemove', 110, 110, browserWindow); mouse(browserWindow, 'mousemove', 190, 170, browserWindow)
    expect(node(view.container).parentElement!.style.transform).toBe('translate(90px,70px)')
    act(() => handle!.reveal('later'))
    const revealed = view.container.querySelector('.react-flow__viewport')!.getAttribute('style')
    view.rerender(<WorkflowCanvasView {...props} readonly />)
    mouse(browserWindow, 'mouseup', 190, 170, browserWindow)
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
    mouse(node(view.container), 'mousedown', 100, 100, browserWindow)
    mouse(browserWindow, 'mousemove', 110, 110, browserWindow); mouse(browserWindow, 'mousemove', 190, 170, browserWindow)
    const accepted = { ...props.layout, positions: { review: { x: 300, y: 400 } } }
    view.rerender(<WorkflowCanvasView {...props} layout={accepted} />)
    mouse(browserWindow, 'mouseup', 190, 170, browserWindow)
    expect(props.onLayout).not.toHaveBeenCalled()
    expect(node(view.container).parentElement!.style.transform).toBe('translate(300px,400px)')
    fireEvent.keyDown(node(view.container), { key: 'ArrowRight' })
    expect(props.onLayout).toHaveBeenCalledExactlyOnceWith(expect.objectContaining({ positions: { review: { x: 324, y: 400 } } }))
  })

  it('keeps pointer press and small jitter from selecting or saving a node', async () => {
    const props = fixture(), view = render(<WorkflowCanvasView {...props} />)
    await connected(view.container)
    vi.spyOn(view.container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
    const browserWindow = node(view.container).ownerDocument.defaultView!
    mouse(node(view.container), 'mousedown', 100, 100, browserWindow)
    expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onLayout).not.toHaveBeenCalled()
    mouse(browserWindow, 'mousemove', 101, 100, browserWindow); mouse(browserWindow, 'mouseup', 101, 100, browserWindow)
    expect(props.onSelect).not.toHaveBeenCalled(); expect(props.onLayout).not.toHaveBeenCalled()
    expect(node(view.container).parentElement!.style.transform).toBe('translate(0px,0px)')
    fireEvent.click(node(view.container)); expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('review')
  })
})
