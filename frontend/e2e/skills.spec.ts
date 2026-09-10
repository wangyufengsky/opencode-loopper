import { expect, test } from '@playwright/test'

test('工具与 Skill 分页、按需阅读、安全预览和项目切换', async ({ page }) => {
  const documentRequests: string[] = []
  const listRequests: string[] = []
  const skill = { name: 'code-review', description: '检查代码行为与回归覆盖', location: '/skills/code-review/SKILL.md' }
  const content = '# 代码审查\n\n先阅读变更，再检查行为。\n\n```shell\ngit diff --check\n```\n\n<script>window.skillExecuted = true</script>'
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url())
    let payload: unknown = []
    if (url.pathname === '/api/projects/summaries') payload = [{ id: 'second-project', name: '另一个项目', rootPath: '/tmp/skill-project', status: 'READY', updatedAt: '2026-09-10T00:00:00Z', taskCount: 0, openDesignerSessionCount: 0 }]
    if (url.pathname === '/api/runtime/tools') payload = { servers: [{ id: 'search', name: '代码搜索', status: 'connected', type: 'local' }], complete: true, checkedAt: '' }
    if (url.pathname === '/api/runtime/tools/skills') {
      listRequests.push(url.search)
      payload = { skills: url.searchParams.get('projectId') ? [] : [skill], complete: true, checkedAt: '2026-09-10T00:00:00Z' }
    }
    if (url.pathname === '/api/runtime/tools/skills/document') { documentRequests.push(url.search); payload = { ...skill, content } }
    await route.fulfill({ contentType: 'application/json', body: JSON.stringify(payload) })
  })
  await page.goto('/tools')
  await expect(page.getByRole('heading', { name: '工具与 Skill' })).toBeVisible()
  await expect(page.getByText('代码搜索', { exact: true })).toBeVisible()
  expect(listRequests).toEqual([])
  await page.getByRole('tab', { name: 'Skill', exact: true }).click()
  await expect(page.getByRole('button', { name: /code-review/ })).toBeVisible()
  expect(documentRequests).toEqual([])
  await page.getByRole('button', { name: /code-review/ }).click()
  await expect(page.getByRole('heading', { name: '代码审查', exact: true })).toBeVisible()
  expect(documentRequests).toEqual(['?projectId=&name=code-review'])
  expect(await page.evaluate(() => Object.hasOwn(window, 'skillExecuted'))).toBeFalsy()
  await page.getByRole('button', { name: '查看 Markdown 源文' }).click()
  await expect(page.getByLabel('Markdown 源文')).toHaveText(content)
  await page.screenshot({ path: '/tmp/loopper-skills.png', fullPage: true })
  await page.locator('.tools-toolbar .el-select__wrapper').click()
  await page.getByRole('option', { name: '另一个项目' }).click()
  await expect(page.getByText('当前范围暂无 Skill')).toBeVisible()
  await expect(page.getByLabel('Markdown 源文')).toHaveCount(0)
  await page.getByRole('tab', { name: '工具', exact: true }).click()
  await expect(page.getByText('代码搜索', { exact: true })).toBeVisible()
})
