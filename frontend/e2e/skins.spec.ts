import { expect, test } from '@playwright/test'

const evidence = process.env.SKIN_EVIDENCE_DIR ?? '../data/deliveries/skins'

test.beforeEach(async ({ context }) => {
  await context.route('http://127.0.0.1:41773/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: ': fixture\n\n' })
    const payload = path === '/api/tasks/summaries' ? { tasks: [], facets: {} } : path === '/api/projects/summaries' ? [] : {}
    return route.fulfill({ contentType: 'application/json', body: JSON.stringify(payload) })
  })
})

test('主页选择、刷新、深层页面与同源标签同步，非法保存值安全恢复', async ({ page, context }) => {
  await page.goto('/')
  await expect(page.getByRole('combobox', { name: '选择皮肤' })).toHaveValue('spdb')
  await expect(page.getByRole('combobox', { name: '选择皮肤' }).locator('option')).toHaveText(['spdb风', '科技蓝', 'GitHub 白'])
  await expect(page.locator('.home-artwork')).toHaveAttribute('src', /home-spdb/)
  await page.getByRole('combobox', { name: '选择皮肤' }).selectOption('github-white')
  await expect(page.locator('html')).toHaveCSS('color-scheme', 'light')
  await expect(page.locator('body')).toHaveCSS('background-color', 'rgb(255, 255, 255)')
  await expect(page.locator('.home-artwork')).toHaveAttribute('src', /home-github-white/)
  await page.reload()
  await expect(page.getByRole('combobox', { name: '选择皮肤' })).toHaveValue('github-white')
  const other = await context.newPage()
  await other.goto('/e2e/fixtures/skins.html')
  await expect(other.locator('html')).toHaveAttribute('data-skin', 'github-white')
  await page.getByRole('combobox', { name: '选择皮肤' }).selectOption('tech-blue')
  await expect(page.locator('.home-artwork')).toHaveAttribute('src', /home-orbit/)
  await expect(other.locator('html')).toHaveAttribute('data-skin', 'tech-blue')
  await page.getByRole('combobox', { name: '选择皮肤' }).selectOption('github-white')
  await page.goto('/tasks')
  await expect(page.getByRole('heading', { name: '任务控制台' })).toBeVisible()
  await expect(page.locator('html')).toHaveCSS('color-scheme', 'light')
  await page.evaluate(() => localStorage.setItem('loopper.skin', 'no-longer-installed'))
  await page.reload()
  await expect(page.locator('html')).toHaveAttribute('data-skin', 'spdb')
  await other.close()
})

test('spdb 选择在刷新与同源标签恢复，清空保存值回到新默认', async ({ page, context }) => {
  await page.goto('/')
  const other = await context.newPage()
  await other.goto('/')
  await page.getByRole('combobox', { name: '选择皮肤' }).selectOption('tech-blue')
  await expect(other.locator('html')).toHaveAttribute('data-skin', 'tech-blue')
  await page.getByRole('combobox', { name: '选择皮肤' }).selectOption('spdb')
  await expect(other.locator('.home-artwork')).toHaveAttribute('src', /home-spdb/)
  await page.reload()
  await expect(page.getByRole('combobox', { name: '选择皮肤' })).toHaveValue('spdb')
  await page.evaluate(() => localStorage.removeItem('loopper.skin'))
  await expect(other.locator('html')).toHaveAttribute('data-skin', 'spdb')
  await other.close()
})

for (const { skin, artwork, surface } of [
  { skin: 'spdb', artwork: /home-spdb/, surface: 'rgb(243, 246, 252)' },
  { skin: 'tech-blue', artwork: /home-orbit/, surface: 'rgb(13, 20, 36)' },
  { skin: 'github-white', artwork: /home-github-white/, surface: 'rgb(246, 248, 250)' },
]) {
  for (const width of [1440, 1280, 390]) {
    test(`${skin} 主页 ${width}px 皮肤与离线布局`, async ({ page }) => {
      await page.setViewportSize({ width, height: 1000 })
      await page.emulateMedia({ reducedMotion: 'reduce' })
      await page.addInitScript(value => localStorage.setItem('loopper.skin', value), skin)
      await page.goto('/')
      await expect(page.getByRole('combobox', { name: '选择皮肤' })).toHaveValue(skin)
      await expect(page.locator('.home-artwork')).toBeVisible()
      await expect(page.locator('.home-artwork')).toHaveAttribute('src', artwork)
      await expect.poll(() => page.locator('.home-artwork').evaluate((image: HTMLImageElement) => image.complete && image.naturalWidth === 1536 && image.naturalHeight === 1024)).toBe(true)
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
    await expect(page.locator('.el-dialog')).toHaveCSS('background-color', surface)
    await page.screenshot({ path: `${evidence}/${skin}-dialog.png`, fullPage: true })
    expect(errors).toEqual([])
  })
}
