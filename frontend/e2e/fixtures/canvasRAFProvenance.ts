import type { CDPSession, Page } from '@playwright/test'
import { createHash } from 'node:crypto'
import type { CanvasFrameOwner } from './allCanvasResources'

type Paused = { reason: string; hitBreakpoints?: string[]; callFrames: { callFrameId: string; functionName: string;
  location: { scriptId: string; lineNumber: number; columnNumber?: number } }[] }
type ProtocolAudit = { sequence: number; event: string; token?: number; reason?: string; callbackId?: number }
const probes = new WeakMap<Page, { session: CDPSession; stop(): Promise<void>; errors: string[];
  classifications: { callbackId: number; owner: CanvasFrameOwner | null }[]; protocol: ProtocolAudit[] }>()

/** The only paused registrations are candidate fixture frames; identity is checked from actual closures. */
export async function observeCanvasRAFProvenance(page: Page) {
  if (probes.has(page)) return
  const session = await page.context().newCDPSession(page)
  const scripts = new Map<string, string>(), sources = new Map<string, { url: string; sha256: string; recognized: boolean }>()
  const errors: string[] = [], classifications: { callbackId: number; owner: CanvasFrameOwner | null }[] = []
  const protocol: ProtocolAudit[] = []
  let activePause: number | undefined, nextPause = 0
  const audit = (event: Omit<ProtocolAudit, 'sequence'>) => protocol.push({ sequence: protocol.length + 1, ...event })
  const parsed = (event: { scriptId: string; url: string }) => scripts.set(event.scriptId, event.url)
  session.on('Debugger.scriptParsed', parsed)
  await session.send('Debugger.enable')
  let registration: { scriptId: string; lineNumber: number } | undefined
  for (const [scriptId, url] of scripts) {
    if (url && !url.includes('playwright')) continue
    const { scriptSource } = await session.send('Debugger.getScriptSource', { scriptId })
    if (!scriptSource.includes('Observed RAF registration') || !scriptSource.includes('frameLedger')) continue
    const offset = scriptSource.indexOf('const id = request(')
    if (offset < 0) continue
    registration = { scriptId, lineNumber: scriptSource.slice(0, offset).split('\n').length - 1 }
    break
  }
  if (!registration) {
    await session.detach()
    throw new Error('Canvas RAF registration source not recognized; fail closed')
  }
  // The condition only bounds debugger overhead. It never classifies a callback as external.
  const breakpoint = await session.send('Debugger.setBreakpoint', { location: { ...registration, columnNumber: 0 },
    condition: 'window.__allCanvasResources.isArmed() && callback.name === "syncPosition"' })
  let pending = Promise.resolve()
  const resumed = () => { audit({ event: 'browser-resumed', token: activePause }); activePause = undefined }
  session.on('Debugger.resumed', resumed)
  const paused = (event: Paused) => {
    const token = ++nextPause
    activePause = token; audit({ event: 'browser-paused', token, reason: event.reason })
    pending = pending.then(async () => {
      try {
        if (activePause !== token) { audit({ event: 'pause-ended-before-inspection', token }); return }
        if (!event.hitBreakpoints?.includes(breakpoint.breakpointId)) throw new Error('Unexpected canvas provenance debugger pause')
        const inspected = await session.send('Debugger.evaluateOnCallFrame', { callFrameId: event.callFrames[0]!.callFrameId,
          expression: 'window.__allCanvasResources.callbackIdentity(callback)', returnByValue: true, objectGroup: 'canvas-raf-provenance' })
        if (inspected.exceptionDetails || typeof inspected.result.value !== 'number') throw new Error('Cannot read actual RAF callback identity')
        const callbackId = inspected.result.value as number
        audit({ event: 'callback-inspected', token, callbackId })
        let owner: CanvasFrameOwner | null = null
        for (const frame of event.callFrames.slice(1)) {
          const url = scripts.get(frame.location.scriptId) ?? ''
          if (!/\/e2e\/fixtures\/rafOwner\.tsx(?:\?|$)/.test(url)) continue
          let source = sources.get(frame.location.scriptId)
          if (!source) {
            const { scriptSource } = await session.send('Debugger.getScriptSource', { scriptId: frame.location.scriptId })
            source = { url, sha256: createHash('sha256').update(scriptSource).digest('hex'),
              recognized: scriptSource.includes('W7_OTHER_INSTANCE_RAF') && scriptSource.includes('requestAnimationFrame(syncPosition)')
                && scriptSource.includes('function startOwnerFrame(owner)') }
            sources.set(frame.location.scriptId, source)
          }
          if (!source.recognized) continue
          const observed = await session.send('Debugger.evaluateOnCallFrame', { callFrameId: frame.callFrameId, returnByValue: true,
            objectGroup: 'canvas-raf-provenance', expression: `(typeof owner === 'object' && typeof syncPosition === 'function')
              ? window.__allCanvasResources.identifyDestinationInstance(syncPosition, owner, ${callbackId}, ${JSON.stringify({ url: source.url, sha256: source.sha256 })}) : null` })
          if (observed.exceptionDetails) throw new Error(observed.exceptionDetails.text)
          owner = observed.result.value as CanvasFrameOwner | null
          if (owner) break
        }
        classifications.push({ callbackId, owner })
        audit({ event: owner ? 'fixture-instance-confirmed' : 'unclassified', token, callbackId })
      } catch (error) { errors.push(String(error)) }
      finally {
        if (activePause === token) {
          audit({ event: 'own-resume-request', token })
          await session.send('Debugger.resume').catch(error => errors.push(String(error)))
        } else audit({ event: 'resume-not-required-for-ended-pause', token })
      }
    })
  }
  session.on('Debugger.paused', paused)
  const probe = { session, errors, classifications, protocol, async stop() {
    audit({ event: 'stop-remove-own-breakpoint' })
    await session.send('Debugger.removeBreakpoint', { breakpointId: breakpoint.breakpointId })
    let observed: Promise<void>
    do { observed = pending; await observed } while (observed !== pending)
    session.off('Debugger.paused', paused); session.off('Debugger.scriptParsed', parsed)
    await session.send('Runtime.releaseObjectGroup', { objectGroup: 'canvas-raf-provenance' })
    await session.send('Debugger.disable'); session.off('Debugger.resumed', resumed); await session.detach()
    audit({ event: 'stopped' })
  } }
  probes.set(page, probe)
}

export async function stopCanvasRAFProvenance(page: Page) {
  const probe = probes.get(page)
  if (!probe) return { errors: [], classifications: [], protocol: [] }
  await probe.stop(); probes.delete(page)
  return { errors: [...probe.errors], classifications: [...probe.classifications], protocol: [...probe.protocol] }
}
