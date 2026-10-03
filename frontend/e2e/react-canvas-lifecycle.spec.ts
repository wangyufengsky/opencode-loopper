import { expect, test } from '@playwright/test'
import { canvasReviewFixture } from './fixtures/canvasReview'

const evidence = process.env.CANVAS_EVIDENCE_DIR ?? 'test-results/react-canvas'

test('同一页面反复进入与离开 React 画布，选择和取消不叠加副作用（模拟数据）', async ({ page }) => {
  const fixture = await canvasReviewFixture(page)
  await page.goto('/workflows')
  const documentIdentity = await page.evaluate(() => {
    const identity = crypto.randomUUID()
    document.documentElement.dataset.reviewDocument = identity
    return identity
  })
  for (let index = 0; index < 3; index++) {
    await page.getByRole('link', { name: fixture.flow.title, exact: true }).click()
    const canvas = page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')
    await expect(canvas).toHaveCount(1)
    await expect(canvas.locator('.react-flow')).toBeVisible()
    const oldRoot = await canvas.elementHandle()
    const node = canvas.locator('.workflow-node').filter({ hasText: '确认需求' })
    await node.click()
    await expect(page.getByRole('complementary', { name: '节点设置', exact: true })).toBeVisible()
    await node.click()
    await expect(page.getByRole('complementary', { name: '节点设置', exact: true })).toHaveCount(1)
    await page.keyboard.press('Escape')
    await expect(page.locator('.workflow-context-panel')).toHaveCount(0)
    await page.getByRole('link', { name: '返回流程库', exact: true }).click()
    await expect(page).toHaveURL('/workflows')
    await expect(page.locator('[data-canvas-kind="workflow"]')).toHaveCount(0)
    expect(await oldRoot!.evaluate(element => element.isConnected)).toBe(false)
    await oldRoot!.dispose()
    expect(await page.locator('html').getAttribute('data-review-document')).toBe(documentIdentity)
    expect(fixture.mutations).toEqual([])
  }
  expect(fixture.errors).toEqual([])
})

test('内置模板真实 React Flow 只读预览不修改业务图（模拟数据）', async ({ page }) => {
  const fixture = await canvasReviewFixture(page)
  fixture.flow.builtin = true
  fixture.flow.title = '内置交付流程 · 模拟数据'
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto('/workflows/builtin.workflow.development')
  const canvas = page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')
  await expect(canvas.locator('.react-flow')).toBeVisible()
  await expect(page.getByRole('button', { name: '复制为自定义流程', exact: true })).toBeEnabled()
  await expect(page.getByRole('button', { name: '添加节点', exact: true })).toBeDisabled()
  const node = canvas.locator('.workflow-node').filter({ hasText: '确认需求' })
  await node.click()
  await expect(page.getByLabel('名称', { exact: true })).toBeDisabled()
  await expect(canvas.locator('.workflow-port')).toHaveCount(0)
  const before = await node.boundingBox()
  await node.focus()
  await page.keyboard.press('ArrowRight')
  await page.keyboard.press('Delete')
  await expect(canvas.locator('.workflow-node')).toHaveCount(3)
  expect(await node.boundingBox()).toEqual(before)
  expect(fixture.mutations).toEqual([])
  expect(fixture.errors).toEqual([])
  await page.screenshot({ path: `${evidence}/workflow-builtin-preview.png`, fullPage: true })
})
