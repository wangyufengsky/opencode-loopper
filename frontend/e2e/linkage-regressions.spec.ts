import { expect, test } from '@playwright/test'

const origin = 'http://127.0.0.1:41773'
const now = '2026-09-12T08:00:00Z'
const task = (id: string, title: string, status: 'RUNNING' | 'QUEUED') => ({
  id, title, status, projectId: 'linkage-project', projectName: '前后端联动验收',
  goal: '只使用隔离 API fixture 验证浏览器交互', branch: 'DIRECT', worktreePath: '/fixture',
  version: 1, loopRetryAvailable: false, cancellationAvailable: true, hasDesignHistory: false,
  archived: false, attemptCount: 0, maxAttempts: 3, stages: [], attempts: [], errors: [],
  judges: [], artifacts: [], createdAt: now, updatedAt: now,
})

test('任务 A 的取消确认在浏览器后退到任务 B 后失效', async ({ page }) => {
  const holder = task('linkage-holder', '联动验收任务 A', 'RUNNING')
  const waiter = task('linkage-waiter', '联动验收任务 B', 'QUEUED')
  const cancelRequests: string[] = []
  await page.route(`${origin}/api/**`, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (request.method() === 'POST' && path.endsWith('/cancel')) {
      cancelRequests.push(path)
      return route.fulfill({ json: { ...waiter, status: 'CANCELLED', version: 2 } })
    }
    if (path === `/api/tasks/${holder.id}/overview`) return route.fulfill({ json: holder })
    if (path === `/api/tasks/${waiter.id}/overview`) return route.fulfill({ json: waiter })
    if (path === `/api/tasks/${waiter.id}/queue`) return route.fulfill({ json: {
      taskId: waiter.id, state: 'QUEUED', queuePosition: 1, leaseState: 'HELD',
      holderTaskId: holder.id, holderTaskTitle: holder.title, holderTaskState: holder.status,
      holderArchived: false, reconcileAvailable: false,
    } })
    if (path.endsWith('/audit')) return route.fulfill({ json: { attempts: [], errors: [], judges: [], artifacts: [] } })
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
    // Includes session-monitor and global story-accounting reads; no request reaches a Provider.
    return route.fulfill({ json: [] })
  })

  await page.goto(`/tasks/${waiter.id}`)
  await expect(page.getByRole('heading', { name: waiter.title, exact: true })).toBeVisible()
  await page.getByRole('button', { name: holder.title, exact: true }).click()
  await expect(page).toHaveURL(`/tasks/${holder.id}`)
  await expect(page.getByRole('heading', { name: holder.title, exact: true })).toBeVisible()
  await page.getByRole('button', { name: '取消任务', exact: true }).click()
  const confirmation = page.locator('.el-message-box')
  await expect(confirmation).toContainText('取消当前任务？')

  // This is a real history navigation while the real ElementPlus modal remains open.
  // Vue reuses TaskDetailView for the other :id, which reproduced the incorrect target.
  await page.goBack()
  await expect(page).toHaveURL(`/tasks/${waiter.id}`)
  await expect(page.getByRole('heading', { name: waiter.title, exact: true })).toBeVisible()
  await expect(confirmation).toBeVisible()
  await confirmation.getByRole('button', { name: '取消任务', exact: true }).click()
  await expect(confirmation).toBeHidden()
  await expect(page.getByRole('heading', { name: '排队状态', exact: true })).toBeVisible()
  expect(cancelRequests).toEqual([])
})

test('待处理中心的提交失败在重新核验和自动刷新成功后仍可见', async ({ page }) => {
  const permission = {
    id: 'linkage-permission', kind: 'PERMISSION', state: 'PENDING', taskId: 'linkage-holder',
    sessionId: 'linkage-session', externalRequestId: 'permission-request', version: 4,
    createdAt: now, updatedAt: now,
    payload: { permission: 'bash', patterns: ['git status'], metadata: {}, title: '联动验收权限', hardDenied: false },
  }
  const failureMessage = '投递结果尚未确认，请等待重新核验'
  let failedSubmission = false
  let successfulReadsAfterFailure = 0
  const submissions: Array<{ action: string; version: number }> = []
  await page.route(`${origin}/api/**`, async route => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === `/api/interactions/${permission.id}/resolve`) {
      submissions.push(request.postDataJSON() as { action: string; version: number })
      expect(request.method()).toBe('POST')
      expect(request.headers()['x-loopper-local-ui']).toBe('1')
      failedSubmission = true
      return route.fulfill({ status: 409, contentType: 'application/problem+json', json: {
        status: 409, title: '交互提交待核验', detail: failureMessage,
      } })
    }
    if (path === '/api/interactions') {
      if (failedSubmission) successfulReadsAfterFailure += 1
      return route.fulfill({ json: failedSubmission ? [] : [permission] })
    }
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
    return route.fulfill({ json: [] })
  })

  await page.goto('/inbox')
  await expect(page.getByRole('heading', { name: '联动验收权限', exact: true })).toBeVisible()
  await page.getByRole('button', { name: '仅本次允许', exact: true }).click()
  await expect(page.getByText(failureMessage, { exact: true })).toBeVisible()
  await expect(page.getByText('目前没有待处理项', { exact: true })).toBeVisible()
  expect(submissions).toEqual([{ action: 'ONCE', version: 4 }])
  // First read reconciles the failed command; the second is the real scheduled poll.
  await expect.poll(() => successfulReadsAfterFailure).toBeGreaterThanOrEqual(2)
  await expect(page.getByText(failureMessage, { exact: true })).toBeVisible()
  expect(submissions).toHaveLength(1)
})
