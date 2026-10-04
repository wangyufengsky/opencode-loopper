import { expect, test, type Page } from '@playwright/test'
import { canvasReviewFixture } from './fixtures/canvasReview'
import { CANVAS_RUNTIME_STORAGE } from '../src/migration/canvasRuntime'

const evidence = process.env.CANVAS_EVIDENCE_DIR ?? 'test-results/react-canvas'
async function drag(page: Page, from: { x: number; y: number }, to: { x: number; y: number }) {
  await page.mouse.move(from.x, from.y); await page.mouse.down()
  await page.mouse.move(to.x, to.y, { steps: 12 }); await page.mouse.up()
}

test('React Flow 真实拖动与连线保持业务图结构，撤销不会提交（模拟数据）', async ({ page }) => {
  const fixture = await canvasReviewFixture(page)
  await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto('/workflows/canvas-review')
  const canvas = page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')
  await expect(canvas.locator('.react-flow')).toBeVisible()
  const source = canvas.locator('.workflow-node').filter({ hasText: '确认需求' })
  const before = (await source.boundingBox())!
  await drag(page, { x: before.x + 80, y: before.y + 25 }, { x: before.x + 140, y: before.y + 65 })
  await expect.poll(async () => (await source.boundingBox())!.x).toBeGreaterThan(before.x + 40)
  await source.click()
  const outgoing = canvas.locator('.react-flow__handle.source[data-nodeid="scope"]')
  const incoming = canvas.locator('.react-flow__handle.target[data-nodeid="review"]')
  const start = (await outgoing.boundingBox())!, end = (await incoming.boundingBox())!
  await drag(page, { x: start.x + start.width / 2, y: start.y + start.height / 2 }, { x: end.x + end.width / 2, y: end.y + end.height / 2 })
  await expect(canvas.locator('.workflow-wire')).toHaveCount(3)
  await page.getByRole('button', { name: '撤销修改', exact: true }).click()
  await expect(canvas.locator('.workflow-wire')).toHaveCount(2)
  expect(fixture.mutations).toEqual([])
  expect(fixture.errors).toEqual([])
  await page.screenshot({ path: `${evidence}/react-flow-drag-connect.png`, fullPage: true })
})

test('回退偏好不替换活动画布或绕过草稿守卫，安全离开后才启用兼容画布（模拟数据）', async ({ page }) => {
  const fixture = await canvasReviewFixture(page)
  await page.goto('/workflows/canvas-review')
  const canvas = page.locator('[data-canvas-kind="workflow"]')
  await expect(canvas).toHaveAttribute('data-canvas-runtime', 'react')
  await canvas.evaluate(element => element.setAttribute('data-original-instance', 'yes'))
  await page.getByRole('button', { name: '流程设置', exact: true }).click()
  await page.getByLabel('流程名称', { exact: true }).fill('保留中的草稿 · 模拟数据')
  await page.evaluate(key => {
    localStorage.setItem(key, JSON.stringify({ workflow: 'vue' }))
    window.dispatchEvent(new StorageEvent('storage', { key }))
  }, CANVAS_RUNTIME_STORAGE)
  await expect(canvas).toHaveAttribute('data-canvas-runtime', 'react')
  await expect(canvas).toHaveAttribute('data-original-instance', 'yes')
  page.once('dialog', dialog => dialog.dismiss())
  await page.getByRole('link', { name: '返回流程库', exact: true }).click()
  await expect(page).toHaveURL('/workflows/canvas-review')
  await expect(page.getByLabel('流程名称', { exact: true })).toHaveValue('保留中的草稿 · 模拟数据')
  page.once('dialog', dialog => dialog.accept())
  await page.getByRole('link', { name: '返回流程库', exact: true }).click()
  await page.getByRole('link', { name: fixture.flow.title, exact: true }).click()
  await expect(page.locator('[data-canvas-kind="workflow"]')).toHaveAttribute('data-canvas-runtime', 'react')
  await page.locator('.workflow-node').first().click()
  await expect(page.getByRole('complementary', { name: '节点设置', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.locator('.workflow-context-panel')).toHaveCount(0)
  expect(fixture.mutations).toEqual([])
  expect(fixture.errors).toEqual([])
  await page.screenshot({ path: `${evidence}/workflow-safe-fallback.png`, fullPage: true })
})

test('本地画布偏好入口只更新下一次页面实例，不写服务端设置（模拟数据）', async ({ page }) => {
  const fixture = await canvasReviewFixture(page)
  await page.goto('/settings')
  await page.getByRole('button', { name: /画布显示/ }).click()
  await page.getByRole('combobox', { name: '流程创作与需求画布', exact: true }).selectOption('react')
  await expect(page.getByRole('status')).toContainText('当前打开的页面保持不变')
  await expect(page.getByRole('button', { name: '保存设置', exact: true })).toHaveCount(0)
  await page.goto('/workflows/canvas-review')
  await expect(page.locator('[data-canvas-kind="workflow"]')).toHaveAttribute('data-canvas-runtime', 'react')
  expect(fixture.mutations).toEqual([])
})

async function taskFixture(page: Page, template: boolean) {
  const id = template ? 'template-react' : 'stage-react'
  const stages = [
    { id: 'prepare', ordinal: 1, objective: '准备固定输入 · 模拟数据', status: 'SUCCEEDED', attemptCount: 1, attempts: [] },
    { id: 'inspect', ordinal: 2, objective: '核对交付结果 · 模拟数据', status: 'RUNNING', attemptCount: 1, attempts: [] },
  ]
  const task = { id, projectId: 'project', projectName: '画布验证 · 模拟数据', title: '阶段图验证 · 模拟数据', goal: '仅验证浏览器渲染',
    status: 'RUNNING', version: 4, attemptCount: 2, maxAttempts: 7, hasDesignHistory: false, archived: false,
    executionMode: template ? 'TEMPLATE_REPORT' : 'WORKTREE', branch: 'main', worktreePath: '/tmp/mock-project',
    loopRetryAvailable: false, cancellationAvailable: true, createdAt: '2026-10-03T00:00:00Z', updatedAt: '2026-10-03T00:00:00Z',
    stages, attempts: [], errors: [], judges: [], artifacts: [], workPackages: [],
    ...(template ? { templateProgress: { reviewBatches: 2, contributorBatches: 0, completedReviews: 1, completedContributors: 0,
      activeBatches: 1, failedBatches: 0, repairRound: 0, reportCount: 0, documentPath: '/tmp/mock-report',
      steps: [{ key: 'PREPARE', label: '准备固定输入', state: 'COMPLETE' }, { key: 'ANALYSIS', label: '核对交付结果', state: 'ACTIVE' }, { key: 'REPORT', label: '保存报告', state: 'PENDING' }] } } : {}),
  }
  const errors: string[] = [], writes: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() !== 'GET') { writes.push(path); return route.fulfill({ status: 501, json: { detail: '模拟数据不执行命令' } }) }
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === `/api/tasks/${id}/overview` || path === `/api/tasks/${id}`) return route.fulfill({ json: task })
    if (path.endsWith('/audit')) return route.fulfill({ json: { attempts: [], errors: [], judges: [], artifacts: [] } })
    if (path.endsWith('/template-progress')) return route.fulfill({ json: task.templateProgress ?? null })
    if (path.endsWith('/failed-batches')) return route.fulfill({ json: { items: [], facets: {}, nextCursor: null } })
    if (path === '/api/runtime/opencode') return route.fulfill({ json: { status: 'OFFLINE', managed: false } })
    if (path === '/api/tasks/summaries') return route.fulfill({ json: { items: [], facets: {} } })
    if (path.endsWith('/sessions/current')) return route.fulfill({ status: 204 })
    return route.fulfill({ json: [] })
  })
  return { id, errors, writes }
}

for (const skin of ['spdb', 'tech-blue', 'github-white']) {
  for (const template of [false, true]) {
    test(`${skin} ${template ? '模板步骤及阶段详情' : '任务阶段'}真实ReactFlow投影与窄屏（模拟数据）`, async ({ page }) => {
      const fixture = await taskFixture(page, template)
      await page.addInitScript(value => localStorage.setItem('loopper.skin', value), skin)
      await page.setViewportSize({ width: 1440, height: 1000 })
      await page.goto(`/tasks/${fixture.id}`)
      const kind = template ? 'template-progress' : 'stages'
      const canvas = page.locator(`[data-canvas-runtime="react"][data-canvas-kind="${kind}"]`).filter({ has: page.locator('.react-flow') }).last()
      await expect(canvas).toBeVisible()
      await expect(canvas.locator('.react-flow__node')).toHaveCount(template ? 3 : 2)
      await expect(canvas.locator('.react-flow__edge')).toHaveCount(template ? 2 : 1)
      if (template) {
        await page.getByText('阶段详情', { exact: true }).click()
        await expect(page.locator('[data-canvas-kind="stages"] .react-flow__node')).toHaveCount(2)
      }
      await canvas.scrollIntoViewIfNeeded()
      await page.screenshot({ path: `${evidence}/${skin}-${kind}.png`, fullPage: true })
      await page.setViewportSize({ width: 390, height: 844 })
      await expect(canvas).toBeVisible()
      expect(await page.locator('body').evaluate(element => element.scrollWidth <= innerWidth + 1)).toBe(true)
      await page.screenshot({ path: `${evidence}/${skin}-${kind}-mobile.png`, fullPage: true })
      expect(fixture.writes).toEqual([])
      expect(fixture.errors).toEqual([])
    })
  }
}
