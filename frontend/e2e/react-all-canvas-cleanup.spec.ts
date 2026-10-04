import { expect, test, type Locator, type Page } from '@playwright/test'
import { allCanvasReviewFixture, type ReadonlyKind } from './fixtures/allCanvasReview'
import { allCanvasEvidence, allCanvasSnapshot, assertCanvasDisposed, assertNoCleanupInput, observeAllCanvasResources, recordAllCanvas } from './fixtures/allCanvasResources'
import { observeCanvasRAFProvenance, stopCanvasRAFProvenance } from './fixtures/canvasRAFProvenance'
import { SKIN_STORAGE_KEY } from '../src/themes/registry'

test.afterEach(async ({ page }, info) => {
  const provenance = await stopCanvasRAFProvenance(page)
  await recordAllCanvas(info, 'raf-instance-provenance', provenance)
  expect(provenance.errors).toEqual([])
})

const viewport = (canvas: Locator) => canvas.locator('.react-flow__viewport').evaluate(element => {
  const matrix = new DOMMatrixReadOnly(getComputedStyle(element).transform)
  return { x: matrix.m41, y: matrix.m42, zoom: matrix.m11 }
})
async function panePoint(canvas: Locator) {
  await canvas.scrollIntoViewIfNeeded()
  return canvas.evaluate(element => {
    const pane = element.querySelector('.react-flow__pane')!, rect = pane.getBoundingClientRect()
    for (const y of [.1, .9, .5, .7]) for (const x of [25, 60, 120, 200]) {
      const at = { x: rect.x + x, y: rect.y + rect.height * y }
      if (at.x + 105 < rect.right && document.elementFromPoint(at.x, at.y) === pane
        && document.elementFromPoint(at.x + 105, at.y + 20) === pane) return at
    }
    throw new Error('No unobstructed background pan start/end in simulated readonly diagram')
  })
}

for (const skin of ['spdb', 'tech-blue', 'github-white']) {
  for (const kind of ['stages', 'template-progress', 'roles'] as const) {
  for (const width of [390,1440]) {
    test(`${skin} readonly ${kind} ${width}px 正常pan缩放键盘定位与文字选择保持订阅（模拟数据）`, async ({ page }, info) => {
      const fixture = await allCanvasReviewFixture(page, kind)
      await observeAllCanvasResources(page)
      await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin })
      await page.setViewportSize({ width, height: width===390?844:1000 }); await page.emulateMedia({ reducedMotion: 'reduce' })
      await page.goto('/tasks')
      await observeCanvasRAFProvenance(page)
      const canvas = await enter(page, kind, fixture.task.title), nodes = canvas.locator('.react-flow__node')
      const originalStreams = await page.evaluate(() => window.__allCanvasStreams)
      const at = await panePoint(canvas), before = await viewport(canvas)
      await page.evaluate(() => window.__allCanvasResources.arm())
      await page.mouse.move(at.x, at.y); await page.mouse.down(); await page.mouse.move(at.x + 105, at.y + 20)
      await expect(canvas).toHaveAttribute('data-pointer-gesture', 'pan')
      await expect.poll(async () => (await viewport(canvas)).x - before.x).toBeCloseTo(105, 2)
      expect((await viewport(canvas)).y - before.y).toBeCloseTo(20, 2)
      expect((await allCanvasSnapshot(page)).captures).toHaveLength(1)
      await page.mouse.up()
      const ended = await allCanvasSnapshot(page)
      expect(ended.captures).toEqual([]); expect(ended.listeners).toEqual([]); expect(ended.pendingFrames).toEqual([])
      await page.evaluate(() => window.__allCanvasResources.disarm())
      const panResult = await viewport(canvas)
      await canvas.getByRole('button', { name: '放大流程图', exact: true }).click()
      await expect.poll(async () => (await viewport(canvas)).zoom).toBeCloseTo(Math.min(2, panResult.zoom * 1.2), 3)
      await canvas.getByRole('button', { name: '缩小流程图', exact: true }).click()
      await expect.poll(async () => (await viewport(canvas)).zoom).toBeCloseTo(panResult.zoom, 3)
      const wheelAt = await panePoint(canvas), beforeWheel = await viewport(canvas)
      await page.mouse.move(wheelAt.x, wheelAt.y); await page.keyboard.down('Control'); await page.mouse.wheel(0, -60); await page.keyboard.up('Control')
      await expect.poll(async () => (await viewport(canvas)).zoom).toBeGreaterThan(beforeWheel.zoom)
      const nodePosition = await nodes.last().getAttribute('style')
      await canvas.getByRole('button', { name: '适应流程图', exact: true }).click()
      // Restore readable text through the actual public controls; long sequences
      // cannot fit at the original minimum zoom and still keep labels readable.
      for (let step = 0; step < 12 && (await viewport(canvas)).zoom < 1; step++) {
        const zoom = (await viewport(canvas)).zoom
        await canvas.getByRole('button', { name: '放大流程图', exact: true }).click()
        await expect.poll(async () => (await viewport(canvas)).zoom).toBeGreaterThan(zoom)
      }
      await nodes.first().focus()
      const count = kind === 'roles' ? 3 : 20
      for (let index = 1; index < count; index++) { await page.keyboard.press('Tab'); await expect(nodes.nth(index)).toBeFocused() }
      const last = nodes.last()
      // The old public autoPanOnNodeFocus contract reveals completely offscreen
      // nodes. Move this actual node fully outside before testing that contract.
      const bounds = (await canvas.boundingBox())!, lastBox = (await last.boundingBox())!, focusAt = await panePoint(canvas)
      const shift = Math.ceil(bounds.x + bounds.width + 30 - lastBox.x)
      const beforeFocusPan = await viewport(canvas)
      await page.mouse.move(focusAt.x, focusAt.y); await page.mouse.down(); await page.mouse.move(focusAt.x + shift, focusAt.y); await page.mouse.up()
      await expect.poll(async () => (await viewport(canvas)).x - beforeFocusPan.x).toBeCloseTo(shift, 2)
      await expect.poll(async () => (await last.boundingBox())!.x >= bounds.x + bounds.width).toBe(true)
      // Controls follow the readonly nodes in the actual DOM tab sequence.
      // Shift+Tab enters the last node without first focusing its neighbor.
      await canvas.getByRole('button', { name: '放大流程图', exact: true }).focus()
      await page.keyboard.press('Shift+Tab'); await expect(last).toBeFocused()
      await expect.poll(async () => {
        const box = (await last.boundingBox())!, bounds = (await canvas.boundingBox())!
        return box.x + box.width / 2 >= bounds.x && box.x + box.width / 2 <= bounds.x + bounds.width
      }).toBe(true)
      await expect(last).toHaveCSS('outline-style', 'solid')
      for (const key of ['ArrowRight', 'Enter', 'Space', 'Delete']) await page.keyboard.press(key)
      expect(await last.getAttribute('style')).toBe(nodePosition)
      await expect(nodes).toHaveCount(count); await expect(canvas.locator('.react-flow__node.selected')).toHaveCount(0)
      const text = last.locator(kind === 'stages' ? '.phase-objective p' : 'strong')
      // Space may scroll the outer page through its ordinary browser default.
      // Restore the real visible target, then prove both physical hit points.
      await text.scrollIntoViewIfNeeded()
      const range = await text.evaluate(element => {
        const range = document.createRange(); range.setStart(element.firstChild!, 0); range.setEnd(element.firstChild!, 4)
        const rect = range.getBoundingClientRect(); return { x: rect.x, y: rect.y, width: rect.width, height: rect.height, expected: range.toString() }
      })
      expect(await text.evaluate((element, at) => element.contains(document.elementFromPoint(at.x + .1, at.y + at.height / 2))
        && element.contains(document.elementFromPoint(at.x + at.width - .1, at.y + at.height / 2)), range)).toBe(true)
      const beforeSelection = await viewport(canvas)
      await page.mouse.move(range.x + .1, range.y + range.height / 2); await page.mouse.down()
      await page.mouse.move(range.x + range.width - .1, range.y + range.height / 2, { steps: 8 }); await page.mouse.up()
      await expect.poll(() => page.evaluate(() => getSelection()?.toString())).toBe(range.expected)
      expect(await viewport(canvas)).toEqual(beforeSelection)
      expect(await page.locator('body').evaluate(element => element.scrollWidth <= innerWidth + 1)).toBe(true)
      expect(await page.evaluate(() => window.__allCanvasStreams)).toEqual(originalStreams)
      // Three representative screenshots cover all skins and all shared entries.
      if (kind === ({ spdb: 'stages', 'tech-blue': 'template-progress', 'github-white': 'roles' } as Record<string, string>)[skin]) {
        await canvas.getByRole('button', { name: '放大流程图', exact: true }).focus()
        await page.keyboard.press('Shift+Tab'); await expect(last).toBeFocused()
        await recordAllCanvas(info, `${skin}-${kind}-normal`, { ended, viewport: await viewport(canvas), streams: originalStreams })
        await page.screenshot({ path: `${allCanvasEvidence}/${skin}-${kind}-${width}-keyboard.png`, fullPage: true })
      }
      await page.evaluate(() => window.__allCanvasResources.arm())
      await leave(page, kind); await expect(canvas).toHaveCount(0)
      const immediate = await allCanvasSnapshot(page)
      await recordAllCanvas(info, `${skin}-${kind}-normal-exit`, { ended, immediate, viewportBeforeSelection: beforeSelection,
        streamsBefore: originalStreams, streamsAfter: await page.evaluate(() => window.__allCanvasStreams), errors: fixture.errors, mutations: fixture.mutations })
      assertCanvasDisposed(immediate)
      expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
    })
  }
  }
}
async function enter(page: Page, kind: ReadonlyKind, title: string) {
  if (kind === 'roles') {
    await page.locator('a[href="/roles"]').first().evaluate(element => (element as HTMLElement).click())
    await page.getByRole('list',{name:'角色',exact:true}).getByRole('button').first().click()
  } else { await page.getByRole('link', { name: title, exact: true }).click();await page.getByRole('button',{name:'选择：执行进度',exact:true}).click() }
  const canvas = page.locator(`.readonly-diagram[data-canvas-kind="${kind}"]`)
  await expect(canvas.locator('.react-flow__node')).toHaveCount(kind === 'roles' ? 3 : 20)
  return canvas
}
async function leave(page: Page, kind: ReadonlyKind) {
  const control = kind === 'roles' ? page.locator('a[href="/tasks"]').first() : page.locator('.w2-heading a[href="/tasks"]')
  // Activate the real SPA navigation control without resetting the held physical pointer.
  await control.evaluate(element => (element as HTMLElement).click())
  await expect(page).toHaveURL('/tasks')
}

for (const kind of ['stages', 'template-progress', 'roles'] as const) {
  test(`readonly ${kind} background-pan 三次SPA退出首采样零监听RAF捕获RO（模拟数据）`, async ({ page }, info) => {
    const fixture = await allCanvasReviewFixture(page, kind)
    await observeAllCanvasResources(page)
    await page.setViewportSize({ width: 1600, height: 1000 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/tasks')
    await observeCanvasRAFProvenance(page)
    const identity = (await allCanvasSnapshot(page)).documentIdentity
    const rootIds = new Set<number>()
    for (let cycle = 0; cycle < 3; cycle++) {
      const canvas = await enter(page, kind, fixture.task.title)
      const at = await panePoint(canvas), before = await viewport(canvas)
      const streams = await page.evaluate(() => window.__allCanvasStreams)
      const oldRoot = await canvas.elementHandle()
      // Wait only for mounting work before arm; cleanup gets a single, unpolled snapshot.
      await expect.poll(async () => (await allCanvasSnapshot(page)).pendingFrames.length).toBe(0)
      await page.evaluate(() => window.__allCanvasResources.arm())
      await page.mouse.move(at.x, at.y); await page.mouse.down(); await page.mouse.move(at.x + 105, at.y + 20)
      await expect(canvas).toHaveAttribute('data-pointer-gesture', 'pan')
      await expect.poll(async () => (await viewport(canvas)).x - before.x).toBeCloseTo(105, 2)
      expect((await viewport(canvas)).y - before.y).toBeCloseTo(20, 2)
      const during = await allCanvasSnapshot(page)
      expect(during.captures).toHaveLength(1); expect(during.listeners).toHaveLength(5); expect(during.instanceWheel).toHaveLength(during.roots.length)
      expect(during.events.some(event => event.type === 'pointerdown' && event.trusted && event.pointerType === 'mouse')).toBe(true)
      expect(during.events.some(event => event.type === 'pointermove' && event.trusted && Math.abs(event.clientX! - at.x - 105) < .01)).toBe(true)
      expect(during.observers.length).toBeGreaterThan(0)
      expect(rootIds.has(during.roots[0]!.id)).toBe(false); rootIds.add(during.roots[0]!.id)
      await leave(page, kind)
      await expect(canvas).toHaveCount(0)
      const immediate = await allCanvasSnapshot(page)
      await recordAllCanvas(info, `${kind}-${cycle}`, { during, immediate, streamsBefore: streams,
        streamsAfter: await page.evaluate(() => window.__allCanvasStreams), errors: fixture.errors, mutations: fixture.mutations })
      assertCanvasDisposed(immediate, true); assertNoCleanupInput(during, immediate)
      expect(immediate.documentIdentity).toBe(identity)
      expect(await oldRoot!.evaluate(element => element.isConnected)).toBe(false); await oldRoot!.dispose()
      expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
      const afterStreams = await page.evaluate(() => window.__allCanvasStreams)
      expect(afterStreams.opened).toEqual(streams.opened)
      expect(afterStreams.closed).toEqual(kind === 'roles' ? streams.closed : [...streams.closed, `/api/tasks/${fixture.id}/events`])
      // Natural input device reset happens only AFTER the strict first-snapshot gate.
      await page.evaluate(() => window.__allCanvasResources.disarm()); await page.mouse.up()
      await page.mouse.move(7, 9)
      // Device input can be coalesced after the CDP acknowledgement. This health check is
      // strictly after the immutable disposal/no-cleanup-input gate and cannot repair it.
      await expect.poll(async () => (await allCanvasSnapshot(page)).sentinel.moves).toBeGreaterThan(immediate.sentinel.moves)
      await expect.poll(async () => (await allCanvasSnapshot(page)).sentinel.frames).toBeGreaterThan(immediate.sentinel.frames)
      const unaffected = await allCanvasSnapshot(page)
      expect(unaffected.sentinel.moves).toBeGreaterThan(immediate.sentinel.moves)
      expect(unaffected.sentinel.frames).toBeGreaterThan(immediate.sentinel.frames)
      expect(unaffected.sentinel.listener).toBe(true)
    }
  })
}

test('readonly stages Chromium双指缩放后单指续pan活动SPA退出立即清理（模拟数据）', async ({ page }, info) => {
  const fixture = await allCanvasReviewFixture(page, 'stages'); await observeAllCanvasResources(page)
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/tasks')
  await observeCanvasRAFProvenance(page)
  const canvas = await enter(page, 'stages', fixture.task.title), at = await panePoint(canvas)
  const second = { x: at.x + 150, y: at.y }
  expect(await canvas.evaluate((element, point) => document.elementFromPoint(point.x, point.y) === element.querySelector('.react-flow__pane'), second)).toBe(true)
  const before = await viewport(canvas), cdp = await page.context().newCDPSession(page)
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 })
  await page.evaluate(() => window.__allCanvasResources.arm())
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...at, id: 1 }, { ...second, id: 2 }] })
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: at.x - 30, y: at.y, id: 1 }, { x: second.x + 30, y: second.y, id: 2 }] })
  await expect(canvas).toHaveAttribute('data-pointer-gesture', 'pan')
  await expect.poll(async () => (await viewport(canvas)).zoom).toBeCloseTo(before.zoom * 1.4, 3)
  const two = await allCanvasSnapshot(page)
  expect(two.captures).toHaveLength(2)
  const remaining = two.events.find(event => event.type === 'pointerdown' && event.pointerType === 'touch' && Math.abs(event.clientX! - second.x) < .01)!.pointerId!
  expect(two.events.filter(event => event.type === 'pointerdown' && event.trusted && event.pointerType === 'touch')).toHaveLength(2)
  // Chromium's touchEnd list contains the changed/released first point.
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [{ x: at.x - 30, y: at.y, id: 1 }] })
  await expect.poll(async () => (await allCanvasSnapshot(page)).captures.map(capture => capture.pointerId)).toEqual([remaining])
  const one = await viewport(canvas), move = { x: second.x + 55, y: second.y + 15, id: 2 }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [move] })
  // Wait for this single existing CDP input; no extra input may repair the movement.
  await expect.poll(async () => (await allCanvasSnapshot(page)).events.some(event => event.type === 'pointermove' && event.trusted
    && event.pointerId === remaining && Math.abs(event.clientX! - move.x) < .01 && Math.abs(event.clientY! - move.y) < .01)).toBe(true)
  await expect.poll(async () => (await viewport(canvas)).x - one.x).toBeCloseTo(25, 2)
  expect((await viewport(canvas)).y - one.y).toBeCloseTo(15, 2)
  const during = await allCanvasSnapshot(page), viewportAfterMove = await viewport(canvas)
  await leave(page, 'stages'); await expect(canvas).toHaveCount(0)
  const immediate = await allCanvasSnapshot(page)
  await recordAllCanvas(info, 'readonly-pinch-partial-exit', { before, two, one, viewportAfterMove, remaining, during, immediate })
  assertCanvasDisposed(immediate); assertNoCleanupInput(during, immediate)
  expect(immediate.documentIdentity).toBe(during.documentIdentity); expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  await page.evaluate(() => window.__allCanvasResources.disarm())
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await cdp.detach()
})

test('readonly stages Chromium touchCancel回滚pan并移除活动资源（模拟数据）', async ({ page }, info) => {
  const fixture = await allCanvasReviewFixture(page, 'stages'); await observeAllCanvasResources(page)
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/tasks')
  await observeCanvasRAFProvenance(page)
  const canvas = await enter(page, 'stages', fixture.task.title), at = await panePoint(canvas), before = await viewport(canvas)
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 })
  await page.evaluate(() => window.__allCanvasResources.arm())
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...at, id: 1 }] })
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: at.x + 105, y: at.y + 20, id: 1 }] })
  await expect.poll(async () => (await viewport(canvas)).x - before.x).toBeCloseTo(105, 2)
  const during = await allCanvasSnapshot(page); expect(during.captures).toHaveLength(1)
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] })
  await expect(canvas).not.toHaveAttribute('data-pointer-gesture', 'pan')
  await expect.poll(() => viewport(canvas)).toEqual(before)
  const cancelled = await allCanvasSnapshot(page)
  expect(cancelled.events.some(event => event.type === 'pointercancel' && event.trusted && event.pointerType === 'touch')).toBe(true)
  expect(cancelled.listeners).toEqual([]); expect(cancelled.captures).toEqual([]); expect(cancelled.pendingFrames).toEqual([])
  await leave(page, 'stages'); await expect(canvas).toHaveCount(0)
  const immediate = await allCanvasSnapshot(page)
  await recordAllCanvas(info, 'readonly-touch-cancel', { before, during, cancelled, immediate })
  assertCanvasDisposed(immediate); expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  // touchCancel already ended the browser sequence; do not send another touchEnd.
  await cdp.detach()
})
