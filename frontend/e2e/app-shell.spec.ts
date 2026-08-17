import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('http://127.0.0.1:41773/api/**', async (route) => {
    const path = new URL(route.request().url()).pathname
    const payload = path === '/api/projects'
      ? [{ id: 'e2e-project', name: 'E2E 隔离项目', rootPath: '/tmp/loopper-e2e', status: 'READY', updatedAt: '2026-08-05T00:00:00Z', taskCount: 0, openDesignerSessionCount: 0 }]
      : path === '/api/tasks'
        ? []
        : path === '/api/automations/templates' || path === '/api/automations/rules'
          ? []
          : path === '/api/automations/runs'
            ? { runs: [], serverTime: '2026-08-05T00:00:00Z' }
        : path === '/api/runtime/opencode'
          ? { status: 'OFFLINE', managed: false, checkedAt: '2026-08-05T00:00:00Z' }
          : {}
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(payload) })
  })
  await page.route('http://127.0.0.1:41773/actuator/**', (route) => route.fulfill({ contentType: 'application/json', body: '{}' }))
})

test('本地暗色中文外壳可启动并在主要路由间导航', async ({ page }) => {
  await page.goto('/tasks')

  await expect(page).toHaveTitle('OpenCode Loopper')
  await expect(page.getByRole('link', { name: 'OpenCode Loopper 首页' })).toBeVisible()
  await expect(page.getByRole('navigation', { name: '主导航' })).toContainText('设计器 / 循环规范')
  await expect(page.locator('.app-shell')).toBeVisible()
  await expect(page.locator('html')).toHaveCSS('color-scheme', 'dark')
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(7, 11, 20)')
  await expect(page.getByRole('heading', { name: '任务控制台' })).toBeVisible()

  await page.getByRole('link', { name: '设计器 / 循环规范' }).click()
  await expect(page).toHaveURL(/\/designer$/)
  await expect(page.getByRole('heading', { name: '设计工作台' })).toBeVisible()

  await page.getByRole('link', { name: '运行环境', exact: true }).click()
  await expect(page).toHaveURL(/\/runtime$/)
  await expect(page.getByRole('heading', { name: 'OpenCode Runtime' })).toBeVisible()

  await page.getByRole('link', { name: '模板与自动化' }).click()
  await expect(page).toHaveURL(/\/automations$/)
  await expect(page.getByRole('heading', { name: '自动化工作台' })).toBeVisible()
  await expect(page.getByText('服务端强制')).toBeVisible()
  await expect(page.getByText('尚无运行历史；不会显示模拟进度。')).toBeVisible()
})
