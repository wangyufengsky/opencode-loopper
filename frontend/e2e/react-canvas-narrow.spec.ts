import { expect, test } from '@playwright/test'
import { canvasReviewFixture } from './fixtures/canvasReview'

const evidence = process.env.CANVAS_EVIDENCE_DIR ?? 'test-results/react-canvas'
for (const skin of ['spdb', 'tech-blue', 'github-white']) {
  test(`${skin} React流程画布窄屏选择、键盘取消和上下文关闭（模拟数据）`, async ({ page }) => {
    const fixture = await canvasReviewFixture(page)
    await page.addInitScript(value => localStorage.setItem('loopper.skin', value), skin)
    await page.setViewportSize({ width: 390, height: 844 })
    await page.goto('/workflows/canvas-review')
    const canvas = page.locator('[data-canvas-runtime="react"][data-canvas-kind="workflow"]')
    await expect(canvas.locator('.react-flow')).toBeVisible()
    await page.getByRole('button', { name: '适应画布', exact: true }).click()
    await expect(canvas.locator('.workflow-node')).toHaveCount(3)
    await expect(page.locator('.workflow-context-panel')).toHaveCount(0)
    await page.screenshot({ path: `${evidence}/${skin}-workflow-mobile.png`, fullPage: true })
    const first = canvas.locator('.workflow-node').first()
    await first.focus(); await page.keyboard.press('Enter')
    await expect(page.getByLabel('名称', { exact: true })).toBeVisible()
    await page.screenshot({ path: `${evidence}/${skin}-workflow-mobile-selected.png`, fullPage: true })
    await page.keyboard.press('Escape')
    await expect(page.locator('.workflow-context-panel')).toHaveCount(0)
    await expect(first).toBeFocused()
    await first.click()
    await page.getByRole('button', { name: '关闭节点详情', exact: true }).click()
    await expect(page.locator('.workflow-context-panel')).toHaveCount(0)
    expect(await page.locator('body').evaluate(element => element.scrollWidth <= innerWidth + 1)).toBe(true)
    expect(fixture.mutations).toEqual([])
    expect(fixture.errors).toEqual([])
  })
}
