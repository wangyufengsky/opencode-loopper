import { expect, test, type Page } from '@playwright/test'
import { canvasReviewFixture } from './fixtures/canvasReview'

async function chooseCompatibility(page: Page) {
  const settings = await page.context().newPage()
  await canvasReviewFixture(settings)
  await settings.goto('/settings')
  await settings.getByRole('button', { name: /画布显示/ }).click()
  await settings.getByRole('combobox', { name: /流程创作与需求画布/ }).selectOption('react')
  await expect(settings.getByRole('status').filter({ hasText: '下次重新进入' })).toBeVisible()
  await settings.close()
}

test('Editor 未知保存回执保留原请求，改画布偏好后恢复再安全进入兼容画布（模拟数据）', async ({ page }) => {
  const fixture = await canvasReviewFixture(page), writes: unknown[] = []
  await page.route('**/api/workflows/templates/canvas-review', async route => {
    if (route.request().method() !== 'PUT') return route.fallback()
    const body = route.request().postDataJSON()
    writes.push(body)
    if (writes.length === 1) return route.abort('failed')
    Object.assign(fixture.flow, { title: body.title, description: body.description, graph: body.graph, revision: 3, headRevision: 3, version: 4 })
    return route.fulfill({ json: { id: fixture.flow.id, revision: 3, version: 4, layoutVersion: 4, state: 'ACTIVE' } })
  })
  await page.route('**/api/workflows/templates/canvas-review/layout', async route => {
    const body = route.request().postDataJSON()
    fixture.flow.layout = body.layout; fixture.flow.layoutVersion = 5
    return route.fulfill({ json: { id: fixture.flow.id, revision: 3, version: 4, layoutVersion: 5, state: 'ACTIVE' } })
  })
  await page.goto('/workflows/canvas-review')
  const root = page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')
  await expect(root.locator('.react-flow')).toBeVisible()
  const mounted = await root.elementHandle()
  await page.getByRole('button', { name: '流程设置', exact: true }).click()
  await page.getByLabel('流程名称', { exact: true }).fill('待确认流程 · 模拟数据')
  await page.getByRole('button', { name: '保存流程', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试保存', exact: true })).toBeEnabled()
  expect(writes).toHaveLength(1)
  await chooseCompatibility(page)
  await page.getByRole('link', { name: '返回流程库', exact: true }).click()
  await expect(page).toHaveURL('/workflows/canvas-review')
  await expect(page.getByRole('alert')).toContainText('请重试原保存操作')
  await expect(root).toHaveCount(1)
  expect(await mounted!.evaluate(element => element.isConnected)).toBe(true)
  expect(writes).toHaveLength(1)
  await page.getByRole('button', { name: '重试保存', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '流程已保存' })).toBeVisible()
  expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0])
  await page.getByRole('link', { name: '返回流程库', exact: true }).click()
  await expect(page).toHaveURL('/workflows')
  await page.getByRole('link', { name: fixture.flow.title, exact: true }).click()
  await expect(page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')).toBeVisible()
  expect(await mounted!.evaluate(element => element.isConnected)).toBe(false)
  await mounted!.dispose(); expect(fixture.errors).toEqual([])
})

test('Requirement 未知确认回执保留原请求，改画布偏好后恢复再安全进入兼容画布（模拟数据）', async ({ page }) => {
  const fixture = await canvasReviewFixture(page), writes: unknown[] = []
  await page.route('**/api/workflows/requirements/req/confirm', async route => {
    writes.push(route.request().postDataJSON())
    if (writes.length === 1) return route.abort('failed')
    fixture.req.state = 'PENDING_START'; fixture.req.version = 8
    fixture.snapshot.execution.state = 'PENDING_START'; fixture.snapshot.execution.version = 8
    return route.fulfill({ json: { id: fixture.req.id, revision: fixture.req.revision, version: 8, layoutVersion: fixture.req.layoutVersion, state: 'PENDING_START' } })
  })
  await page.goto('/requirements/req')
  const root = page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')
  await expect(root.locator('.react-flow')).toBeVisible()
  const mounted = await root.elementHandle()
  await page.getByRole('button', { name: '确认计划', exact: true }).click()
  await expect(page.getByRole('button', { name: '重试原操作', exact: true })).toBeEnabled()
  expect(writes).toHaveLength(1)
  await chooseCompatibility(page)
  await page.getByRole('link', { name: '返回需求任务', exact: true }).click()
  await expect(page).toHaveURL('/requirements/req')
  await expect(page.getByRole('alert')).toContainText('请重试原操作')
  await expect(root).toHaveCount(1)
  expect(await mounted!.evaluate(element => element.isConnected)).toBe(true)
  expect(writes).toHaveLength(1)
  await page.getByRole('button', { name: '重试原操作', exact: true }).click()
  await expect(page.getByRole('status').filter({ hasText: '计划已确认' })).toBeVisible()
  expect(writes).toHaveLength(2); expect(writes[1]).toEqual(writes[0])
  await page.getByRole('link', { name: '返回需求任务', exact: true }).click()
  await expect(page).toHaveURL('/requirements')
  await page.getByRole('link', { name: fixture.req.title, exact: true }).click()
  await expect(page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')).toBeVisible()
  expect(await mounted!.evaluate(element => element.isConnected)).toBe(false)
  await mounted!.dispose(); expect(fixture.errors).toEqual([])
})
