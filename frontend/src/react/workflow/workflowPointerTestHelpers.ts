import { fireEvent, waitFor } from '@testing-library/react'
import { expect, vi } from 'vitest'
import { template } from '@/components/workflow/workflowTestFixtures'
import type { WorkflowCanvasProps } from './types'

export function fixture(extra: Partial<WorkflowCanvasProps> = {}): WorkflowCanvasProps {
  const value = template()
  value.graph.nodes.push({ ...value.graph.nodes[0]!, id: 'later', title: '后续检查' })
  value.graph.edges = [{ id: 'edge', from: 'review', to: 'later', outcome: null }]
  return { graph: value.graph, layout: value.layout, onSelect: vi.fn(), onEdge: vi.fn(), onConnect: vi.fn(), onConnectPair: vi.fn(),
    onLayout: vi.fn(), onRemove: vi.fn(), onCancel: vi.fn(), ...extra }
}
export const node = (container: HTMLElement, id = 'review') => container.querySelector<HTMLElement>(`[data-node-id="${id}"]`)!
export const canvas = (container: HTMLElement) => container.querySelector<HTMLElement>('[data-canvas-kind="workflow"]')!
export async function connected(container: HTMLElement) {
  await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(1))
  vi.spyOn(container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 600))
}

// jsdom lacks PointerEvent and native capture. This supplies browser-shaped
// pointer input and records capture ownership; Chromium remains the evidence
// for native delivery. It never sends an artificial release on unmount.
export function installPointerEnvironment() {
  const captures = new Map<number, Element>()
  const saved = ['setPointerCapture', 'releasePointerCapture', 'hasPointerCapture'].map(key =>
    [key, Object.getOwnPropertyDescriptor(Element.prototype, key)] as const)
  const originalPointer = Object.getOwnPropertyDescriptor(globalThis, 'PointerEvent')
  if (typeof PointerEvent === 'undefined') {
    class TestPointerEvent extends MouseEvent {
      readonly pointerId: number
      readonly pointerType: string
      readonly isPrimary: boolean
      readonly width: number
      readonly height: number
      readonly pressure: number
      constructor(type: string, options: PointerEventInit = {}) {
        super(type, options)
        this.pointerId = options.pointerId ?? 1
        this.pointerType = options.pointerType ?? 'mouse'
        this.isPrimary = options.isPrimary ?? true
        this.width = options.width ?? 1
        this.height = options.height ?? 1
        this.pressure = options.pressure ?? (options.buttons ? .5 : 0)
      }
    }
    Object.defineProperty(globalThis, 'PointerEvent', { configurable: true, writable: true, value: TestPointerEvent })
  }
  Object.defineProperties(Element.prototype, {
    setPointerCapture: { configurable: true, value(this: Element, id: number) { captures.set(id, this) } },
    releasePointerCapture: { configurable: true, value(this: Element, id: number) { if (captures.get(id) === this) captures.delete(id) } },
    hasPointerCapture: { configurable: true, value(this: Element, id: number) { return captures.get(id) === this } },
  })
  return {
    captures,
    pointer(target: HTMLElement | Document | Window, type: string, x: number, y: number, options: PointerEventInit = {}) {
      const pointerId = options.pointerId ?? 1
      const event = new PointerEvent(type, { bubbles: true, cancelable: true, button: 0,
        buttons: ['pointerup', 'pointercancel', 'lostpointercapture'].includes(type) ? 0 : 1,
        clientX: x, clientY: y, pointerType: 'mouse', isPrimary: true, ...options, pointerId })
      if (type === 'lostpointercapture') captures.delete(pointerId)
      // After capture, physical events route to that same element. The event
      // still bubbles through the real section and document listeners.
      const receiver = type === 'pointerdown' ? target : captures.get(pointerId) ?? target
      fireEvent(receiver, event)
      if (type === 'pointerup' || type === 'pointercancel') captures.delete(pointerId)
      return event
    },
    restore() {
      captures.clear()
      for (const [key, descriptor] of saved) {
        if (descriptor) Object.defineProperty(Element.prototype, key, descriptor)
        else Reflect.deleteProperty(Element.prototype, key)
      }
      if (originalPointer) Object.defineProperty(globalThis, 'PointerEvent', originalPointer)
      else Reflect.deleteProperty(globalThis, 'PointerEvent')
    },
  }
}
export type PointerEnvironment = ReturnType<typeof installPointerEnvironment>

export function hitTarget(target: Element | null) {
  const original = Object.getOwnPropertyDescriptor(document, 'elementFromPoint')
  Object.defineProperty(document, 'elementFromPoint', { configurable: true, value: () => target })
  return () => { if (original) Object.defineProperty(document, 'elementFromPoint', original); else Reflect.deleteProperty(document, 'elementFromPoint') }
}

const gestureTypes = new Set(['pointermove', 'pointerup', 'pointercancel', 'lostpointercapture', 'blur',
  'mousemove', 'mouseup', 'touchmove', 'touchend', 'touchcancel', 'dragstart', 'selectstart'])
const capture = (options?: boolean | AddEventListenerOptions | EventListenerOptions) => typeof options === 'boolean' ? options : options?.capture ?? false

// Record identity, target and capture in chronological order. Counting calls
// alone would miss a wrong callback removal or a repeated add after removal.
export function trackGestureResources(roots: HTMLElement[]) {
  const registrations = [
    ...roots.map((root, index) => ({ label: `root-${index}`, target: root })),
    { label: 'document', target: document }, { label: 'window', target: window },
  ].map(({ label, target }) => ({ label, add: vi.spyOn(target, 'addEventListener'), remove: vi.spyOn(target, 'removeEventListener') }))
  const identities = new WeakMap<object, number>(); let nextIdentity = 1
  const frames = new Map<number, FrameRequestCallback>(); let nextFrame = 1
  const requestFrame = vi.spyOn(globalThis, 'requestAnimationFrame').mockImplementation(callback => { const id = nextFrame++; frames.set(id, callback); return id })
  const cancelFrame = vi.spyOn(globalThis, 'cancelAnimationFrame').mockImplementation(id => { frames.delete(id) })
  return {
    frames,
    restore() {
      for (const registration of registrations) { registration.add.mockRestore(); registration.remove.mockRestore() }
      requestFrame.mockRestore(); cancelFrame.mockRestore()
    },
    listeners() {
      const live = new Map<string, { target: string; type: string; capture: boolean; listener: number }>()
      for (const registration of registrations) {
        const changes = [
          ...registration.add.mock.calls.map((call, index) => ({ add: true, call, order: registration.add.mock.invocationCallOrder[index]! })),
          ...registration.remove.mock.calls.map((call, index) => ({ add: false, call, order: registration.remove.mock.invocationCallOrder[index]! })),
        ].sort((a, b) => a.order - b.order)
        for (const change of changes) {
          const [type, listener, options] = change.call
          if (!gestureTypes.has(type) || !listener) continue
          if (!identities.has(listener)) identities.set(listener, nextIdentity++)
          const id = identities.get(listener)!, capturing = capture(options), key = `${registration.label}:${type}:${capturing}:${id}`
          if (change.add) live.set(key, { target: registration.label, type, capture: capturing, listener: id })
          else live.delete(key)
        }
      }
      return [...live.values()]
    },
  }
}
