import { expect, test, type Route } from '@playwright/test'

const project = { id: 'project', name: '隔离项目', rootPath: '/fixture', status: 'READY', updatedAt: '2026-09-08T00:00:00Z', taskCount: 2, openDesignerSessionCount: 0 }
const summary = (name: string) => ({ id: name, title: name, goalPreview: name, projectId: 'project', projectName: '隔离项目', status: 'RUNNING', hasDesignHistory: false, archived: false, createdAt: '2026-09-08T00:00:00Z', updatedAt: '2026-09-08T00:00:00Z' })

test('较晚返回的旧筛选响应不能替换当前列表', async ({ page }) => {
  let delayed: Route | undefined
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/projects/summaries') return route.fulfill({ json: [project] })
    if (url.pathname === '/api/tasks/summaries') {
      const query = url.searchParams.get('q') ?? 'initial'
      if (query === 'old') { delayed = route; return }
      return route.fulfill({ json: { items: [summary(query)], facets: {}, nextCursor: null } })
    }
    return route.fulfill({ json: [] })
  })
  await page.goto('/tasks')
  await expect(page.getByRole('link', { name: 'initial', exact: true })).toBeVisible()
  await page.getByRole('textbox', { name: '搜索任务' }).fill('old')
  await expect.poll(() => !!delayed).toBe(true)
  await page.getByRole('textbox', { name: '搜索任务' }).fill('new')
  await expect(page.getByRole('link', { name: 'new', exact: true })).toBeVisible()
  const oldResponse = page.waitForResponse(response => response.url().includes('q=old'))
  await delayed!.fulfill({ json: { items: [summary('old')], facets: {}, nextCursor: 'old-page' } })
  await oldResponse
  await page.evaluate(() => new Promise<void>(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve()))))
  await expect(page.getByRole('link', { name: 'new', exact: true })).toBeVisible()
  await expect(page.getByRole('link', { name: 'old', exact: true })).toHaveCount(0)
})

// Retain the historical visible recovery contract as a real failing gate while
// the approved archive consumer is missing. Redirect coverage below does not
// replace this assertion or prove that health recovery is reachable in the UI.
test('自动化页面显示检测失败并在服务端恢复后清除旧告警', async ({ page }) => {
  let failed = true
  await page.route('http://127.0.0.1:41773/api/**', route => {
    const url = new URL(route.request().url())
    if (url.pathname === '/api/projects/summaries') return route.fulfill({ json: [project] })
    if (url.pathname === '/api/automations/workspace') return route.fulfill({ json: {
      templates: [], runs: [], serverTime: '2026-09-08T00:00:00Z', rules: [{ id: 'r', name: '隔离 Git 检查', projectId: 'project', templateVersionId: 'v', triggerType: 'GIT_HEAD_CHANGED', triggerConfig: {}, state: 'ENABLED', approvalMode: 'REVIEW_REQUIRED', version: 1,
        health: { status: failed ? 'FAILED' : 'CHECKED', lastCheckedAt: '2026-09-08T00:00:00Z', consecutiveFailures: failed ? 2 : 0, errorMessage: failed ? 'Git 检查超时，请检查项目目录后刷新。' : null },
      }],
    } })
    return route.fulfill({ json: [] })
  })
  await page.goto('/automations')
  await expect(page.getByLabel('自动化检测状态')).toContainText('检测失败 · 连续 2 次')
  failed = false
  await page.reload()
  await expect(page.getByLabel('自动化检测状态')).toContainText('检测正常')
  await expect(page.getByText('Git 检查超时')).toHaveCount(0)
})

test('退役自动化深链和刷新进入模板任务，不读取或重开历史自动化写入口', async ({ page }) => {
  const legacyRequests: { method: string; path: string }[] = []
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  page.on('request', request => {
    const path = new URL(request.url()).pathname
    if (path.startsWith('/api/automations')) legacyRequests.push({ method: request.method(), path })
  })
  await page.route('http://127.0.0.1:41773/api/**', route => {
    const path = new URL(route.request().url()).pathname
    if (path === '/api/projects/summaries') return route.fulfill({ json: [project] })
    if (path === '/api/template-tasks/catalog') return route.fulfill({ json: { templates: [], dimensions: [] } })
    if (path === '/api/template-tasks/projects') return route.fulfill({ json: { items: [project], nextCursor: null } })
    if (path === '/api/template-tasks/projects/project') return route.fulfill({ json: project })
    if (path.endsWith('/branches')) return route.fulfill({ json: { page: { items: [], nextCursor: null }, defaultBranch: null, defaultBranchId: null, remoteAvailable: false } })
    return route.fulfill({ json: [] })
  })
  await page.goto('/automations?projectId=project')
  await expect(page).toHaveURL(/\/template-tasks\?projectId=project$/)
  await expect(page.getByRole('heading', { name: '模板任务', exact: true })).toBeVisible()
  await expect(page.getByLabel('自动化检测状态')).toHaveCount(0)
  await page.reload()
  await expect(page).toHaveURL(/\/template-tasks\?projectId=project$/)
  await expect(page.getByRole('heading', { name: '模板任务', exact: true })).toBeVisible()
  expect(legacyRequests).toEqual([])
  expect(errors).toEqual([])
})
