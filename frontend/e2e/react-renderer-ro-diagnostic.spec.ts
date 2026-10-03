import { expect, test } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { allCanvasReviewFixture } from './fixtures/allCanvasReview'
import { observeRendererROWeakly, rendererRODebugger } from './fixtures/rendererRODiagnostic'
import { allCanvasSnapshot, assertCanvasDisposed, observeAllCanvasResources, recordAllCanvas } from './fixtures/allCanvasResources'
import { observeCanvasRAFProvenance, stopCanvasRAFProvenance } from './fixtures/canvasRAFProvenance'

// Default mode is the patched strict gate; the explicit original mode preserves mechanism evidence.
test('renderer RO CDP cleanup-ref, extent-owner and WeakRef diagnostic（模拟数据）', async ({ page }, info) => {
  const original = process.env.CANVAS_RO_ORIGINAL_DIAGNOSTIC === '1'
  test.setTimeout(60_000)
  const fixture = await allCanvasReviewFixture(page, 'stages')
  await observeRendererROWeakly(page)
  await page.setViewportSize({ width: 1600, height: 1000 })
  const session = await page.context().newCDPSession(page)
  const debuggerProbe = await rendererRODebugger(session, original)
  try {
    await page.goto('/tasks')
    await page.getByRole('link', { name: fixture.task.title, exact: true }).click()
    await expect(page.locator('.readonly-diagram .react-flow__node')).toHaveCount(20)
    const mounted = await page.evaluate(() => window.__rendererROWeakDiagnostic.snapshot())
    expect(mounted.observers.length).toBeGreaterThan(0)
    // Real SPA owner unmount; no saved ElementHandle or console object can retain the renderer.
    await page.getByRole('button', { name: '全部任务', exact: true }).evaluate(element => (element as HTMLElement).click())
    await expect(page).toHaveURL('/tasks')
    await expect(page.locator('.readonly-diagram')).toHaveCount(0)
    const immediate = await page.evaluate(() => window.__rendererROWeakDiagnostic.snapshot())
    await debuggerProbe.stop()
    const gcSamples = []
    await session.send('HeapProfiler.enable')
    for (let cycle = 0; cycle < 3; cycle++) {
      await session.send('HeapProfiler.collectGarbage')
      gcSamples.push(await page.evaluate(() => window.__rendererROWeakDiagnostic.snapshot()))
    }
    await session.send('HeapProfiler.disable')
    const result = { mode: original ? 'original' : 'patched', sources: debuggerProbe.sources, pauses: debuggerProbe.pauses, debuggerErrors: debuggerProbe.errors,
      mounted, immediate, gcSamples, errors: fixture.errors, mutations: fixture.mutations,
      limits: 'No strong observer/Element/ElementHandle/console/remote-object registry. Debugger disabled and remote object group released before GC. WeakRef survival is an observational Chromium sample; it does not establish a retaining path, unbounded heap growth, or every-engine GC behavior. No GC result substitutes for explicit disconnect.' }
    const body = JSON.stringify(result, null, 2) + '\n'
    const evidence = process.env.CANVAS_RO_PATCH_EVIDENCE_DIR ?? 'test-results/react-renderer-ro-patch'
    await mkdir(evidence, { recursive: true })
    const label = `${original ? 'original' : 'patched'}-renderer-ro-cdp-weak`
    await writeFile(join(evidence, `${label}.json`), body)
    await info.attach(label, { body, contentType: 'application/json' })
    expect(debuggerProbe.errors).toEqual([])
    expect(debuggerProbe.sources).toHaveLength(1)
    const setup = debuggerProbe.pauses.filter(pause => pause.phase === 'setup')
    const cleanup = debuggerProbe.pauses.filter(pause => pause.phase === 'cleanup' && pause.location === '/tasks')
    expect(setup.length).toBeGreaterThan(0)
    expect(setup.every(pause => !pause.currentIsNull && pause.current?.className.includes('react-flow__renderer'))).toBe(true)
    expect(cleanup.length).toBeGreaterThan(0)
    expect(cleanup.every(pause => pause.currentIsNull && pause.setupTargets.length > 0)).toBe(true)
    expect(cleanup.every(pause => pause.setupTargets.every(target => !target.connected))).toBe(true)
    for (const pause of cleanup) expect(setup.some(row => row.observerId === pause.observerId
      && pause.setupTargets.some(target => target.id === row.current?.id))).toBe(true)
    const extentSetup = debuggerProbe.pauses.filter(pause => pause.phase === 'extent-setup')
    const extentDestroy = debuggerProbe.pauses.filter(pause => pause.phase === 'extent-destroy' && pause.location === '/tasks')
    expect(extentSetup.length).toBeGreaterThan(0)
    if (!original) expect(extentDestroy.length).toBeGreaterThan(0)
    for (const pause of extentDestroy) {
      expect(pause.currentRead).toBe('unavailable')
      expect(pause.setupTargets.length).toBeGreaterThan(0)
      expect(pause.setupTargets.every(target => !target.connected)).toBe(true)
      expect(extentSetup.some(row => row.observerId === pause.observerId
        && pause.setupTargets.some(target => target.id === row.current?.id))).toBe(true)
    }
    expect(setup.every(hook => extentSetup.some(extent => hook.current?.id === extent.current?.id
      && hook.observerId !== extent.observerId))).toBe(true)
    if (original) expect(immediate.observers.every(row => row.activeTargets.length > 0 && row.unobserved.length === 0 && row.disconnects === 0)).toBe(true)
    else {
      expect(immediate.observers.flatMap(row => row.activeTargets)).toEqual([])
      expect(immediate.observers.every(row => row.disconnects > 0)).toBe(true)
    }
    expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  } finally { await session.detach() }
})

test('strict cleanup negative control: unknown RAF and misleading callback name remain blocking', async ({ page }, info) => {
  await allCanvasReviewFixture(page, 'stages')
  await observeAllCanvasResources(page)
  await page.goto('/tasks')
  await observeCanvasRAFProvenance(page)
  try {
    const result = await page.evaluate(() => {
      window.__allCanvasResources.arm()
      const unknown = requestAnimationFrame(function unknownCanvasFrame() {})
      const misleading = requestAnimationFrame(function syncPosition() {})
      return { unknown, misleading, snapshot: window.__allCanvasResources.snapshot() }
    })
    await recordAllCanvas(info, 'unknown-frame-negative-control', result)
    expect(result.snapshot.pendingFrames).toEqual(expect.arrayContaining([result.unknown, result.misleading]))
    expect(result.snapshot.pendingCanvasFrames).toEqual(expect.arrayContaining([result.unknown, result.misleading]))
    for (const id of [result.unknown, result.misleading]) expect(result.snapshot.frameLedger.find(row => row.id === id)?.owner).toBeUndefined()
    expect(result.snapshot.roots).toEqual([]); expect(result.snapshot.listeners).toEqual([])
    expect(result.snapshot.instanceWheel).toEqual([]); expect(result.snapshot.captures).toEqual([]); expect(result.snapshot.observers).toEqual([])
    // This must throw because of pendingCanvasFrames, not another resource category.
    expect(() => assertCanvasDisposed(result.snapshot)).toThrow()
  } finally {
    const provenance = await stopCanvasRAFProvenance(page)
    await recordAllCanvas(info, 'negative-control-raf-provenance', provenance)
    expect(provenance.errors).toEqual([])
  }
})

test('strict cleanup negative control: detached renderer observer is never filtered', async ({ page }, info) => {
  await allCanvasReviewFixture(page, 'stages')
  await observeAllCanvasResources(page)
  await page.goto('/tasks')
  const detached = await page.evaluate(() => {
    const root = document.createElement('div'), renderer = document.createElement('div')
    root.className = 'readonly-diagram'; root.dataset.canvasRuntime = 'react'; root.dataset.canvasKind = 'negative-control'
    renderer.className = 'react-flow__renderer'; root.append(renderer); document.body.append(root)
    // This observer belongs to the negative control, not a production component.
    const observer = new ResizeObserver(() => {})
    try {
      observer.observe(renderer); root.remove()
      return window.__allCanvasResources.snapshot()
    } finally {
      // The immutable first snapshot is taken before cleanup of this test's own observer.
      observer.disconnect(); root.remove()
    }
  })
  await recordAllCanvas(info, 'detached-native-observer-negative-control', detached)
  expect(detached.roots).toEqual([])
  expect(detached.observers).toHaveLength(1)
  expect(detached.observers[0]!.targets).toHaveLength(1)
  expect(detached.observers[0]!.targets[0]!.connected).toBe(false)
  expect(detached.observerLedger[0]!.disconnects).toBe(0)
  expect(detached.pendingCanvasFrames).toEqual([])
  expect(() => assertCanvasDisposed(detached)).toThrow()
  expect((await allCanvasSnapshot(page)).observers).toEqual([])
})

test('strict cleanup negative control: reused retired Table callback needs fresh positive ownership', async ({ page }, info) => {
  await allCanvasReviewFixture(page, 'stages')
  await observeAllCanvasResources(page)
  await page.goto('/tasks'); await observeCanvasRAFProvenance(page)
  try {
    // Resize the real mounted Table to obtain an actual closure-proven classification.
    await page.evaluate(() => window.__allCanvasResources.arm())
    await page.setViewportSize({ width: 1400, height: 900 })
    await expect.poll(() => page.evaluate(() => !!window.__allCanvasResources.lastDestinationCallbackForNegativeControl())).toBe(true)
    const callbackId = await page.evaluate(() => {
      const holder = window as Window & { __negativeRecordedRAF?: FrameRequestCallback }
      holder.__negativeRecordedRAF = window.__allCanvasResources.lastDestinationCallbackForNegativeControl()!
      return window.__allCanvasResources.callbackIdentity(holder.__negativeRecordedRAF)
    })
    // Retire the real Table through its actual route owner; do not remove product DOM manually.
    await page.evaluate(() => window.__allCanvasResources.disarm())
    await page.locator('a[href="/roles"]').first().evaluate(element => (element as HTMLElement).click())
    await expect(page).toHaveURL('/roles'); await expect(page.locator('.el-table')).toHaveCount(0)
    await expect(page.locator('.role-item').first()).toBeVisible()
    const result = await page.evaluate(() => {
      const holder = window as Window & { __negativeRecordedRAF?: FrameRequestCallback }
      window.__allCanvasResources.arm()
      try {
        const id = requestAnimationFrame(holder.__negativeRecordedRAF!)
        return { id, snapshot: window.__allCanvasResources.snapshot() }
      } finally { delete holder.__negativeRecordedRAF }
    })
    await recordAllCanvas(info, 'retired-table-callback-negative-control', { callbackId, ...result })
    expect(result.snapshot.pendingFrames).toContain(result.id); expect(result.snapshot.pendingCanvasFrames).toContain(result.id)
    expect(result.snapshot.externalPendingFrames).not.toContain(result.id)
    expect(result.snapshot.frameLedger.find(row => row.id === result.id)?.owner).toBeUndefined()
    expect(result.snapshot.roots).toEqual([]); expect(result.snapshot.observers).toEqual([])
    expect(result.snapshot.listeners).toEqual([]); expect(result.snapshot.instanceWheel).toEqual([]); expect(result.snapshot.captures).toEqual([])
    expect(() => assertCanvasDisposed(result.snapshot)).toThrow()
  } finally {
    const provenance = await stopCanvasRAFProvenance(page)
    await recordAllCanvas(info, 'retired-callback-raf-provenance', provenance)
    expect(provenance.classifications.some(row => row.owner?.callbackId === row.callbackId)).toBe(true)
    expect(provenance.classifications.some(row => row.owner === null)).toBe(true)
    expect(provenance.errors).toEqual([])
  }
})
