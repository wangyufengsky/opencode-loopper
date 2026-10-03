import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterAll, afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { PptCanvasView, type PptCanvasViewProps } from './PptCanvasView'
import { pptDeck } from '@/components/ppt/pptTestFixtures'

class TestPointerEvent extends MouseEvent {
  readonly pointerId: number
  constructor(type: string, options: PointerEventInit = {}) {
    super(type, options)
    this.pointerId = options.pointerId ?? 0
  }
}

beforeAll(() => vi.stubGlobal('PointerEvent', TestPointerEvent))
afterAll(() => vi.unstubAllGlobals())
afterEach(() => { cleanup(); vi.restoreAllMocks() })

function setup(overrides: Partial<PptCanvasViewProps> = {}) {
  const deck = pptDeck()
  const props = {
    deck, slide: deck.slides[0]!, selected: 'text-1', revision: 3, editing: true,
    onSelect: vi.fn(), onPatch: vi.fn(), onRemove: vi.fn(), ...overrides,
  }
  const result = render(<PptCanvasView {...props} />)
  const surface = screen.getByLabelText('幻灯片画布')
  surface.getBoundingClientRect = () => ({ width: 480 }) as DOMRect
  const object = screen.getByRole('button', { name: '文本框：本季度核心成果' })
  return { ...result, props, surface, object }
}

function start(object: HTMLElement, pointerId = 1) {
  fireEvent.pointerDown(object, { button: 0, clientX: 100, clientY: 100, pointerId })
}

describe('React PPT free object canvas', () => {
  it('renders the actual React canvas and translates scaled dragging into point geometry with its revision', () => {
    const { props, surface, object, container } = setup({ selected: '' })
    expect(container.querySelector('[data-canvas-kind="ppt"]')?.getAttribute('data-canvas-runtime')).toBe('react')
    start(object)
    expect(document.activeElement).toBe(object)
    fireEvent.pointerMove(surface, { clientX: 120, clientY: 110, pointerId: 1 })
    expect(props.onSelect).not.toHaveBeenCalled()
    expect(props.onPatch).not.toHaveBeenCalled()
    expect(object.classList.contains('dragging')).toBe(true)
    fireEvent.pointerUp(surface, { pointerId: 1 })
    expect(props.onSelect).toHaveBeenCalledExactlyOnceWith('text-1')
    expect(props.onPatch).toHaveBeenCalledExactlyOnceWith('text-1', { x: 120, y: 90, width: 400, height: 90 }, 3)
    expect(object.classList.contains('dragging')).toBe(false)
  })

  it('captures and releases the precise pointer while ignoring another pointer and the resulting click', () => {
    const { props, surface, object } = setup()
    const setPointerCapture = vi.fn()
    const releasePointerCapture = vi.fn()
    Object.assign(object, { setPointerCapture, hasPointerCapture: () => true, releasePointerCapture })
    start(object, 9)
    expect(setPointerCapture).toHaveBeenCalledWith(9)
    fireEvent.pointerMove(surface, { clientX: 200, clientY: 200, pointerId: 8 })
    fireEvent.pointerUp(surface, { pointerId: 8 })
    fireEvent.pointerCancel(surface, { pointerId: 8 })
    fireEvent.lostPointerCapture(surface, { pointerId: 8 })
    expect(props.onPatch).not.toHaveBeenCalled()
    fireEvent.pointerMove(surface, { clientX: 120, clientY: 110, pointerId: 9 })
    fireEvent.pointerUp(surface, { pointerId: 9 })
    fireEvent.click(object, { detail: 1 })
    expect(releasePointerCapture).toHaveBeenCalledExactlyOnceWith(9)
    expect(props.onSelect).toHaveBeenCalledTimes(1)
    expect(props.onPatch).toHaveBeenCalledTimes(1)
    fireEvent.click(object, { detail: 0 })
    expect(props.onSelect).toHaveBeenCalledTimes(2)
  })

  it('resizes from the handle using the frozen scale and keeps dimensions above twelve points', () => {
    const { props, surface } = setup()
    const handle = screen.getByRole('button', { name: '拖动调整对象大小' })
    start(handle)
    fireEvent.pointerMove(surface, { clientX: 120, clientY: 110, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })
    expect(props.onPatch).toHaveBeenLastCalledWith('text-1', { x: 80, y: 70, width: 440, height: 110 }, 3)
    start(handle)
    fireEvent.pointerMove(surface, { clientX: -500, clientY: -500, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })
    expect(props.onPatch).toHaveBeenLastCalledWith('text-1', { x: 80, y: 70, width: 12, height: 12 }, 3)
  })

  it('selects a clicked object without submitting an unchanged gesture', () => {
    const { props, surface, object } = setup()
    start(object)
    fireEvent.pointerUp(surface, { pointerId: 1 })
    expect(props.onSelect).toHaveBeenCalledWith('text-1')
    expect(props.onPatch).not.toHaveBeenCalled()
  })

  it('supports keyboard movement, Alt resizing, Shift steps and deletion with the current revision', () => {
    const { props, object } = setup()
    fireEvent.keyDown(object, { key: 'ArrowRight', shiftKey: true })
    expect(props.onPatch).toHaveBeenLastCalledWith('text-1', { x: 90, y: 70 }, 3)
    fireEvent.keyDown(object, { key: 'ArrowUp' })
    expect(props.onPatch).toHaveBeenLastCalledWith('text-1', { x: 80, y: 69 }, 3)
    fireEvent.keyDown(object, { key: 'ArrowRight', altKey: true, shiftKey: true })
    expect(props.onPatch).toHaveBeenLastCalledWith('text-1', { width: 410, height: 90 }, 3)
    fireEvent.keyDown(object, { key: 'Backspace' })
    expect(props.onRemove).toHaveBeenCalledExactlyOnceWith('text-1')
  })

  it.each(['disabled', 'page', 'object'] as const)('preserves readonly selection while blocking writes for %s locking', mode => {
    const deck = pptDeck()
    const slide = deck.slides[0]!
    if (mode === 'page') slide.locked = true
    if (mode === 'object') slide.elements[0]!.locked = true
    const { props, surface, object } = setup({ deck, slide, disabled: mode === 'disabled' })
    start(object)
    fireEvent.pointerMove(surface, { clientX: 120, clientY: 110, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })
    fireEvent.click(object)
    fireEvent.keyDown(object, { key: 'ArrowRight' })
    fireEvent.keyDown(object, { key: 'Delete' })
    expect(props.onSelect).toHaveBeenCalledWith('text-1')
    expect(props.onPatch).not.toHaveBeenCalled()
    expect(props.onRemove).not.toHaveBeenCalled()
    expect(screen.queryByRole('button', { name: '拖动调整对象大小' })).toBeNull()
    fireEvent.keyDown(object, { key: 'Escape' })
    expect(props.onSelect).toHaveBeenLastCalledWith('')
  })

  it('does not edit an unselected object through its keyboard', () => {
    const { props, object } = setup({ selected: '' })
    fireEvent.keyDown(object, { key: 'ArrowRight' })
    fireEvent.keyDown(object, { key: 'Delete' })
    expect(props.onPatch).not.toHaveBeenCalled()
    expect(props.onRemove).not.toHaveBeenCalled()
  })

  it('cancels an in-flight drag with Escape and releases capture without committing', () => {
    const { props, surface, object } = setup()
    const releasePointerCapture = vi.fn()
    Object.assign(object, { setPointerCapture: vi.fn(), hasPointerCapture: () => true, releasePointerCapture })
    start(object)
    fireEvent.pointerMove(surface, { clientX: 150, clientY: 160, pointerId: 1 })
    fireEvent.keyDown(object, { key: 'Escape' })
    fireEvent.pointerUp(surface, { pointerId: 1 })
    expect(releasePointerCapture).toHaveBeenCalledExactlyOnceWith(1)
    expect(props.onPatch).not.toHaveBeenCalled()
    expect(props.onSelect).toHaveBeenLastCalledWith('')
    expect(object.classList.contains('dragging')).toBe(false)
  })

  it.each(['pointerCancel', 'lostPointerCapture'] as const)('cancels a gesture on %s without writing', event => {
    const { props, surface, object } = setup()
    start(object)
    fireEvent.pointerMove(surface, { clientX: 150, clientY: 160, pointerId: 1 })
    fireEvent[event](surface, { pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })
    expect(props.onPatch).not.toHaveBeenCalled()
    expect(object.classList.contains('dragging')).toBe(false)
  })

  it.each(['revision', 'selection', 'disabled', 'slide', 'elementLock'] as const)('cancels a stale gesture when %s changes', reason => {
    const { props, surface, object, rerender } = setup()
    start(object)
    fireEvent.pointerMove(surface, { clientX: 150, clientY: 160, pointerId: 1 })
    const next = { ...props }
    if (reason === 'revision') next.revision = 4
    if (reason === 'selection') next.selected = ''
    if (reason === 'disabled') next.disabled = true
    if (reason === 'slide') next.slide = { ...props.slide, id: 'another-slide' }
    if (reason === 'elementLock') next.slide = { ...props.slide, elements: [{ ...props.slide.elements[0]!, locked: true }] }
    rerender(<PptCanvasView {...next} />)
    fireEvent.pointerUp(screen.getByLabelText('幻灯片画布'), { pointerId: 1 })
    expect(props.onPatch).not.toHaveBeenCalled()
  })

  it('ignores a drag until the displayed canvas has a finite positive scale', () => {
    const { props, surface, object } = setup()
    surface.getBoundingClientRect = () => ({ width: 0 }) as DOMRect
    start(object)
    fireEvent.pointerMove(surface, { clientX: 150, clientY: 160, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })
    expect(props.onPatch).not.toHaveBeenCalled()
  })

  it('identifies stale rendered output and displays its actual image', () => {
    const { container } = setup({ preview: '/preview.png', previewRevision: 2, revision: 4 })
    expect(screen.getByText(/预览来自版本 2，当前草稿为版本 4/)).toBeTruthy()
    expect(screen.getByRole('img', { name: '当前幻灯片实际预览' }).getAttribute('src')).toBe('/preview.png')
    expect(container.querySelector('.ppt-canvas-object')?.classList.contains('placeholder')).toBe(false)
  })

  it('zooms and fits without changing objects or the presentation theme, with bounded keyboard zoom', () => {
    const { props, surface } = setup()
    const before = surface.getAttribute('style')
    const viewport = screen.getByLabelText('幻灯片画布视口')
    fireEvent.keyDown(viewport, { key: '+' })
    expect(screen.getByRole('button', { name: '适应画布' }).textContent).toBe('110%')
    expect(surface.getAttribute('style')).not.toBe(before)
    fireEvent.click(screen.getByRole('button', { name: '适应画布' }))
    expect(surface.getAttribute('style')).toBe(before)
    for (let i = 0; i < 20; i++) fireEvent.keyDown(viewport, { key: '-' })
    expect(screen.getByRole('button', { name: '适应画布' }).textContent).toBe('50%')
    expect(screen.getByRole('button', { name: '缩小画布' }).hasAttribute('disabled')).toBe(true)
    for (let i = 0; i < 20; i++) fireEvent.keyDown(viewport, { key: '=' })
    expect(screen.getByRole('button', { name: '适应画布' }).textContent).toBe('200%')
    expect(screen.getByRole('button', { name: '放大画布' }).hasAttribute('disabled')).toBe(true)
    fireEvent.keyDown(viewport, { key: '0' })
    expect(surface.getAttribute('style')).toBe(before)
    expect(surface.getAttribute('data-theme')).toBe('business')
    expect(props.onPatch).not.toHaveBeenCalled()
  })

  it('keeps the surface size stable during a drag and adopts a narrow viewport after release', () => {
    let notify: ResizeObserverCallback | undefined
    const disconnect = vi.fn()
    const Observer = class {
      constructor(callback: ResizeObserverCallback) { notify = callback }
      observe = vi.fn()
      unobserve = vi.fn()
      disconnect = disconnect
    }
    vi.stubGlobal('ResizeObserver', Observer)
    const { props, surface, object, unmount } = setup()
    const before = surface.style.width
    start(object)
    act(() => notify?.([{ contentRect: { width: 320, height: 240 } } as ResizeObserverEntry], {} as ResizeObserver))
    expect(surface.style.width).toBe(before)
    fireEvent.pointerMove(surface, { clientX: 120, clientY: 110, pointerId: 1 })
    fireEvent.pointerUp(surface, { pointerId: 1 })
    expect(surface.style.width).toBe('272px')
    expect(props.onPatch).toHaveBeenCalledWith('text-1', { x: 120, y: 90, width: 400, height: 90 }, 3)
    unmount()
    expect(disconnect).toHaveBeenCalledOnce()
    vi.stubGlobal('ResizeObserver', undefined)
  })

  it('deselects only direct canvas backgrounds and releases a captured pointer on unmount', () => {
    const { props, surface, object, unmount } = setup()
    fireEvent.click(surface)
    expect(props.onSelect).toHaveBeenLastCalledWith('')
    const releasePointerCapture = vi.fn()
    Object.assign(object, { setPointerCapture: vi.fn(), hasPointerCapture: () => true, releasePointerCapture })
    start(object, 4)
    unmount()
    expect(releasePointerCapture).toHaveBeenCalledExactlyOnceWith(4)
    expect(props.onPatch).not.toHaveBeenCalled()
  })
})
