import { expect, test } from '@playwright/test'

const catalog = {
  templates: [
    { id: 'CODE_REVIEW', version: '1', title: '代码审查', description: '审查所选分支和日期范围内的 Git 提交，生成 Markdown 代码审查报告', stages: ['采集 Git 证据', '分析与生成报告'], contentRepairLimit: 2, scoringVersion: null },
    { id: 'CONTRIBUTION_REPORT', version: '1', title: '项目人员贡献周报', description: '生成贡献排名、项目总报告和个人周报', stages: ['采集 Git 证据', '分析与生成报告'], contentRepairLimit: 2, scoringVersion: 'CONTRIBUTION_SCORE_V1' },
  ], timezone: 'Asia/Shanghai', defaultStartDate: '2026-09-05', defaultEndDate: '2026-09-11', scoringVersion: 'CONTRIBUTION_SCORE_V1', scoreFormula: '30 × ln(1+L) / ln(1+Lmax)', dimensions: [],
}
const main = { id: 'remote:origin:refs/heads/main', label: 'origin/main（远程）', ref: 'refs/heads/main', remote: 'origin' }
const dev = { id: 'remote:origin:refs/heads/develop', label: 'origin/develop（远程）', ref: 'refs/heads/develop', remote: 'origin' }
let submissions: Record<string, unknown>[] = []
test.beforeEach(async ({ page }) => {
  submissions = []
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    let payload: unknown = []
    if (path === '/api/template-tasks/catalog') payload = catalog
    else if (path === '/api/template-tasks/projects') payload = { items: [{ id: 'p', name: '示例项目', createdAt: '2026-09-11T00:00:00Z' }], nextCursor: null }
    else if (path === '/api/template-tasks/projects/p/branches') payload = { page: { items: [main, dev], nextCursor: null }, defaultBranchId: main.id, defaultBranch: main, remoteAvailable: true }
    else if (path === '/api/template-tasks' && request.method() === 'POST') {
      expect(request.headers()['x-loopper-local-ui']).toBe('1')
      submissions.push(request.postDataJSON())
      payload = { id: 'created', state: 'PENDING_START' }
    } else if (path === '/api/template-tasks/created/start') payload = { id: 'created', state: 'RUNNING' }
    else if (path === '/api/template-tasks') payload = { items: [], nextCursor: null }
    else if (path.includes('story-binding/capability')) payload = { available: false, state: 'UNAVAILABLE', reason: '未安装统计命令', checkedAt: 'now' }
    else if (path === '/api/runtime/opencode') payload = { status: 'OFFLINE', managed: false, checkedAt: 'now' }
    else if (path === '/api/tasks/summaries') payload = { tasks: [], facets: {} }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(payload) })
  })
})

test('模板任务默认北京时间日期、可选择分支，结束日期校验阻止错误提交', async ({ page }) => {
  await page.goto('/automations')
  await expect(page).toHaveURL(/\/template-tasks$/)
  await expect(page.getByRole('heading', { name: '模板任务', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始执行', exact: true })).toBeDisabled()
  await page.getByRole('combobox', { name: '项目', exact: true }).click()
  await page.getByRole('option', { name: '示例项目', exact: true }).click()
  await expect(page.getByRole('combobox', { name: '分支', exact: true })).toHaveValue('')
  await expect(page.locator('.el-select__selected-item').filter({ hasText: 'origin/main（远程）' })).toBeVisible()
  const dates = page.locator('.el-date-editor input')
  await expect(dates.nth(0)).toHaveValue('2026-09-05')
  await expect(dates.nth(1)).toHaveValue('2026-09-11')
  await dates.nth(0).fill('2026-09-12')
  await dates.nth(0).press('Enter')
  await page.getByRole('heading', { name: '模板任务', exact: true }).click()
  await expect(page.getByText('结束日期不能早于开始日期', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '开始执行', exact: true })).toBeDisabled()
  await dates.nth(0).fill('2026-09-11')
  await dates.nth(0).press('Enter')
  await page.getByRole('heading', { name: '模板任务', exact: true }).click()
  await page.getByRole('combobox', { name: '分支', exact: true }).click()
  await page.getByRole('option', { name: 'origin/develop（远程）', exact: true }).click()
  await page.getByRole('button', { name: '开始执行', exact: true }).click()
  await expect(page).toHaveURL(/\/tasks\/created$/)
  expect(submissions).toHaveLength(1)
  expect(submissions[0]).toMatchObject({ projectId: 'p', branchId: dev.id, startDate: '2026-09-11', endDate: '2026-09-11' })
})

for (const width of [1440, 390]) {
  test(`模板任务 ${width}px 布局与评分说明`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/template-tasks')
    await page.getByRole('button', { name: /项目人员贡献周报/ }).click()
    await page.getByText('内置评分标准 · 满分 100').click()
    await expect(page.getByText(/数量 30 分/)).toBeVisible()
    expect(await page.locator('body').evaluate(node => node.scrollWidth <= window.innerWidth)).toBeTruthy()
    await page.screenshot({ path: `/tmp/loopper-template-tasks-${width}.png`, fullPage: true })
  })
}
