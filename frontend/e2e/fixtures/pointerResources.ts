import type { Page } from '@playwright/test'

export type PointerResourceSnapshot = {
  location: string
  roots: number
  listeners: { target: string; type: string; capture: boolean; callback: string }[]
  wheelListeners: { target: string; capture: boolean; callback: string }[]
  pendingFrames: number[]
  captures: { pointerId: number; target: string; connected: boolean }[]
  events: { type: string; target: string; pointerId?: number; pointerType?: string; clientX?: number; clientY?: number; trusted: boolean }[]
  sentinelMoves: number
  sentinelFrames: number
  sentinelListener: boolean
}

type PointerProbe = { arm(): void; disarm(): void; snapshot(): PointerResourceSnapshot }
declare global { interface Window { __pointerResources: PointerProbe } }

/** Observe identities and native capture state. This helper never removes a product listener. */
export async function observePointerResources(page: Page) {
  await page.addInitScript(() => {
    const types = new Set(['pointermove', 'pointerup', 'pointercancel', 'lostpointercapture', 'blur',
      'mousemove', 'mouseup', 'touchmove', 'touchend', 'touchcancel', 'dragstart', 'selectstart', 'wheel'])
    const nativeAdd = EventTarget.prototype.addEventListener, nativeRemove = EventTarget.prototype.removeEventListener
    const request = window.requestAnimationFrame.bind(window), cancel = window.cancelAnimationFrame.bind(window)
    const capture = (options?: boolean | AddEventListenerOptions | EventListenerOptions) => typeof options === 'boolean' ? options : !!options?.capture
    const entries: { target: EventTarget; type: string; listener: EventListenerOrEventListenerObject;
      capture: boolean; owned: boolean; active: boolean; signal?: AbortSignal }[] = []
    const frames = new Map<number, boolean>()
    const captures: { target: Element; pointerId: number }[] = []
    const events: { type: string; target: string; pointerId?: number; pointerType?: string; clientX?: number; clientY?: number; trusted: boolean }[] = []
    let armed = false, sentinelMoves = 0, sentinelFrames = 0
    const scope = (target: EventTarget) => target === window || target === document
      || target instanceof Element && !!target.closest('[data-canvas-kind="workflow"]')
    const name = (target: EventTarget | null) => target === window ? 'window' : target === document ? 'document'
      : target instanceof Element ? `${target.tagName.toLowerCase()}.${target.className}` : 'other'
    EventTarget.prototype.addEventListener = function(type, listener, options) {
      const result = nativeAdd.call(this, type, listener, options)
      if (listener && scope(this) && types.has(type) && !entries.some(row => row.active && row.target === this
        && row.type === type && row.listener === listener && row.capture === capture(options))) {
        entries.push({ target: this, type, listener, capture: capture(options), owned: armed, active: true,
          signal: typeof options === 'object' ? options.signal : undefined })
      }
      return result
    }
    EventTarget.prototype.removeEventListener = function(type, listener, options) {
      const result = nativeRemove.call(this, type, listener, options)
      for (const row of entries) if (row.active && row.target === this && row.type === type
        && row.listener === listener && row.capture === capture(options)) row.active = false
      return result
    }
    window.requestAnimationFrame = callback => {
      const id = request(time => { frames.delete(id); callback(time) })
      frames.set(id, armed); return id
    }
    window.cancelAnimationFrame = id => { frames.delete(id); cancel(id) }
    const setCapture = Element.prototype.setPointerCapture, releaseCapture = Element.prototype.releasePointerCapture
    Element.prototype.setPointerCapture = function(pointerId) {
      const result = setCapture.call(this, pointerId)
      if (armed && !captures.some(row => row.target === this && row.pointerId === pointerId)) captures.push({ target: this, pointerId })
      return result
    }
    Element.prototype.releasePointerCapture = function(pointerId) { return releaseCapture.call(this, pointerId) }
    for (const type of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'lostpointercapture', 'mousemove', 'mouseup']) {
      nativeAdd.call(document, type, ((event: PointerEvent) => {
        if (armed) events.push({ type: event.type, target: name(event.target), pointerId: event.pointerId,
          pointerType: event.pointerType, clientX: event.clientX, clientY: event.clientY, trusted: event.isTrusted })
      }) as EventListener, true)
    }
    nativeAdd.call(window, 'blur', event => { if (armed) events.push({ type: event.type, target: name(event.target), trusted: event.isTrusted }) }, true)
    // Persistent unrelated resources must survive each island's cleanup. They use
    // the original RAF function, so they cannot masquerade as gesture-owned frames.
    const sentinel = () => { sentinelMoves++ }
    window.addEventListener('pointermove', sentinel)
    function tick() { sentinelFrames++; request(tick) }
    request(tick)
    Object.assign(window, { __pointerResources: {
      arm: () => { armed = true; events.length = 0 },
      disarm: () => { armed = false },
      snapshot: () => ({
        location: location.pathname,
        roots: document.querySelectorAll('[data-canvas-runtime="react"][data-canvas-kind="workflow"]').length,
        // Keep earlier cycles' owned entries: resetting the observation window
        // must never hide a listener leaked by an earlier island.
        listeners: entries.filter(row => row.active && row.owned && !row.signal?.aborted).map(row => ({
          target: name(row.target), type: row.type, capture: row.capture,
          callback: typeof row.listener === 'function' ? row.listener.name : 'object',
        })),
        // The section's wheel handler is an instance resource installed before
        // arm(). Observe it independently without exempting gesture resources.
        wheelListeners: entries.filter(row => row.active && row.type === 'wheel' && !row.signal?.aborted
          && row.target instanceof Element && row.target.matches('[data-canvas-kind="workflow"]')).map(row => ({
            target: name(row.target), capture: row.capture,
            callback: typeof row.listener === 'function' ? row.listener.name : 'object',
          })),
        pendingFrames: [...frames].filter(([, owned]) => owned).map(([id]) => id),
        captures: captures.filter(row => row.target.hasPointerCapture(row.pointerId)).map(row => ({
          pointerId: row.pointerId, target: name(row.target), connected: row.target.isConnected,
        })),
        events: [...events], sentinelMoves, sentinelFrames,
        sentinelListener: entries.some(row => row.active && row.listener === sentinel),
      }),
    } })
  })
}

export async function pointerSnapshot(page: Page) { return page.evaluate(() => window.__pointerResources.snapshot()) }
