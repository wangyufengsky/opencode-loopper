import { expect, type Page, type TestInfo } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

export const allCanvasEvidence = process.env.CANVAS_ALL_CLEANUP_EVIDENCE_DIR ?? 'test-results/react-all-canvas-cleanup'
const roots = '.readonly-diagram[data-canvas-runtime="react"], .ppt-canvas-wrap[data-canvas-runtime="react"], .react-mermaid-diagram[data-canvas-runtime="react"]'
export type CanvasFrameOwner = {
  kind: 'destination-react-fixture'; instanceId: number; targetId: number; callbackId: number;
  location: string; connected: boolean; className: string; refsAgree: boolean;
  closureSource: { url: string; sha256: string }
}
export type AllCanvasSnapshot = {
  documentIdentity: string
  location: string
  roots: { id: number; kind: string | null }[]
  listeners: { target: string; targetId: number; type: string; capture: boolean; callbackId: number; allocationStack: string }[]
  instanceWheel: { targetId: number; callbackId: number; capture: boolean }[]
  pendingFrames: number[]
  pendingCanvasFrames: number[]
  externalPendingFrames: number[]
  frameLedger: { id: number; active: boolean; callback: string; stack: string; observerId?: number; rootIds: number[]; owner?: CanvasFrameOwner }[]
  captures: { targetId: number; target: string; pointerId: number; connected: boolean }[]
  observers: { observerId: number; kind: 'resize' | 'intersection'; targets: { targetId: number; target: string; connected: boolean }[] }[]
  observerLedger: { observerId: number; kind: 'resize' | 'intersection'; observed: number[]; unobserved: number[]; disconnects: number; activeTargets: number[] }[]
  events: { type: string; target: string; targetId: number; trusted: boolean; pointerId?: number; pointerType?: string; clientX?: number; clientY?: number }[]
  sentinel: { listener: boolean; moves: number; frames: number }
}
declare global { interface Window { __allCanvasResources: {
  arm(): void; disarm(): void; snapshot(): AllCanvasSnapshot
  isArmed(): boolean
  callbackIdentity(callback: FrameRequestCallback): number
  lastDestinationCallbackForNegativeControl(): FrameRequestCallback | undefined
  identifyDestinationInstance(callback: FrameRequestCallback, table: unknown, expectedCallbackId: number,
    closureSource: CanvasFrameOwner['closureSource']): CanvasFrameOwner | null
} } }

/** Transparent observation only: exact identities, native capture and native RO lifecycle. */
export async function observeAllCanvasResources(page: Page) {
  await page.addInitScript(({ rootSelector }) => {
    const add = EventTarget.prototype.addEventListener, remove = EventTarget.prototype.removeEventListener
    const request = window.requestAnimationFrame.bind(window), cancel = window.cancelAnimationFrame.bind(window)
    const NativeObserver = window.ResizeObserver, NativeIntersection = window.IntersectionObserver
    const relevant = (target: EventTarget) => target === window || target === document
      || target instanceof Element && (!!target.closest(rootSelector) || target.matches('figure[data-mermaid-source], figure[data-w3-mermaid]'))
    const label = (target: EventTarget | null) => target === window ? 'window' : target === document ? 'document'
      : target instanceof Element ? `${target.tagName.toLowerCase()}.${target.getAttribute('class') ?? ''}` : 'other'
    const ids = new WeakMap<object, number>(); let nextId = 1
    const identity = (value: object | null) => { if (!value) return 0; if (!ids.has(value)) ids.set(value, nextId++); return ids.get(value)! }
    const captureFlag = (options?: boolean | AddEventListenerOptions | EventListenerOptions) => typeof options === 'boolean' ? options : !!options?.capture
    const types = new Set(['pointermove', 'pointerup', 'pointercancel', 'lostpointercapture', 'blur',
      'mousemove', 'mouseup', 'touchmove', 'touchend', 'touchcancel', 'dragstart', 'selectstart', 'wheel'])
    const listeners: { target: EventTarget; type: string; listener: EventListenerOrEventListenerObject;
      capture: boolean; owned: boolean; active: boolean; signal?: AbortSignal; allocationStack: string }[] = []
    const frames = new Map<number, boolean>()
    const frameLedger: (AllCanvasSnapshot['frameLedger'][number] & { owned: boolean })[] = []
    const callbackOwners = new WeakMap<FrameRequestCallback, CanvasFrameOwner>()
    let lastDestinationCallback: WeakRef<FrameRequestCallback> | undefined
    let currentObserver: ResizeObserver | IntersectionObserver | undefined
    const captures: { target: Element; pointerId: number }[] = []
    const observers: { observer: ResizeObserver | IntersectionObserver; kind: 'resize' | 'intersection'; targets: Map<Element, boolean>;
      observed: Element[]; unobserved: Element[]; disconnects: number }[] = []
    const events: AllCanvasSnapshot['events'] = []
    let armed = false, moves = 0, ticks = 0
    EventTarget.prototype.addEventListener = function(type, listener, options) {
      const result = add.call(this, type, listener, options)
      if (listener && relevant(this) && types.has(type) && !listeners.some(row => row.active && row.target === this
        && row.type === type && row.listener === listener && row.capture === captureFlag(options))) {
        listeners.push({ target: this, type, listener, capture: captureFlag(options), owned: armed, active: true, allocationStack: new Error('Observed listener registration').stack ?? '',
          signal: typeof options === 'object' ? options.signal : undefined })
      }
      return result
    }
    EventTarget.prototype.removeEventListener = function(type, listener, options) {
      const result = remove.call(this, type, listener, options)
      for (const row of listeners) if (row.active && row.target === this && row.type === type
        && row.listener === listener && row.capture === captureFlag(options)) row.active = false
      return result
    }
    window.requestAnimationFrame = callback => {
      // Provenance is per registration: a retired instance callback cannot reuse an old positive tag.
      callbackOwners.delete(callback)
      const id = request(time => {
        frames.delete(id); const row = frameLedger.find(frame => frame.id === id); if (row) row.active = false
        callback(time)
      })
      frames.set(id, armed)
      frameLedger.push({ id, owned: armed, active: true, allocationStack: new Error('Observed listener registration').stack ?? '', callback: callback.name, stack: new Error('Observed RAF registration').stack ?? '',
        observerId: currentObserver ? identity(currentObserver) : undefined, rootIds: [...document.querySelectorAll(rootSelector)].map(identity),
        owner: callbackOwners.get(callback) })
      return id
    }
    window.cancelAnimationFrame = id => { frames.delete(id); const row = frameLedger.find(frame => frame.id === id); if (row) row.active = false; cancel(id) }
    const set = Element.prototype.setPointerCapture
    Element.prototype.setPointerCapture = function(pointerId) {
      const result = set.call(this, pointerId)
      if (relevant(this) && !captures.some(row => row.target === this && row.pointerId === pointerId)) captures.push({ target: this, pointerId })
      return result
    }
    if (NativeObserver) window.ResizeObserver = class extends NativeObserver {
      constructor(callback: ResizeObserverCallback) {
        super((entries, observer) => {
          const previous = currentObserver; currentObserver = observer
          try { callback.call(observer, entries, observer) } finally { currentObserver = previous }
        })
        observers.push({ observer: this, kind: 'resize', targets: new Map(), observed: [], unobserved: [], disconnects: 0 })
      }
      observe(target: Element, options?: ResizeObserverOptions) {
        super.observe(target, options)
        const row = observers.find(row => row.observer === this)!
        row.targets.set(target, relevant(target)); if (relevant(target)) row.observed.push(target)
      }
      unobserve(target: Element) { super.unobserve(target); const row = observers.find(row => row.observer === this)!; if (row.targets.get(target)) row.unobserved.push(target); row.targets.delete(target) }
      disconnect() { super.disconnect(); const row = observers.find(row => row.observer === this)!; row.disconnects++; row.targets.clear() }
    }
    if (NativeIntersection) window.IntersectionObserver = class extends NativeIntersection {
      constructor(callback: IntersectionObserverCallback, options?: IntersectionObserverInit) {
        super((entries, observer) => {
          const previous = currentObserver; currentObserver = observer
          try { callback.call(observer, entries, observer) } finally { currentObserver = previous }
        }, options)
        observers.push({ observer: this, kind: 'intersection', targets: new Map(), observed: [], unobserved: [], disconnects: 0 })
      }
      observe(target: Element) { super.observe(target); const row = observers.find(row => row.observer === this)!; row.targets.set(target, relevant(target)); if (relevant(target)) row.observed.push(target) }
      unobserve(target: Element) { super.unobserve(target); const row = observers.find(row => row.observer === this)!; if (row.targets.get(target)) row.unobserved.push(target); row.targets.delete(target) }
      disconnect() { super.disconnect(); const row = observers.find(row => row.observer === this)!; row.disconnects++; row.targets.clear() }
    }
    for (const type of ['pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'lostpointercapture', 'mousedown', 'mousemove', 'mouseup']) {
      add.call(document, type, ((event: PointerEvent) => {
        if (armed) events.push({ type, target: label(event.target), targetId: identity(event.target), trusted: event.isTrusted,
          pointerId: event.pointerId, pointerType: event.pointerType, clientX: event.clientX, clientY: event.clientY })
      }) as EventListener, true)
    }
    add.call(window, 'blur', event => {
      if (armed) events.push({ type: 'blur', target: label(event.target), targetId: identity(event.target), trusted: event.isTrusted })
    }, true)
    const sentinel = () => { moves++ }
    window.addEventListener('pointermove', sentinel)
    function tick() { ticks++; request(tick) }
    request(tick)
    const documentIdentity = crypto.randomUUID()
    window.__allCanvasResources = {
      callbackIdentity: callback => identity(callback),
      lastDestinationCallbackForNegativeControl: () => lastDestinationCallback?.deref(),
      identifyDestinationInstance: (callback, value, expectedCallbackId, closureSource) => {
        callbackOwners.delete(callback)
        // CDP supplies the actual lexical React fixture instance and the same callback identity.
        // No callback name, allocation stack or absence of canvas roots grants ownership.
        if (!value || typeof value !== 'object' || identity(callback) !== expectedCallbackId) return null
        const instance = value as { element?: unknown; active?: boolean }
        const target = instance.element, wrapper = instance.element
        if (!(target instanceof Element) || target !== wrapper || !target.matches('[data-raf-owner-fixture="true"]') || instance.active !== true || !target.isConnected
          || target.closest(rootSelector) || location.pathname !== '/tasks'
          || !/^https?:\/\/[^/]+\/e2e\/fixtures\/rafOwner\.tsx(?:\?|$)/.test(closureSource.url)
          || !/^[a-f0-9]{64}$/.test(closureSource.sha256)) return null
        const owner: CanvasFrameOwner = { kind: 'destination-react-fixture', instanceId: identity(instance), targetId: identity(target),
          callbackId: expectedCallbackId, location: location.pathname, connected: target.isConnected,
          className: target.getAttribute('class') ?? '', refsAgree: target === wrapper, closureSource }
        callbackOwners.set(callback, owner)
        lastDestinationCallback = new WeakRef(callback)
        return owner
      },
      // Preserve the resource ledger across cycles. arm only clears the event window.
      arm: () => { armed = true; events.length = 0 }, disarm: () => { armed = false },
      isArmed: () => armed,
      snapshot: () => ({ documentIdentity, location: location.pathname,
        roots: [...document.querySelectorAll(rootSelector)].map(element => ({ id: identity(element), kind: element.getAttribute('data-canvas-kind') })),
        listeners: listeners.filter(row => row.active && row.owned && !row.signal?.aborted).map(row => ({
          target: label(row.target), targetId: identity(row.target), type: row.type, capture: row.capture, callbackId: identity(row.listener), allocationStack: row.allocationStack,
        })),
        instanceWheel: listeners.filter(row => row.active && row.type === 'wheel' && !row.signal?.aborted
          && row.target instanceof Element && row.target.matches(rootSelector)).map(row => ({ targetId: identity(row.target), callbackId: identity(row.listener), capture: row.capture })),
        pendingFrames: [...frames].filter(([, owned]) => owned).map(([id]) => id),
        // Raw IDs remain intact. Only a positively identified other instance is outside this gate.
        // Unknown and canvas callbacks remain in pendingCanvasFrames and fail the same strict [].
        pendingCanvasFrames: [...frames].filter(([id, owned]) => owned && !frameLedger.find(row => row.id === id)?.owner).map(([id]) => id),
        externalPendingFrames: [...frames].filter(([id, owned]) => owned && !!frameLedger.find(row => row.id === id)?.owner).map(([id]) => id),
        frameLedger: frameLedger.filter(frame => frame.owned).map(({ owned: _owned, ...frame }) => ({ ...frame })),
        captures: captures.filter(row => row.target.hasPointerCapture(row.pointerId)).map(row => ({
          targetId: identity(row.target), target: label(row.target), pointerId: row.pointerId, connected: row.target.isConnected,
        })),
        observers: observers.filter(row => [...row.targets.values()].some(Boolean)).map(row => ({ observerId: identity(row.observer), kind: row.kind,
          targets: [...row.targets].filter(([, owned]) => owned).map(([target]) => ({ targetId: identity(target), target: label(target), connected: target.isConnected })),
        })),
        observerLedger: observers.filter(row => row.observed.length).map(row => ({ observerId: identity(row.observer), kind: row.kind,
          observed: row.observed.map(identity), unobserved: row.unobserved.map(identity), disconnects: row.disconnects,
          activeTargets: [...row.targets].filter(([, owned]) => owned).map(([target]) => identity(target)),
        })),
        events: [...events], sentinel: { listener: listeners.some(row => row.active && row.listener === sentinel), moves, frames: ticks },
      }),
    }
  }, { rootSelector: roots })
}

export const allCanvasSnapshot = (page: Page) => page.evaluate(() => window.__allCanvasResources.snapshot())
export function assertCanvasDisposed(snapshot: AllCanvasSnapshot, continueAfterFramesAndObservers = false) {
  expect(snapshot.roots).toEqual([]); expect(snapshot.listeners).toEqual([]); expect(snapshot.instanceWheel).toEqual([])
  // Soft checks keep the identical strict [] expectation and keep the test red;
  // only repeated probes opt in so later cycles can expose cumulative ownership.
  if (continueAfterFramesAndObservers) expect.soft(snapshot.pendingCanvasFrames).toEqual([])
  else expect(snapshot.pendingCanvasFrames).toEqual([])
  expect(snapshot.captures).toEqual([])
  if (continueAfterFramesAndObservers) expect.soft(snapshot.observers).toEqual([])
  else expect(snapshot.observers).toEqual([])
  expect(snapshot.sentinel.listener).toBe(true)
}
export function assertNoCleanupInput(before: AllCanvasSnapshot, after: AllCanvasSnapshot) {
  expect(after.events.slice(before.events.length).filter(event => ['pointermove', 'pointerup', 'pointercancel', 'mousemove', 'mouseup'].includes(event.type)
    || event.type === 'blur' && event.target === 'window')).toEqual([])
}
export async function recordAllCanvas(info: TestInfo, label: string, value: unknown) {
  await mkdir(allCanvasEvidence, { recursive: true })
  const body = JSON.stringify(value, null, 2) + '\n'
  await info.attach(label, { body, contentType: 'application/json' })
  await writeFile(join(allCanvasEvidence, `${info.testId.replace(/[^a-z0-9_-]/gi, '_')}-${label}.json`), body)
}
