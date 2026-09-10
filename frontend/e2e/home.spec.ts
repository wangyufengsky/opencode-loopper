import { expect, test } from '@playwright/test'

test.beforeEach(async ({ page }) => {
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    const payload = path === '/api/tasks/summaries' ? { tasks: [], facets: {} }
      : path === '/api/runtime/opencode' ? { status: 'OFFLINE', managed: false, checkedAt: '2026-09-10T00:00:00Z' }
        : []
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(payload) })
  })
})

test('默认主页、十个入口、品牌返回、后退与刷新', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: '主页', exact: true })).toBeVisible()
  await expect(page.locator('.home-artwork')).toBeVisible()
  expect(await page.locator('.home-artwork').evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth > 0)).toBeTruthy()
  const destinations = ['/projects', '/designer', '/tasks', '/inbox', '/designs', '/insights', '/automations', '/runtime', '/tools', '/settings']
  for (const path of destinations) {
    await page.locator(`main a[href="${path}"]`).first().click()
    await expect(page).toHaveURL(new RegExp(`${path}$`))
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.locator('.nav-item[href="/"]')).not.toHaveClass(/router-link-active/)
    await page.getByRole('link', { name: 'OpenCode Loopper 首页' }).click()
    await expect(page.getByRole('heading', { name: '主页', exact: true })).toBeVisible()
  }
  await page.getByRole('link', { name: '查看任务', exact: true }).click()
  await expect(page.getByRole('heading', { name: '任务控制台' })).toBeVisible()
  await page.goBack()
  await expect(page).toHaveURL(/\/$/)
  await page.reload()
  await expect(page.getByRole('heading', { name: '主页', exact: true })).toBeVisible()
  await page.goto('/unknown/deep/path')
  await expect(page).toHaveURL(/\/$/)
})

test('键盘可跳过导航并进入设计', async ({ page }) => {
  await page.goto('/')
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '跳到主内容' })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page.locator('#main-content')).toBeFocused()
  await page.keyboard.press('Tab')
  await expect(page.getByRole('link', { name: '开始设计', exact: true })).toBeFocused()
  await page.keyboard.press('Enter')
  await expect(page).toHaveURL(/\/designer$/)
})

for (const width of [1440, 1280, 390]) {
  test(`主页在 ${width}px 下无水平溢出且图片离线可用`, async ({ page }) => {
    const externalRequests: string[] = []
    page.on('request', request => { if (!request.url().startsWith('http://127.0.0.1:41773/')) externalRequests.push(request.url()) })
    await page.setViewportSize({ width, height: 1000 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/')
    await expect(page.getByRole('link', { name: '开始设计', exact: true })).toBeVisible()
    await expect(page.locator('.home-artwork')).toHaveJSProperty('complete', true)
    expect(await page.locator('body').evaluate(element => element.scrollWidth <= window.innerWidth)).toBeTruthy()
    for (const card of await page.locator('.home-workspace-link, .home-more-link').all()) {
      expect(await card.evaluate(element => element.scrollWidth <= element.clientWidth)).toBeTruthy()
    }
    expect(externalRequests).toEqual([])
    await page.screenshot({ path: `/tmp/loopper-home-${width}.png`, fullPage: true })
  })
}
