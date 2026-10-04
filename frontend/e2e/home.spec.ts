import { expect, test } from '@playwright/test'
import { productFixture } from './w2/productFixture'

test.beforeEach(async ({ page }) => {
  await productFixture(page)
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if(['/api/settings','/api/settings/models','/api/runtime/tools'].includes(path))return route.fallback()
    const payload = path === '/api/knowledge/conversations' ? {items:[],nextCursor:null} : path === '/api/template-tasks/catalog' ? { templates: [], dimensions: [], defaultStartDate: '2026-09-05', defaultEndDate: '2026-09-11' }
      : (path === '/api/template-tasks/projects' || path === '/api/template-tasks') ? { items: [], nextCursor: null }
      : (path === '/api/workflows/requirements' || path === '/api/workflows/templates') ? { items: [], nextCursor: null }
      : path === '/api/tasks/summaries' ? { tasks: [], facets: {} }
      : path === '/api/runtime/opencode' ? { status: 'OFFLINE', managed: false, checkedAt: '2026-09-10T00:00:00Z' }
        : []
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(payload) })
  })
})

test('默认主页、需求与流程入口、品牌返回、后退与刷新', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '主页', exact: true })).toBeVisible()
  await expect(page.getByRole('textbox', {name:'搜索项目与最近对话'})).toBeVisible()
  await expect(page.getByRole('region', {name:'最近项目'})).toBeVisible()
  await expect(page.getByRole('region', {name:'最近对话'})).toBeVisible()
  const destinations = ['/projects', '/requirements', '/requirements/new', '/workflows', '/tasks', '/inbox', '/designs', '/insights', '/template-tasks', '/runtime', '/tools', '/settings']
  for (const path of destinations) {
    if (path === '/requirements/new') await page.locator('[data-semantic="workflow.newRequirement"]').click()
    else {
      const link=page.locator(`main a[href="${path}"]`).first()
      if (!(await link.isVisible())) await page.getByText('更多入口',{exact:true}).click()
      await link.click()
    }
    await expect(page).toHaveURL(new RegExp(`${path}$`))
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.getByRole('navigation',{name:'主导航'}).locator('a[href="/"]')).not.toHaveAttribute('aria-current','page')
    await page.getByRole('link', { name: 'OpenCode Loopper 首页' }).click()
    await expect(page.getByRole('heading', { name: '主页', exact: true })).toBeVisible()
  }
  await page.locator('main a[href="/tasks"]').click()
  await expect(page.getByRole('heading', { name: '任务控制台' })).toBeVisible()
  await page.goBack()
  await expect(page).toHaveURL(/\/$/)
  await page.reload()
  await expect(page.getByRole('heading', { name: '主页', exact: true })).toBeVisible()
  await page.goto('/unknown/deep/path')
  await expect(page).toHaveURL(/\/$/)
})

test('键盘可跳过导航并进入需求任务', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('textbox',{name:'搜索项目与最近对话'})).toBeVisible()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '跳到主内容' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('textbox', {name:'搜索项目与最近对话'})).toBeFocused()
  await page.keyboard.press('Shift+Tab')
  await expect(page.locator('[data-semantic="workflow.newRequirement"]')).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/requirements\/new$/)
})

for (const width of [1440, 1280, 390]) {
  test(`主页在 ${width}px 下无水平溢出且图片离线可用`, async ({ page }) => {
    const externalRequests: string[] = []
    page.on('request', request => { if (!request.url().startsWith('http://127.0.0.1:41773/')) externalRequests.push(request.url()) })
    await page.setViewportSize({ width, height: 1000 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/')
    await expect(page.locator('[data-semantic="workflow.newRequirement"]')).toBeVisible()
    await expect(page.locator('[data-react-page="nav.home"] svg').first()).toBeVisible()
    expect(await page.locator('body').evaluate(element => element.scrollWidth <= window.innerWidth)).toBeTruthy()
    for (const card of await page.locator('.w2-secondary-actions a').all()) {
      expect(await card.evaluate(element => element.scrollWidth <= element.clientWidth)).toBeTruthy()
    }
    expect(externalRequests).toEqual([])
    await page.screenshot({ path: `/tmp/loopper-home-${width}.png`, fullPage: true })
  })
}
