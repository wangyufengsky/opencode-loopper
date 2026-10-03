import { expect, test, type CDPSession, type Locator, type Page, type TestInfo } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { canvasReviewFixture } from './fixtures/canvasReview'
import { observePointerResources, pointerSnapshot, type PointerResourceSnapshot } from './fixtures/pointerResources'
import { SKIN_STORAGE_KEY } from '../src/themes/registry'

const evidence = process.env.CANVAS_POINTER_EVIDENCE_DIR ?? 'test-results/react-canvas-pointer'
const selector = '[data-canvas-runtime="react"][data-canvas-kind="workflow"]'
type Gesture = 'connection-pressed' | 'connection-mouse' | 'connection-touch' | 'node-mouse' | 'pane-mouse'
type Fixture = Awaited<ReturnType<typeof canvasReviewFixture>>

async function record(info: TestInfo, label: string, value: unknown) {
  await mkdir(evidence, { recursive: true })
  const body = JSON.stringify(value, null, 2) + '\n'
  await info.attach(label, { body, contentType: 'application/json' })
  await writeFile(join(evidence, `${info.testId.replace(/[^a-z0-9_-]/gi, '_')}-${label}.json`), body)
}
async function setup(page: Page, zoom = 1, skin = 'spdb', width = 1600) {
  const fixture = await canvasReviewFixture(page)
  fixture.flow.layout.zoom = zoom
  await observePointerResources(page)
  await page.addInitScript(({ key, skin }) => localStorage.setItem(key, skin), { key: SKIN_STORAGE_KEY, skin })
  await page.setViewportSize({ width, height: width < 720 ? 844 : 1000 })
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await page.goto('/workflows')
  await page.evaluate(() => { document.documentElement.dataset.pointerDocument = crypto.randomUUID() })
  return fixture
}
async function enter(page: Page, fixture: Fixture) {
  await page.getByRole('link', { name: fixture.flow.title, exact: true }).click()
  const canvas = page.locator(selector)
  await expect(canvas.locator('.react-flow__node')).toHaveCount(3)
  const node = canvas.locator('[data-node-id="scope"]')
  await node.click()
  await expect(node).toHaveClass(/selected/)
  return { canvas, node }
}
async function point(element: Locator, body = false) {
  const box = (await element.boundingBox())!
  return { x: box.x + (body ? 80 : box.width / 2), y: box.y + (body ? 25 : box.height / 2) }
}
async function position(canvas: Locator) {
  return canvas.locator('.react-flow__node[data-id="scope"]').evaluate(element => {
    const matrix = new DOMMatrixReadOnly(getComputedStyle(element).transform)
    return { x: matrix.m41, y: matrix.m42 }
  })
}
async function viewport(canvas: Locator) {
  return canvas.locator('.react-flow__viewport').evaluate(element => {
    const matrix = new DOMMatrixReadOnly(getComputedStyle(element).transform)
    return { x: matrix.m41, y: matrix.m42, zoom: matrix.m11 }
  })
}
async function blankPane(canvas: Locator) {
  return canvas.evaluate(element => {
    const pane = element.querySelector('.react-flow__pane')!, bounds = pane.getBoundingClientRect()
    for (const y of [.75, .55, .85]) for (const x of [80, 150, 220, 300]) {
      const first = { x: bounds.x + x, y: bounds.y + bounds.height * y }
      const second = { x: first.x + 150, y: first.y }
      const blank = (point: { x: number; y: number }) => document.elementFromPoint(point.x, point.y) === pane
      if (blank(first) && blank(second)) return { first, second }
    }
    throw new Error('Fixture has no two visible blank pane points')
  })
}
async function touch(page: Page) {
  const cdp = await page.context().newCDPSession(page)
  await cdp.send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 2 })
  return cdp
}
async function start(page: Page, canvas: Locator, gesture: Gesture, asTouch = false) {
  const node = canvas.locator('[data-node-id="scope"]')
  const source = gesture === 'pane-mouse' ? canvas.locator('.react-flow__pane') : gesture === 'node-mouse' ? node : node.locator('.react-flow__handle.source')
  const origin = gesture === 'pane-mouse' ? (await blankPane(canvas)).first : await point(source, gesture === 'node-mouse')
  expect(await source.evaluate((element, at) => element.contains(document.elementFromPoint(at.x, at.y)), origin)).toBe(true)
  const before = await position(canvas)
  const beforeViewport = await viewport(canvas)
  await page.bringToFront()
  const cdp = asTouch || gesture === 'connection-touch' ? await touch(page) : undefined
  await page.evaluate(() => window.__pointerResources.arm())
  if (cdp) {
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...origin, id: 1 }] })
    await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: origin.x + 105, y: origin.y + 20, id: 1 }] })
  } else {
    await page.mouse.move(origin.x, origin.y); await page.mouse.down()
    if (gesture !== 'connection-pressed') await page.mouse.move(origin.x + 105, origin.y + 20)
  }
  await expect(canvas).toHaveAttribute('data-pointer-gesture', gesture === 'pane-mouse' ? 'pan' : gesture === 'node-mouse' ? 'drag' : 'connect')
  if (gesture.startsWith('connection') && gesture !== 'connection-pressed') await expect(canvas.locator('.workflow-connection-preview')).toBeVisible()
  if (gesture !== 'connection-pressed') {
    // CDP can acknowledge a touch sample before Chromium dispatches its
    // coalesced PointerEvent. Observe that existing input; do not send another.
    await expect.poll(async () => (await pointerSnapshot(page)).events.some(event => event.type === 'pointermove'
      && event.trusted && Math.abs(event.clientX! - origin.x - 105) < .01 && Math.abs(event.clientY! - origin.y - 20) < .01)).toBe(true)
  }
  const during = await pointerSnapshot(page)
  expect(during.captures.length).toBeGreaterThan(0)
  expect(during.events.some(event => event.type === 'pointerdown' && event.trusted && event.pointerType === (cdp ? 'touch' : 'mouse'))).toBe(true)
  if (gesture !== 'connection-pressed') expect(during.events.some(event => event.type === 'pointermove' && event.trusted)).toBe(true)
  return { source, origin, before, beforeViewport, cdp, during }
}
function clean(snapshot: PointerResourceSnapshot) {
  expect(snapshot.listeners).toEqual([])
  expect(snapshot.pendingFrames).toEqual([])
  expect(snapshot.captures).toEqual([])
  expect(snapshot.sentinelListener).toBe(true)
}
function noCleanupInput(before: PointerResourceSnapshot, after: PointerResourceSnapshot) {
  const later = after.events.slice(before.events.length)
  expect(later.filter(event => ['pointermove', 'pointerup', 'pointercancel'].includes(event.type)
    || event.type === 'blur' && event.target === 'window')).toEqual([])
}
async function release(page: Page, cdp?: CDPSession) {
  if (cdp) { await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] }); await cdp.detach() }
  else await page.mouse.up()
}
async function unaffected(page: Page, before: PointerResourceSnapshot) {
  await page.evaluate(() => window.__pointerResources.disarm())
  await page.mouse.move(7, 9)
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  const after = await pointerSnapshot(page)
  expect(after.sentinelMoves).toBeGreaterThan(before.sentinelMoves)
  expect(after.sentinelFrames).toBeGreaterThan(before.sentinelFrames)
  expect(after.sentinelListener).toBe(true)
}
async function middle(page: Page, canvas: Locator, target: 'node' | 'edge') {
  const source = canvas.locator(target === 'node' ? '[data-node-id="scope"]' : '.workflow-wire-hit').first()
  const origin = target === 'node' ? await point(source, true) : await source.evaluate(element => {
    const path = element as SVGGeometryElement, at = path.getPointAtLength(path.getTotalLength() / 2)
    const screen = new DOMPoint(at.x, at.y).matrixTransform(path.getScreenCTM()!)
    return { x: screen.x, y: screen.y }
  })
  expect(await source.evaluate((element, at) => element.contains(document.elementFromPoint(at.x, at.y)), origin)).toBe(true)
  const before = await viewport(canvas), nodeBefore = await position(canvas)
  await page.bringToFront(); await page.evaluate(() => window.__pointerResources.arm())
  await page.mouse.move(origin.x, origin.y); await page.mouse.down({ button: 'middle' })
  await page.mouse.move(origin.x + 105, origin.y + 20)
  await expect(canvas).toHaveAttribute('data-pointer-gesture', 'pan')
  await expect.poll(async () => (await viewport(canvas)).x - before.x).toBeCloseTo(105, 2)
  expect((await viewport(canvas)).y - before.y).toBeCloseTo(20, 2)
  const during = await pointerSnapshot(page)
  expect(during.captures).toHaveLength(1)
  expect(during.events.some(event => event.type === 'pointerdown' && event.pointerType === 'mouse' && event.trusted)).toBe(true)
  expect(during.wheelListeners).toHaveLength(1)
  return { during, before, nodeBefore }
}

for (const gesture of ['connection-pressed', 'connection-mouse', 'connection-touch', 'node-mouse', 'pane-mouse'] as const) {
  test(`Pointer Events ${gesture} 三次SPA退出立即清零监听、RAF和捕获（模拟数据）`, async ({ page }, info) => {
    const fixture = await setup(page)
    const identity = await page.locator('html').getAttribute('data-pointer-document')
    for (let cycle = 0; cycle < 3; cycle++) {
      const { canvas } = await enter(page, fixture)
      const active = await start(page, canvas, gesture)
      if (gesture === 'node-mouse') {
        const moved = await position(canvas)
        expect(moved.x - active.before.x).toBeCloseTo(105, 2); expect(moved.y - active.before.y).toBeCloseTo(20, 2)
      }
      if (gesture === 'pane-mouse') expect(await viewport(canvas)).not.toEqual(active.beforeViewport)
      const oldRoot = await canvas.elementHandle()
      // DOM activation navigates while the actual mouse/touch remains held.
      // No later move/release/cancel can repair the gate's first snapshot.
      await page.getByRole('link', { name: '返回流程库', exact: true }).evaluate(element => (element as HTMLElement).click())
      await expect(page).toHaveURL('/workflows'); await expect(canvas).toHaveCount(0)
      expect(await oldRoot!.evaluate(element => element.isConnected)).toBe(false)
      const immediate = await pointerSnapshot(page)
      await record(info, `${gesture}-${cycle}`, { during: active.during, immediate, mutations: fixture.mutations, errors: fixture.errors })
      expect(immediate.roots).toBe(0); clean(immediate); noCleanupInput(active.during, immediate)
      expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
      expect(await page.locator('html').getAttribute('data-pointer-document')).toBe(identity)
      await oldRoot!.dispose()
      // Reset the real input device only after the strict zero-resource assertion.
      await page.evaluate(() => window.__pointerResources.disarm())
      await release(page, active.cdp)
      await unaffected(page, immediate)
    }
  })
}

for (const kind of ['connect', 'drag'] as const) {
  for (const interrupt of ['pointercancel', 'lostcapture', 'blur'] as const) {
    test(`${kind} ${interrupt} 取消真实指针手势且立即清理（模拟数据）`, async ({ page, context }, info) => {
      const fixture = await setup(page), { canvas } = await enter(page, fixture)
      const focusCdp = interrupt === 'blur' ? await context.newCDPSession(page) : undefined
      // Playwright normally emulates focus in every tab. Turn that public CDP
      // setting off before observing this tab's browser-generated trusted blur.
      await focusCdp?.send('Emulation.setFocusEmulationEnabled', { enabled: false })
      const active = await start(page, canvas, kind === 'drag' ? 'node-mouse' : 'connection-mouse', interrupt === 'pointercancel')
      if (interrupt === 'pointercancel') {
        await active.cdp!.send('Input.dispatchTouchEvent', { type: 'touchCancel', touchPoints: [] })
      } else if (interrupt === 'lostcapture') {
        const id = active.during.captures[0]!.pointerId
        await active.source.evaluate((element, pointerId) => element.releasePointerCapture(pointerId), id)
        // Native processing of pending capture dispatches the trusted loss
        // before this actual move; no lostpointercapture event is fabricated.
        await page.mouse.move(active.origin.x + 106, active.origin.y + 21)
      } else {
        const other = await context.newPage(); await other.goto('about:blank'); await other.bringToFront()
      }
      await expect(canvas).not.toHaveAttribute('data-pointer-gesture', /./)
      await expect(canvas.locator('.workflow-connection-preview')).toHaveCount(0)
      const cancelled = await pointerSnapshot(page)
      await record(info, `${kind}-${interrupt}`, { during: active.during, cancelled })
      clean(cancelled)
      expect(cancelled.events.some(event => event.type === (interrupt === 'lostcapture' ? 'lostpointercapture' : interrupt)
        && event.trusted && (interrupt !== 'blur' || event.target === 'window'))).toBe(true)
      expect(await position(canvas)).toEqual(active.before)
      await expect(canvas.locator('.workflow-wire')).toHaveCount(2)
      expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
      await page.evaluate(() => window.__pointerResources.disarm())
      await page.bringToFront()
      // touchCancel already ends the physical CDP sequence. Sending touchEnd
      // again would fail the protocol after the cleanup assertions have passed.
      if (interrupt === 'pointercancel') await active.cdp!.detach()
      else await release(page, active.cdp)
      await expect(canvas.locator('.workflow-wire')).toHaveCount(2)
      expect(await position(canvas)).toEqual(active.before)
      await focusCdp?.detach()
    })
  }
}

for (const zoom of [.5, 1, 2]) {
  test(`节点首帧105px/20px在缩放${zoom}完整换算并提交（模拟数据）`, async ({ page }, info) => {
    const fixture = await setup(page, zoom), { canvas } = await enter(page, fixture)
    const active = await start(page, canvas, 'node-mouse')
    const moved = await position(canvas)
    expect(moved.x - active.before.x).toBeCloseTo(105 / zoom, 2); expect(moved.y - active.before.y).toBeCloseTo(20 / zoom, 2)
    await page.mouse.up()
    const completed = await pointerSnapshot(page); await record(info, `drag-zoom-${zoom}`, { during: active.during, completed, moved })
    clean(completed); expect(await position(canvas)).toEqual(moved)
    await expect(page.getByRole('button', { name: '撤销修改', exact: true })).toBeEnabled()
    expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  })
}

for (const { skin, input } of [{ skin: 'spdb', input: 'mouse' }, { skin: 'tech-blue', input: 'mouse' },
  { skin: 'github-white', input: 'mouse' }, { skin: 'spdb', input: 'touch' }] as const) {
  test(`${skin} ${input} 正常Pointer连接保留真实ReactFlow边并释放资源（模拟数据）`, async ({ page }, info) => {
    const fixture = await setup(page, 1, skin), { canvas } = await enter(page, fixture)
    const active = await start(page, canvas, input === 'touch' ? 'connection-touch' : 'connection-mouse')
    const target = await point(canvas.locator('.react-flow__handle.target[data-nodeid="review"]'))
    if (active.cdp) {
      await active.cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ ...target, id: 1 }] })
      await active.cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
    } else {
      await page.mouse.move(target.x, target.y, { steps: 5 })
      await expect(canvas.locator('.workflow-connection-preview')).toBeVisible()
      await mkdir(evidence, { recursive: true })
      await page.screenshot({ path: join(evidence, `${skin}-pointer-connect-desktop.png`), fullPage: true })
      await page.mouse.up()
    }
    await expect(canvas.locator('.workflow-wire')).toHaveCount(3)
    const completed = await pointerSnapshot(page); await record(info, `connect-${input}`, { during: active.during, completed })
    clean(completed); await expect(canvas.locator('.workflow-connection-preview')).toHaveCount(0)
    await page.getByRole('button', { name: '撤销修改', exact: true }).click()
    await expect(canvas.locator('.workflow-wire')).toHaveCount(2)
    expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
    await active.cdp?.detach()
  })
}

test('真实CDP触摸节点首帧105px/20px完整提交并清理（模拟数据）', async ({ page }, info) => {
  const fixture = await setup(page, .5), { canvas } = await enter(page, fixture)
  const active = await start(page, canvas, 'node-mouse', true)
  const moved = await position(canvas)
  expect(moved.x - active.before.x).toBeCloseTo(210, 2); expect(moved.y - active.before.y).toBeCloseTo(40, 2)
  await active.cdp!.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  const completed = await pointerSnapshot(page); await record(info, 'touch-drag', { during: active.during, completed })
  clean(completed); expect(await position(canvas)).toEqual(moved)
  await expect(page.getByRole('button', { name: '撤销修改', exact: true })).toBeEnabled()
  expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  await active.cdp!.detach()
})

test('真实CDP双指缩放释放一指后继续单指平移，最终清零（模拟数据）', async ({ page }, info) => {
  const fixture = await setup(page), { canvas } = await enter(page, fixture)
  const { first, second } = await blankPane(canvas), cdp = await touch(page)
  const before = await viewport(canvas), nodeBefore = await position(canvas)
  await page.bringToFront(); await page.evaluate(() => window.__pointerResources.arm())
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...first, id: 1 }] })
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ ...first, id: 1 }, { ...second, id: 2 }] })
  const nextFirst = { x: first.x - 30, y: first.y + 10, id: 1 }, nextSecond = { x: second.x + 30, y: second.y + 10, id: 2 }
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [nextFirst, nextSecond] })
  const pinched = await pointerSnapshot(page), changed = await viewport(canvas)
  expect(changed.zoom).toBeCloseTo(before.zoom * 1.4, 2)
  expect(pinched.captures).toHaveLength(2)
  const down = pinched.events.filter(event => event.type === 'pointerdown' && event.pointerType === 'touch' && event.trusted)
  expect(down).toHaveLength(2)
  expect(down[0]!.clientX).toBeCloseTo(first.x, 2); expect(down[0]!.clientY).toBeCloseTo(first.y, 2)
  expect(down[1]!.clientX).toBeCloseTo(second.x, 2); expect(down[1]!.clientY).toBeCloseTo(second.y, 2)
  // Chromium's native WebTouch partial end names the changed (released) point.
  // End the first finger and keep the second pointer's actual capture below.
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [nextFirst] })
  await expect(canvas).toHaveAttribute('data-pointer-gesture', /pan|pinch/)
  const retained = await pointerSnapshot(page); expect(retained.captures).toHaveLength(1)
  const ended = retained.events.filter(event => event.type === 'pointerup' && event.pointerType === 'touch')
  expect(ended).toHaveLength(1); expect(ended[0]!.trusted).toBe(true)
  expect(ended[0]!.pointerId).toBe(down[0]!.pointerId)
  expect(retained.captures[0]!.pointerId).toBe(down[1]!.pointerId)
  expect(retained.captures[0]!.pointerId).not.toBe(ended[0]!.pointerId)
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchMove', touchPoints: [{ x: nextSecond.x + 25, y: nextSecond.y + 15, id: 2 }] })
  await expect.poll(async () => (await pointerSnapshot(page)).events.some(event => event.type === 'pointermove' && event.trusted
    && event.pointerId === retained.captures[0]!.pointerId && Math.abs(event.clientX! - nextSecond.x - 25) < .01
    && Math.abs(event.clientY! - nextSecond.y - 15) < .01)).toBe(true)
  const singleMoved = await pointerSnapshot(page)
  await expect.poll(async () => (await viewport(canvas)).x - changed.x).toBeCloseTo(25, 2)
  await expect.poll(async () => (await viewport(canvas)).y - changed.y).toBeCloseTo(15, 2)
  const panned = await viewport(canvas)
  expect(panned.zoom).toBeCloseTo(changed.zoom, 2)
  expect(panned.x - changed.x).toBeCloseTo(25, 2); expect(panned.y - changed.y).toBeCloseTo(15, 2)
  await cdp.send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] })
  const completed = await pointerSnapshot(page)
  await record(info, 'pinch-to-pan', { before, pinched, retained, singleMoved, panned, completed })
  clean(completed); expect(await position(canvas)).toEqual(nodeBefore)
  expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  await cdp.detach()
})

for (const skin of ['spdb', 'tech-blue', 'github-white']) {
  test(`${skin} 390px活动指针拖动与退出清理（模拟数据）`, async ({ page }, info) => {
    const fixture = await setup(page, 1, skin, 390), { canvas, node } = await enter(page, fixture)
    // Selecting a node opens the existing narrow-screen details overlay. Close
    // it through its real control before starting a second pointer gesture.
    await page.getByRole('button', { name: '关闭节点详情', exact: true }).click()
    await expect(page.getByRole('region', { name: '节点详情', exact: true })).toHaveCount(0)
    const active = await start(page, canvas, 'node-mouse')
    await expect(page.locator('html')).toHaveAttribute('data-skin', skin)
    await expect(node).toBeFocused()
    expect(await page.locator('body').evaluate(element => element.scrollWidth <= innerWidth + 1)).toBe(true)
    await mkdir(evidence, { recursive: true })
    await page.screenshot({ path: join(evidence, `${skin}-pointer-drag-390.png`), fullPage: true })
    await expect(canvas).toHaveAttribute('data-pointer-gesture', 'drag')
    const beforeLeave = await pointerSnapshot(page); expect(beforeLeave.captures).toHaveLength(1)
    await page.getByRole('link', { name: '返回流程库', exact: true }).evaluate(element => (element as HTMLElement).click())
    await expect(page).toHaveURL('/workflows'); await expect(canvas).toHaveCount(0)
    const immediate = await pointerSnapshot(page); await record(info, skin, { during: active.during, beforeLeave, immediate })
    clean(immediate); noCleanupInput(beforeLeave, immediate)
    expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  })
}

for (const target of ['node', 'edge'] as const) {
  test(`中键从${target}平移三次SPA退出立即清零并移除实例轮缩监听（模拟数据）`, async ({ page }, info) => {
    const fixture = await setup(page)
    for (let cycle = 0; cycle < 3; cycle++) {
      const { canvas } = await enter(page, fixture), active = await middle(page, canvas, target)
      await page.getByRole('link', { name: '返回流程库', exact: true }).evaluate(element => (element as HTMLElement).click())
      await expect(page).toHaveURL('/workflows'); await expect(canvas).toHaveCount(0)
      const immediate = await pointerSnapshot(page)
      await record(info, `${target}-middle-${cycle}`, { during: active.during, immediate })
      clean(immediate); noCleanupInput(active.during, immediate); expect(immediate.wheelListeners).toEqual([])
      expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
      await page.evaluate(() => window.__pointerResources.disarm())
      await page.mouse.up({ button: 'middle' }); await unaffected(page, immediate)
    }
  })
  test(`中键从${target}首帧105px/20px平移只提交一次布局和撤销记录（模拟数据）`, async ({ page }, info) => {
    const fixture = await setup(page), { canvas } = await enter(page, fixture), active = await middle(page, canvas, target)
    const moved = await viewport(canvas)
    await page.mouse.up({ button: 'middle' })
    const completed = await pointerSnapshot(page)
    await record(info, `${target}-middle-complete`, { during: active.during, completed, moved })
    clean(completed); expect(completed.wheelListeners).toHaveLength(1)
    expect(await viewport(canvas)).toEqual(moved); expect(await position(canvas)).toEqual(active.nodeBefore)
    await page.getByRole('button', { name: '撤销修改', exact: true }).click()
    await expect.poll(() => viewport(canvas)).toEqual(active.before)
    await expect(page.getByRole('button', { name: '撤销修改', exact: true })).toBeDisabled()
    expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  })
}

for (const kind of ['connect', 'drag'] as const) {
  test(`真实Ctrl滚轮缩放终止旧${kind}，迟到释放不写回且轮缩监听卸载（模拟数据）`, async ({ page }, info) => {
    const fixture = await setup(page), { canvas } = await enter(page, fixture)
    const mounted = await pointerSnapshot(page); expect(mounted.wheelListeners).toHaveLength(1)
    const active = await start(page, canvas, kind === 'drag' ? 'node-mouse' : 'connection-mouse')
    await page.keyboard.down('Control'); await page.mouse.wheel(0, -100); await page.keyboard.up('Control')
    await expect(canvas).not.toHaveAttribute('data-pointer-gesture', /./)
    await expect.poll(async () => (await viewport(canvas)).zoom).toBeCloseTo(active.beforeViewport.zoom * 1.1, 2)
    const zoomed = await viewport(canvas), stopped = await pointerSnapshot(page)
    await record(info, `${kind}-ctrl-wheel`, { mounted, during: active.during, stopped, zoomed })
    clean(stopped); expect(stopped.wheelListeners).toHaveLength(1)
    expect(await position(canvas)).toEqual(active.before)
    await expect(canvas.locator('.workflow-connection-preview')).toHaveCount(0)
    await expect(canvas.locator('.workflow-wire')).toHaveCount(2)
    // Reset the held mouse only after cancellation's first zero-resource snapshot.
    await page.evaluate(() => window.__pointerResources.disarm()); await page.mouse.up()
    expect(await viewport(canvas)).toEqual(zoomed); expect(await position(canvas)).toEqual(active.before)
    await page.getByRole('button', { name: '撤销修改', exact: true }).click()
    await expect.poll(() => viewport(canvas)).toEqual(active.beforeViewport)
    await expect(page.getByRole('button', { name: '撤销修改', exact: true })).toBeDisabled()
    await page.getByRole('link', { name: '返回流程库', exact: true }).click()
    await expect(page).toHaveURL('/workflows'); await expect(canvas).toHaveCount(0)
    const immediate = await pointerSnapshot(page)
    await record(info, `${kind}-wheel-unmount`, immediate)
    clean(immediate); expect(immediate.wheelListeners).toEqual([])
    expect(fixture.errors).toEqual([]); expect(fixture.mutations).toEqual([])
  })
}
