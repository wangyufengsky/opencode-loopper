import { fireEvent, waitFor } from '@testing-library/react'
import { expect, vi } from 'vitest'
import type { Stage } from '@/types/domain'

// Reuse the existing scoped, restorable browser-shaped Pointer input and
// listener/RAF identity ledger. This does not install a global test setup.
export { installPointerEnvironment, trackGestureResources, type PointerEnvironment } from '../workflow/workflowPointerTestHelpers'

export function stages(count = 2): Stage[] {
  return Array.from({ length: count }, (_, index) => ({ id: `stage-${index}`, ordinal: index + 1,
    objective: `阶段 ${index + 1} 的完整目标`, status: 'PENDING', attempts: [] }))
}
export const root = (container: HTMLElement) => container.querySelector<HTMLElement>('.readonly-diagram')!
export const pane = (container: HTMLElement) => container.querySelector<HTMLElement>('.react-flow__pane')!
export const transform = (container: HTMLElement) => container.querySelector<HTMLElement>('.react-flow__viewport')!.style.transform
export function viewport(container: HTMLElement) {
  const match = /^translate\(([-\d.e]+)px,\s*([-\d.e]+)px\) scale\(([-\d.e]+)\)$/.exec(transform(container))
  expect(match).not.toBeNull()
  return { x: Number(match![1]), y: Number(match![2]), zoom: Number(match![3]) }
}
export async function ready(container: HTMLElement, edges = 1) {
  await waitFor(() => expect(container.querySelectorAll('.react-flow__edge')).toHaveLength(edges))
  vi.spyOn(container.querySelector('.react-flow')!, 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 400))
  vi.spyOn(root(container), 'getBoundingClientRect').mockReturnValue(new DOMRect(0, 0, 800, 400))
}

// Install before render so the lifecycle wheel setup, StrictMode replay and
// removal are all observable. Track only the island's native listener and the
// document/window; React's container delegation belongs to the RTL root.
export function trackWheelResources() {
  const savedAdd = Object.getOwnPropertyDescriptor(EventTarget.prototype, 'addEventListener')!
  const savedRemove = Object.getOwnPropertyDescriptor(EventTarget.prototype, 'removeEventListener')!
  const changes: Array<{ add: boolean; target: EventTarget; type: string; callback: EventListenerOrEventListenerObject | null; options?: boolean | AddEventListenerOptions | EventListenerOptions }> = []
  // A call-through recorder (rather than nested Vitest spies) remains visible
  // when the session ledger independently spies on this root's methods.
  Object.defineProperty(EventTarget.prototype, 'addEventListener', { ...savedAdd,
    value(this: EventTarget, type: string, callback: EventListenerOrEventListenerObject | null, options?: boolean | AddEventListenerOptions) {
      changes.push({ add: true, target: this, type, callback, options })
      Reflect.apply(savedAdd.value, this, [type, callback, options])
    },
  })
  Object.defineProperty(EventTarget.prototype, 'removeEventListener', { ...savedRemove,
    value(this: EventTarget, type: string, callback: EventListenerOrEventListenerObject | null, options?: boolean | EventListenerOptions) {
      changes.push({ add: false, target: this, type, callback, options })
      Reflect.apply(savedRemove.value, this, [type, callback, options])
    },
  })
  const identities = new WeakMap<object, number>(); let nextIdentity = 1
  function relevant(target: EventTarget) {
    return target === window || target === document || target instanceof Element && target.classList.contains('readonly-diagram')
  }
  return {
    restore() {
      Object.defineProperty(EventTarget.prototype, 'addEventListener', savedAdd)
      Object.defineProperty(EventTarget.prototype, 'removeEventListener', savedRemove)
    },
    listeners() {
      const live = new Map<string, { target: EventTarget; callback: EventListenerOrEventListenerObject; capture: boolean }>()
      for (const change of changes) {
        const { type, callback, options } = change
        if (type !== 'wheel' || !callback || !relevant(change.target)) continue
        for (const value of [change.target, callback]) if (!identities.has(value)) identities.set(value, nextIdentity++)
        const capture = typeof options === 'boolean' ? options : options?.capture ?? false
        const key = `${identities.get(change.target)}:${identities.get(callback)}:${capture}`
        if (change.add) live.set(key, { target: change.target, callback, capture })
        else live.delete(key)
      }
      return [...live.values()]
    },
  }
}
export function nativeMouse(target: HTMLElement | Window, type: string, x: number, y: number, browserWindow: Window) {
  const event = new MouseEvent(type, { bubbles: true, cancelable: true, button: 0, buttons: type === 'mouseup' ? 0 : 1, clientX: x, clientY: y })
  Object.defineProperty(event, 'view', { value: browserWindow })
  fireEvent(target, event)
}
