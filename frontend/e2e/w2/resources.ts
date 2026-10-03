import type { Page } from '@playwright/test'
import { expect } from '@playwright/test'
import { readFile, readdir } from 'node:fs/promises'
import { createHash } from 'node:crypto'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
type ExternalTimerOwner = {
  kind: 'AntModalModule'; targetId: number; targetKind: 'HTMLHtmlElement'; callbackId: number
  type: 'click'; capture: true; registeredRootIds: number[]; callbackSource: string
  installedSourceSha256: string; bundleSha256: string; registrationStack: string
}
export type W2ResourceSnapshot = {
  documentIdentity: string; path: string; rootId: number; rootConnected: boolean
  listeners: { targetId: number; targetKind: string; callbackId: number; type: string; capture: boolean; stack: string }[]
  observers: { observerId: number; targets: { targetId: number; connected: boolean }[] }[]
  captures: { targetId: number; pointerId: number; connected: boolean }[]
  pendingFrames: { id: number; callbackId: number; stack: string }[]
  timers: { id: number; repeating: boolean; stack: string }[]
  allTimers: { id: number; repeating: boolean; stack: string; rootIds: number[]; armed: boolean; externalOwner: ExternalTimerOwner | null }[]
  sentinel: { callbackId: number; active: boolean; calls: number }
  events: { type: string; target: string; trusted: boolean }[]
}
declare global { interface Window { __w2Resources: { begin(): void; snapshot(): W2ResourceSnapshot } } }
/** Exact lifecycle observation. The ledger retains targets for auditing, so this is not a heap/GC probe. */
export async function observeW2Resources(page: Page) {
  // Pin the actual public module callback and built bytes. A new/unrecognized
  // callback is never classified by its minified name or allocation stack.
  const frontend = fileURLToPath(new URL('../../', import.meta.url))
  const source = await readFile(join(frontend, 'node_modules/antd/es/modal/Modal.js'))
  const installedSourceSha256 = createHash('sha256').update(source).digest('hex')
  expect(installedSourceSha256).toBe('882d5d7880d5915ab4fef19b0b7f1dc3e0a5047b6e029b73555dce6d065e1f32')
  const assets = join(frontend, 'dist/assets'), matches: { callbackSource: string; bundleSha256: string; installedSourceSha256: string }[] = []
  const pattern = /(?:const|let|var)\s+([\w$]+)=(([\w$]+)=>\{([\w$]+)=\{x:\3\.pageX,y:\3\.pageY\},setTimeout\(\(\)=>\{\4=null\},100\)\});[\w$]+\(\)&&document\.documentElement\.addEventListener\("click",\1,!0\)/g
  for (const file of await readdir(assets)) if (file.endsWith('.js')) {
    const bytes = await readFile(join(assets, file)), text = bytes.toString()
    for (const match of text.matchAll(pattern)) matches.push({ callbackSource: match[2]!, bundleSha256: createHash('sha256').update(bytes).digest('hex'), installedSourceSha256 })
  }
  expect(matches).toHaveLength(1)
  await page.addInitScript(antModule => {
    const selector = '[data-react-page]', ids = new WeakMap<object, number>(); let next = 1, armed = false, root: Element | null = null
    const id = (object: object) => { if (!ids.has(object)) ids.set(object, next++); return ids.get(object)! }
    const roots = () => [...document.querySelectorAll(selector)].map(id)
    const add = EventTarget.prototype.addEventListener, remove = EventTarget.prototype.removeEventListener
    let executingExternal: ExternalTimerOwner | null = null
    const moduleRows: { target: EventTarget; callback: EventListener; wrapped: EventListener; capture: boolean; active: boolean; signal?: AbortSignal }[] = []
    const listenerRows: { target: EventTarget; callback: EventListenerOrEventListenerObject; type: string; capture: boolean; active: boolean; rootIds: number[]; bridgeHost: Element | null; stack: string; signal?: AbortSignal }[] = []
    const flag = (options?: boolean | AddEventListenerOptions | EventListenerOptions) => typeof options === 'boolean' ? options : !!options?.capture
    const types = new Set(['beforeunload', 'pointerdown', 'pointermove', 'pointerup', 'pointercancel', 'lostpointercapture', 'mousedown', 'mousemove', 'mouseup', 'touchstart', 'touchmove', 'touchend', 'touchcancel', 'wheel', 'blur', 'focus', 'focusin', 'focusout', 'keydown', 'keyup', 'dragstart', 'selectstart', 'change'])
    EventTarget.prototype.addEventListener = function(type, callback, options) {
      const stack = new Error('listener registration').stack ?? ''
      const existingModule = type === 'click' ? moduleRows.find(row => row.active && !row.signal?.aborted && row.target === this && row.callback === callback && row.capture === flag(options)) : undefined
      if (existingModule) { add.call(this, type, existingModule.wrapped, options); return }
      // Only this exact source-verified application/module callback is external.
      // React delegation and every unknown callback keep conservative root ownership.
      if (this === document.documentElement && type === 'click' && flag(options) && typeof callback === 'function' && roots().length === 0 && Function.prototype.toString.call(callback) === antModule.callbackSource) {
        const owner: ExternalTimerOwner = { kind: 'AntModalModule', targetId: id(this), targetKind: 'HTMLHtmlElement', callbackId: id(callback), type: 'click', capture: true, registeredRootIds: roots(), callbackSource: antModule.callbackSource, installedSourceSha256: antModule.installedSourceSha256, bundleSha256: antModule.bundleSha256, registrationStack: stack }
        const once = typeof options === 'object' && !!options.once
        const row = { target: this, callback, capture: flag(options), active: true, signal: typeof options === 'object' ? options.signal : undefined, wrapped: null! as EventListener }
        row.wrapped = function(event) {
          const previous = executingExternal; executingExternal = owner
          if (once) row.active = false
          try { callback.call(this, event) } finally { executingExternal = previous }
        }
        moduleRows.push(row); add.call(this, type, row.wrapped, options); return
      }
      add.call(this, type, callback, options)
      if (callback && types.has(type) && (this === window || this === document || typeof MediaQueryList !== 'undefined' && this instanceof MediaQueryList || this instanceof Element && this.closest(selector))
        && !listenerRows.some(row => row.active && !row.signal?.aborted && row.target === this && row.callback === callback && row.type === type && row.capture === flag(options))) {
        // Global effects already installed on the real root count even before begin().
        // The bridge beforeunload effect is registered onMounted before its async
        // React page exists; retain that actual host identity, never a stack-name exemption.
        listenerRows.push({ target: this, callback, type, capture: flag(options), active: true, rootIds: this instanceof Element ? [id(this.closest(selector)!)] : roots(), bridgeHost: type === 'beforeunload' ? document.querySelector('[data-w2-route-bridge]') : null, stack, signal: typeof options === 'object' ? options.signal : undefined })
      }
    }
    EventTarget.prototype.removeEventListener = function(type, callback, options) { const module = type === 'click' ? moduleRows.find(row => row.active && !row.signal?.aborted && row.target === this && row.callback === callback && row.capture === flag(options)) : undefined; remove.call(this, type, module?.wrapped ?? callback, options); if (module) module.active = false; listenerRows.forEach(row => { if (row.target === this && row.callback === callback && row.type === type && row.capture === flag(options)) row.active = false }) }
    let sentinelCalls = 0
    const sentinelCallback = () => { sentinelCalls++ }
    // Test-owned, mounted before any page: its registration has no W2 root owner.
    window.addEventListener('keydown', sentinelCallback)
    const observers: { object: ResizeObserver; targets: Map<Element, number> }[] = [], NativeRO = window.ResizeObserver
    window.ResizeObserver = class extends NativeRO { constructor(callback: ResizeObserverCallback) { super(callback); observers.push({ object: this, targets: new Map() }) } observe(target: Element, options?: ResizeObserverOptions) { super.observe(target, options); const owner = target.closest(selector); if (owner) observers.find(row => row.object === this)!.targets.set(target, id(owner)) } unobserve(target: Element) { super.unobserve(target); observers.find(row => row.object === this)!.targets.delete(target) } disconnect() { super.disconnect(); observers.find(row => row.object === this)!.targets.clear() } }
    const request = window.requestAnimationFrame.bind(window), cancel = window.cancelAnimationFrame.bind(window)
    const frames = new Map<number, { callbackId: number; stack: string; rootIds: number[]; armed: boolean }>()
    window.requestAnimationFrame = callback => { const owner = { callbackId: id(callback), stack: new Error('RAF registration').stack ?? '', rootIds: roots(), armed }; const frame = request(time => { frames.delete(frame); callback(time) }); frames.set(frame, owner); return frame }
    window.cancelAnimationFrame = frame => { frames.delete(frame); cancel(frame) }
    const setTimeoutNative = window.setTimeout.bind(window), clearTimeoutNative = window.clearTimeout.bind(window), intervalNative = window.setInterval.bind(window), clearIntervalNative = window.clearInterval.bind(window)
    const timers = new Map<number, { repeating: boolean; rootIds: number[]; armed: boolean; externalOwner: ExternalTimerOwner | null; stack: string }>()
    window.setTimeout = ((callback: TimerHandler, delay?: number, ...args: unknown[]) => { const metadata = { repeating: false, rootIds: executingExternal ? [...executingExternal.registeredRootIds] : roots(), armed, externalOwner: executingExternal, stack: new Error('timer registration').stack ?? '' }; const timer = setTimeoutNative(() => { timers.delete(timer); const previous = executingExternal; executingExternal = metadata.externalOwner; try { if (typeof callback === 'function') callback.apply(window, args); else Function(callback)() } finally { executingExternal = previous } }, delay); timers.set(timer, metadata); return timer }) as typeof setTimeout
    window.clearTimeout = timer => { if (timer !== undefined) timers.delete(timer); clearTimeoutNative(timer) }
    window.setInterval = ((callback: TimerHandler, delay?: number, ...args: unknown[]) => { const timer = intervalNative(callback, delay, ...args); timers.set(timer, { repeating: true, rootIds: roots(), armed, externalOwner: null, stack: new Error('interval registration').stack ?? '' }); return timer }) as typeof setInterval
    window.clearInterval = timer => { if (timer !== undefined) timers.delete(timer); clearIntervalNative(timer) }
    const captured: { target: Element; pointerId: number }[] = [], setCapture = Element.prototype.setPointerCapture
    Element.prototype.setPointerCapture = function(pointerId) { setCapture.call(this, pointerId); if (this.closest(selector)) captured.push({ target: this, pointerId }) }
    const events: W2ResourceSnapshot['events'] = []
    for (const type of ['pointermove', 'pointerup', 'pointercancel', 'mouseup', 'mousemove', 'blur']) add.call(window, type, event => { if (armed) events.push({ type, target: event.target === window ? 'window' : 'element', trusted: event.isTrusted }) }, true)
    const documentIdentity = crypto.randomUUID()
    window.__w2Resources = {
      begin() { root = document.querySelector(selector); if (!root) throw new Error('Real W2 production root not mounted'); for (const row of listenerRows) if (row.bridgeHost?.contains(root) && !row.rootIds.includes(id(root))) row.rootIds.push(id(root)); armed = true; events.length = 0 },
      snapshot() { const rootId = root ? id(root) : 0; return { documentIdentity, path: location.pathname, rootId, rootConnected: !!root?.isConnected,
        listeners: listenerRows.filter(row => row.active && !row.signal?.aborted && row.rootIds.includes(rootId)).map(row => ({ targetId: id(row.target), targetKind: row.target === window ? 'window' : row.target === document ? 'document' : typeof MediaQueryList !== 'undefined' && row.target instanceof MediaQueryList ? 'MediaQueryList' : 'element', callbackId: id(row.callback), type: row.type, capture: row.capture, stack: row.stack })),
        observers: observers.map(row => ({ observerId: id(row.object), targets: [...row.targets].filter(([, owner]) => owner === rootId).map(([target]) => ({ targetId: id(target), connected: target.isConnected })) })).filter(row => row.targets.length),
        captures: captured.filter(row => !row.target.isConnected || row.target.closest(selector) === root).filter(row => row.target.hasPointerCapture(row.pointerId)).map(row => ({ targetId: id(row.target), pointerId: row.pointerId, connected: row.target.isConnected })),
        pendingFrames: [...frames].filter(([, owner]) => owner.rootIds.includes(rootId) || owner.armed).map(([id, owner]) => ({ id, callbackId: owner.callbackId, stack: owner.stack })),
        timers: [...timers].filter(([, owner]) => owner.rootIds.includes(rootId) || owner.armed && !owner.externalOwner).map(([id, owner]) => ({ id, repeating: owner.repeating, stack: owner.stack })), allTimers: [...timers].map(([id, owner]) => ({ id, ...owner })), sentinel: { callbackId: id(sentinelCallback), active: listenerRows.some(row => row.active && row.target === window && row.callback === sentinelCallback), calls: sentinelCalls }, events: [...events] } },
    }
  }, matches[0]!)
}
export async function immediateW2Exit(page: Page) {
  return page.evaluate(() => new Promise<W2ResourceSnapshot>(resolve => {
    const root = document.querySelector('[data-react-page]')!, observer = new MutationObserver(() => { if (!root.isConnected) { observer.disconnect(); resolve(window.__w2Resources.snapshot()) } })
    observer.observe(document.body, { childList: true, subtree: true })
    const link = document.querySelector<HTMLAnchorElement>('.app-sidebar a[href="/template-tasks"]'); if (!link) throw new Error('Real SPA exit not available'); link.click()
  }))
}
export function assertW2Disposed(before: W2ResourceSnapshot, after: W2ResourceSnapshot) {
  expect(after.documentIdentity).toBe(before.documentIdentity); expect(after.rootConnected).toBe(false)
  expect(after.sentinel.callbackId).toBe(before.sentinel.callbackId); expect(after.sentinel.active).toBe(true)
  expect(after.listeners).toEqual([]); expect(after.observers).toEqual([]); expect(after.pendingFrames).toEqual([]); expect(after.captures).toEqual([]); expect(after.timers).toEqual([])
  expect(after.events.slice(before.events.length).filter(event => ['pointermove', 'pointerup', 'pointercancel', 'mousemove', 'mouseup'].includes(event.type) || event.type === 'blur' && event.target === 'window')).toEqual([])
}
