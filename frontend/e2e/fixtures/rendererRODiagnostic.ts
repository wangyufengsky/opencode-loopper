import type { CDPSession, Page } from '@playwright/test'
import { createHash } from 'node:crypto'

type TargetInfo = { id: number; className: string; connected: boolean; canvasKind: string | null }
export type RendererROWeakSnapshot = {
  location: string
  roots: number
  observers: { id: number; alive: boolean; observed: number[]; unobserved: number[]; disconnects: number;
    activeTargets: number[]; targets: { id: number; alive: boolean; connected: boolean | null }[] }[]
}
type ROPhase = 'setup' | 'cleanup' | 'extent-setup' | 'extent-destroy'
export type RendererROPause = {
  phase: ROPhase; location: string; observerId: number; currentIsNull: boolean;
  currentRead: 'react-ref' | 'system-element' | 'unavailable'
  current: TargetInfo | null; setupTargets: { id: number; alive: boolean; connected: boolean | null }[]
}
declare global {
  interface Window {
    __rendererROWeakDiagnostic: {
      inspect(current: Element | null, observer: ResizeObserver, phase: ROPhase): RendererROPause
      snapshot(): RendererROWeakSnapshot
    }
  }
}

/** Separate from the strict resource ledger: no observer, callback or Element is held strongly. */
export async function observeRendererROWeakly(page: Page) {
  await page.addInitScript(() => {
    const Native = window.ResizeObserver, ids = new WeakMap<object, number>()
    let nextId = 1
    const identity = (value: object) => {
      if (!ids.has(value)) ids.set(value, nextId++)
      return ids.get(value)!
    }
    const rows: { id: number; observer: WeakRef<ResizeObserver>; targets: Map<number, WeakRef<Element>>;
      observed: number[]; unobserved: number[]; disconnects: number; active: Set<number> }[] = []
    const info = (target: Element): TargetInfo => ({ id: identity(target), className: target.getAttribute('class') ?? '',
      connected: target.isConnected, canvasKind: target.closest('[data-canvas-kind]')?.getAttribute('data-canvas-kind') ?? null })
    window.ResizeObserver = class extends Native {
      constructor(callback: ResizeObserverCallback) {
        super((entries, observer) => callback.call(observer, entries, observer))
        rows.push({ id: identity(this), observer: new WeakRef(this), targets: new Map(), observed: [], unobserved: [], disconnects: 0, active: new Set() })
      }
      observe(target: Element, options?: ResizeObserverOptions) {
        super.observe(target, options)
        if (!target.matches('.react-flow__renderer')) return
        const row = rows.find(row => row.id === identity(this))!, id = identity(target)
        row.targets.set(id, new WeakRef(target)); row.observed.push(id); row.active.add(id)
      }
      unobserve(target: Element) {
        super.unobserve(target)
        const row = rows.find(row => row.id === identity(this))!, id = identity(target)
        if (row.targets.has(id)) row.unobserved.push(id)
        row.active.delete(id)
      }
      disconnect() {
        super.disconnect()
        const row = rows.find(row => row.id === identity(this))!
        row.disconnects++; row.active.clear()
      }
    }
    const targetStatus = (targets: Map<number, WeakRef<Element>>) => [...targets].map(([id, reference]) => {
      const target = reference.deref()
      return { id, alive: !!target, connected: target?.isConnected ?? null }
    })
    window.__rendererROWeakDiagnostic = {
      inspect: (current, observer, phase) => ({ phase, location: location.pathname, observerId: identity(observer),
        currentIsNull: current === null, current: current ? info(current) : null,
        currentRead: phase === 'extent-destroy' ? 'unavailable' : phase === 'extent-setup' ? 'system-element' : 'react-ref',
        setupTargets: targetStatus(rows.find(row => row.id === identity(observer))!.targets) }),
      snapshot: () => ({ location: location.pathname, roots: document.querySelectorAll('.readonly-diagram').length,
        observers: rows.filter(row => row.observed.length).map(row => ({ id: row.id, alive: !!row.observer.deref(),
          observed: [...row.observed], unobserved: [...row.unobserved], disconnects: row.disconnects,
          activeTargets: [...row.active], targets: targetStatus(row.targets) })) }),
    }
  })
}

type Paused = { reason: string; data?: { scriptId?: string }; hitBreakpoints?: string[];
  callFrames: { callFrameId: string; location: { scriptId: string; lineNumber: number; columnNumber?: number } }[] }

/** Source breakpoints read the original lexical ref; no source editing or variable mutation. */
export async function rendererRODebugger(session: CDPSession, original: boolean) {
  const sources: { url: string; sha256: string; hook: string; extent: string; lines: { phase: ROPhase; line: number }[] }[] = []
  const pauses: RendererROPause[] = [], errors: string[] = [], scripts = new Map<string, string>()
  const breakpoints = new Map<string, ROPhase>()
  let installed = false, pending = Promise.resolve()
  await session.send('Debugger.enable')
  session.on('Debugger.scriptParsed', event => scripts.set(event.scriptId, event.url))
  const instrumentation = await session.send('Debugger.setInstrumentationBreakpoint', { instrumentation: 'beforeScriptExecution' })
  const onPaused = (event: Paused) => {
    pending = pending.then(async () => {
      try {
        const hit = event.hitBreakpoints?.find(id => breakpoints.has(id))
        if (hit) {
          const phase = breakpoints.get(hit)!
          const result = await session.send('Debugger.evaluateOnCallFrame', {
            callFrameId: event.callFrames[0]!.callFrameId, returnByValue: true, objectGroup: 'renderer-ro-diagnostic',
            expression: phase.startsWith('extent-')
              ? `window.__rendererROWeakDiagnostic.inspect(${phase === 'extent-destroy' ? 'null' : 'domNode'}, extentResizeObserver, '${phase}')`
              : `window.__rendererROWeakDiagnostic.inspect(domNode.current, resizeObserver, '${phase}')`,
          })
          if (result.exceptionDetails) throw new Error(result.exceptionDetails.text)
          pauses.push(result.result.value as RendererROPause)
        } else if (!installed && event.reason === 'instrumentation') {
          const scriptId = event.data?.scriptId ?? event.callFrames[0]?.location.scriptId
          if (scriptId) {
            const { scriptSource } = await session.send('Debugger.getScriptSource', { scriptId })
            const start = scriptSource.indexOf('function useResizeHandler(domNode)')
            if (start >= 0) {
              const setup = scriptSource.indexOf('resizeObserver.observe(domNode.current)', start)
              const cleanup = scriptSource.indexOf(original ? 'if (resizeObserver && domNode.current)' : 'resizeObserver.disconnect()', setup)
              const extentStart = scriptSource.indexOf('function XYPanZoom(')
              const extentObserve = scriptSource.indexOf('.observe(domNode)', extentStart)
              const internalDestroy = scriptSource.indexOf('function destroy()', extentStart)
              const publicDestroy = scriptSource.indexOf('destroy: () => {', internalDestroy)
              const extentCleanup = original ? scriptSource.indexOf('d3ZoomInstance.on(', internalDestroy)
                : scriptSource.indexOf('.disconnect()', publicDestroy)
              if (setup < 0 || cleanup < 0 || extentStart < 0 || extentObserve < 0 || internalDestroy < 0
                || !original && publicDestroy < 0 || extentCleanup < 0) throw new Error(`Unrecognized ${original ? 'original' : 'patched'} renderer/extent cleanup source; fail closed`)
              const line = (offset: number) => scriptSource.slice(0, offset).split('\n').length - 1
              // Original lexical destroy references neither value; V8 may optimize them out.
              // The original extent setup + native lifecycle ledger already establish ownership.
              const locations: [ROPhase, number][] = [['setup', setup], ['cleanup', cleanup], ['extent-setup', extentObserve]]
              if (!original) locations.push(['extent-destroy', extentCleanup])
              for (const [phase, offset] of locations) {
                const result = await session.send('Debugger.setBreakpoint', { location: { scriptId, lineNumber: line(offset), columnNumber: 0 } })
                breakpoints.set(result.breakpointId, phase)
              }
              sources.push({ url: scripts.get(scriptId) ?? '', sha256: createHash('sha256').update(scriptSource).digest('hex'),
                hook: scriptSource.slice(start, cleanup + 180), extent: scriptSource.slice(extentStart, extentObserve + 100),
                lines: locations.map(([phase, offset]) => ({ phase, line: line(offset) + 1 })) })
              installed = true
              await session.send('Debugger.removeBreakpoint', { breakpointId: instrumentation.breakpointId })
            }
          }
        }
      } catch (error) { errors.push(String(error)) }
      finally { await session.send('Debugger.resume').catch(error => errors.push(String(error))) }
    })
  }
  session.on('Debugger.paused', onPaused)
  return {
    sources, pauses, errors,
    async stop() {
      await pending
      session.off('Debugger.paused', onPaused)
      await session.send('Debugger.removeBreakpoint', { breakpointId: instrumentation.breakpointId }).catch(() => {})
      for (const breakpointId of breakpoints.keys()) await session.send('Debugger.removeBreakpoint', { breakpointId })
      await session.send('Runtime.releaseObjectGroup', { objectGroup: 'renderer-ro-diagnostic' })
      await session.send('Debugger.disable')
    },
  }
}
