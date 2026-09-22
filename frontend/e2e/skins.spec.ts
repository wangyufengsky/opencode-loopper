import { expect, test } from '@playwright/test'

const evidence = '../data/deliveries/skins'

test.beforeEach(async ({ page }) => {
  await page.route('http://127.0.0.1:41773/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: ': fixture\n\n' })
    const payload = path === '/api/tasks/summaries' ? { tasks: [], facets: {} } : path === '/api/projects/summaries' ? [] : {}
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(payload) })
  })
})

test('主页选择、刷新、深层页面与同源标签同步，非法保存值安全恢复', async ({ page, context }) => {
  await page.goto('/')
  await expect(page.getByRole('combobox', { name: '选择皮肤' })).toHaveValue('tech-blue')
  await page.getByRole('combobox', { name: '选择皮肤' }).selectOption('github-white')
  await expect(page.locator('html')).toHaveCSS('color-scheme', 'light')
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(255, 255, 255)')
  await page.reload()
  await expect(page.getByRole('combobox', { name: '选择皮肤' })).toHaveValue('github-white')
  const other = await context.newPage()
  await other.goto('/e2e/fixtures/skins.html')
  await expect(other.locator('html')).toHaveAttribute('data-skin', 'github-white')
  await page.getByRole('combobox', { name: '选择皮肤' }).selectOption('tech-blue')
  await expect(other.locator('html')).toHaveAttribute('data-skin', 'tech-blue')
  await page.getByRole('combobox', { name: '选择皮肤' }).selectOption('github-white')
  await page.goto('/tasks')
  await expect(page.getByRole('heading', { name: '任务控制台' })).toBeVisible()
  await expect(page.locator('html')).toHaveCSS('color-scheme', 'light')
  await page.evaluate(() => localStorage.setItem('loopper.skin', 'no-longer-installed'))
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-skin', 'tech-blue')
  await other.close()
})

for (const skin of ['tech-blue', 'github-white']) {
  for (const width of [1440, 1280, 390]) {
    test(`${skin} 主页 ${width}px 皮肤与离线布局`, async ({ page }) => {
      await page.setViewportSize({ width, height: 1000 })
      await page.emulateMedia({ reducedMotion: 'reduce' })
      await page.addInitScript(value => localStorage.setItem('loopper.skin', value), skin)
      await page.goto('/')
      await expect(page.getByRole('combobox', { name: '选择皮肤' })).toHaveValue(skin)
      await expect(page.locator('.home-artwork')).toHaveCSS('display', skin === 'tech-blue' ? 'block' : 'none')
      expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth)).toBe(true)
      await page.screenshot({ path: `${evidence}/${skin}-home-${width}.png`, fullPage: true })
    })
  }

  test(`${skin} 控件、弹层、代码与流程图一致并保持输入`, async ({ page }) => {
    const errors: string[] = []
    page.on('pageerror', error => errors.push(error.message))
    await page.setViewportSize({ width: 1280, height: 1100 })
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/e2e/fixtures/skins.html')
    await page.getByRole('textbox', { name: '未保存输入' }).fill('换肤期间的草稿')
    await page.getByRole('combobox', { name: '选择皮肤' }).selectOption(skin)
    await expect(page.locator('.markdown-mermaid svg')).toBeVisible()
    // Reduced motion must not create a transition while Mermaid measures SVG geometry.
    expect(await page.locator('.markdown-mermaid svg').evaluate(el => el.getBoundingClientRect().height)).toBeLessThan(200)
    await expect(page.getByRole('textbox', { name: '未保存输入' })).toHaveValue('换肤期间的草稿')
    await expect(page.locator('.cm-content')).toContainText('public class Skin')
    await page.screenshot({ path: `${evidence}/${skin}-components.png`, fullPage: true })
    await page.getByRole('button', { name: '打开弹窗' }).click()
    await expect(page.getByRole('dialog')).toBeVisible()
    await expect(page.getByRole('textbox', { name: '弹窗输入' })).toHaveValue('换肤期间的草稿')
    await expect(page.locator('.el-dialog')).toHaveCSS('background-color', skin === 'github-white' ? 'rgb(246, 248, 250)' : 'rgb(13, 20, 36)')
    await page.screenshot({ path: `${evidence}/${skin}-dialog.png`, fullPage: true })
    expect(errors).toEqual([])
  })
}
