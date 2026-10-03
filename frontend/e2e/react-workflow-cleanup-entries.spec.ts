import { expect, test, type Locator, type Page, type TestInfo } from '@playwright/test'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { canvasReviewFixture } from './fixtures/canvasReview'
import { workflowTool } from './fixtures/workflowNavigation'
import { observePointerResources, pointerSnapshot, type PointerResourceSnapshot } from './fixtures/pointerResources'

const evidence = process.env.CANVAS_CLEANUP_EVIDENCE_DIR ?? 'test-results/react-all-canvas-cleanup'
const selector = '[data-canvas-runtime="react"][data-canvas-kind="workflow"]'

async function pan(page: Page, canvas: Locator) {
  await canvas.scrollIntoViewIfNeeded()
  const origin = await canvas.evaluate(element => {
    const pane = element.querySelector('.react-flow__pane')!, box = pane.getBoundingClientRect()
    for (const dy of [.75, .5, .25]) for (const dx of [.15, .35, .55]) {
      const point = { x: box.x + box.width * dx, y: box.y + box.height * dy }
      if (document.elementFromPoint(point.x, point.y) === pane) return point
    }
    throw new Error('No visible blank pane in fixture')
  })
  const offset = () => canvas.locator('.react-flow__viewport').evaluate(element => {
    const matrix = new DOMMatrixReadOnly(getComputedStyle(element).transform)
    return { x: matrix.m41, y: matrix.m42 }
  })
  const before = await offset()
  await page.evaluate(() => window.__pointerResources.arm())
  await page.mouse.move(origin.x, origin.y); await page.mouse.down(); await page.mouse.move(origin.x + 105, origin.y + 20)
  await expect(canvas).toHaveAttribute('data-pointer-gesture', 'pan')
  await expect.poll(async () => (await offset()).x - before.x).toBeCloseTo(105, 2)
  expect((await offset()).y - before.y).toBeCloseTo(20, 2)
  const during = await pointerSnapshot(page)
  expect(during.captures).toHaveLength(1)
  expect(during.listeners).toHaveLength(5)
  return during
}

async function cleaned(page: Page, during: PointerResourceSnapshot, roots: number, info: TestInfo, cycle: number) {
  // The first post-exit sample comes before mouseup or any later input.
  const immediate = await pointerSnapshot(page)
  expect(immediate.roots).toBe(roots)
  expect(immediate.listeners).toEqual([]); expect(immediate.pendingFrames).toEqual([]); expect(immediate.captures).toEqual([])
  expect(immediate.wheelListeners).toHaveLength(roots)
  expect(immediate.sentinelListener).toBe(true)
  expect(immediate.events.slice(during.events.length).filter(event => ['pointermove', 'pointerup', 'pointercancel', 'mousemove', 'mouseup'].includes(event.type)
    || event.type === 'blur' && event.target === 'window')).toEqual([])
  const body = JSON.stringify({ during, immediate }, null, 2)
  await mkdir(evidence, { recursive: true })
  await writeFile(join(evidence, `${info.testId.replace(/[^a-z0-9_-]/gi, '_')}-${cycle}.json`), body)
  await info.attach(`exit-${cycle}`, { body, contentType: 'application/json' })
  await page.evaluate(() => window.__pointerResources.disarm())
  await page.mouse.up()
}

for (const running of [false, true]) test(`需求${running ? '执行' : '规划'}画布活动平移三次路由退出直接清理（模拟数据）`, async ({ page }, info) => {
  const fixture = await canvasReviewFixture(page, running)
  await observePointerResources(page)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto('/requirements')
  const identity = await page.evaluate(() => { document.documentElement.dataset.cleanupDocument = crypto.randomUUID(); return document.documentElement.dataset.cleanupDocument })
  for (let cycle = 0; cycle < 3; cycle++) {
    await page.getByRole('link', { name: fixture.req.title, exact: true }).click()
    const canvas = page.locator(selector)
    await expect(canvas.locator('.react-flow__node')).toHaveCount(3)
    const root = await canvas.elementHandle(), during = await pan(page, canvas)
    await page.getByRole('link', { name: '返回需求任务', exact: true }).evaluate(element => (element as HTMLElement).click())
    await expect(page).toHaveURL('/requirements'); await expect(canvas).toHaveCount(0)
    expect(await root!.evaluate(element => element.isConnected)).toBe(false)
    await cleaned(page, during, 0, info, cycle)
    await root!.dispose()
    expect(await page.locator('html').getAttribute('data-cleanup-document')).toBe(identity)
    expect(fixture.mutations).toEqual([]); expect(fixture.errors).toEqual([])
  }
})

test('另存模板预览活动平移三次关闭立即清理，父画布保持原实例（模拟数据）', async ({ page }, info) => {
  const fixture = await canvasReviewFixture(page)
  await page.route('**/api/workflows/requirements/req/templates/preview', async route => {
    const body = route.request().postDataJSON()
    return route.fulfill({ json: { mode: body.mode, sourceRevision: 2, initialAvailable: true,
      graph: body.graph, layout: body.layout, fixedPlanningNodes: [], sha256: 'simulated-preview', diagnostics: [] } })
  })
  await observePointerResources(page)
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/req')
  const main = page.locator('.workflow-studio').locator(selector)
  await expect(main.locator('.react-flow__node')).toHaveCount(3)
  const parentRoot = await main.elementHandle()
  for (let cycle = 0; cycle < 3; cycle++) {
    await workflowTool(page, '另存为流程模板')
    const dialog = page.getByRole('dialog', { name: '另存为流程模板' }), canvas = dialog.locator(selector)
    await expect(canvas.locator('.react-flow__node')).toHaveCount(3)
    const root = await canvas.elementHandle(), during = await pan(page, canvas)
    await dialog.getByRole('button', { name: '返回任务画布', exact: true }).evaluate(element => (element as HTMLElement).click())
    await expect(dialog).toHaveCount(0)
    expect(await root!.evaluate(element => element.isConnected)).toBe(false)
    await cleaned(page, during, 1, info, cycle)
    expect(await parentRoot!.evaluate(element => element.isConnected)).toBe(true)
    await expect(main.locator('.react-flow__node')).toHaveCount(3)
    expect(fixture.mutations).toEqual([]); expect(fixture.errors).toEqual([])
    await root!.dispose()
  }
  await parentRoot!.dispose()
})
