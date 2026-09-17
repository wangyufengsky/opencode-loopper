import { expect, test } from '@playwright/test'

for (const width of [1440, 390]) {
  test(`等待任务显示失败清单、原批次恢复与独立重查 ${width}px`, async ({ page }) => {
    const posted: { path: string; body: unknown }[] = []
    let resumed = false
    const row = {
      batchId: 'unfinished-11', batchVersion: 9, sessionKey: 'execution:session-11', purpose: 'SNAPSHOT_ANALYSIS', ordinal: 11,
      generation: 0, stageOrdinal: 3, state: 'RUNNING', phase: 'DISCONNECTED', reason: '连接或读取超时，将保留原会话并重新检查',
      connected: false, automaticRetries: 1, retryLimit: 3, transportFailures: 2, nextCheckAt: '2026-09-17T04:39:00Z',
      canCheck: true, canStop: true, canFinalize: false,
    }
    await page.route('http://127.0.0.1:41773/api/**', async route => {
      const path = new URL(route.request().url()).pathname
      if (route.request().method() === 'POST') {
        expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
        posted.push({ path, body: route.request().postDataJSON() })
        if (path.endsWith('/recheck')) resumed = true
        return route.fulfill({ json: path.endsWith('/check') ? row : { id: 'fixture', state: 'RUNNING' } })
      }
      if (path.endsWith('/overview')) return route.fulfill({ json: {
        id: 'fixture', projectId: 'p', projectName: '恢复验收', title: '原批次恢复', goal: '', status: resumed ? 'RUNNING' : 'WAITING_INPUT',
        executionMode: 'TEMPLATE_REPORT', workspacePolicy: 'ISOLATED_REPORT', cancellationAvailable: true, loopRetryAvailable: false, hasDesignHistory: false, archived: false,
        attemptCount: 1, maxAttempts: 10, stages: [], version: 21, createdAt: '2026-09-17T04:00:00Z', updatedAt: '2026-09-17T04:38:00Z',
        templateProgress: { reviewBatches: 20, contributorBatches: 0, completedReviews: 7, completedContributors: 0, activeBatches: 1, failedBatches: 3, reportCount: 0 },
      } })
      if (path.endsWith('/failed-batches')) return route.fulfill({ json: {
        items: [8, 9, 10].map(i => ({ id: `failed-${i}`, ordinal: i - 1, purpose: 'SNAPSHOT_ANALYSIS', version: 7, generation: 0, state: 'STOPPED', errorMessage: '会话已确认停止，未完成分析' })),
        facets: { retrySelectionReady: 0, resumeAvailable: resumed ? 0 : 1, taskVersion: 21, blockingBatches: 1 },
      } })
      if (path.endsWith('/session-diagnostics')) return route.fulfill({ json: { items: [row] } })
      if (path.includes('/session-diagnostics/')) return route.fulfill({ json: row })
      if (path.endsWith('/sessions')) return route.fulfill({ json: [] })
      if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
      return route.fulfill({ json: { items: [], attempts: [], errors: [], judges: [], artifacts: [] } })
    })
    await page.setViewportSize({ width, height: 1100 })
    await page.goto('/tasks/fixture')
    const recovery = page.getByRole('region', { name: '批次恢复' })
    await expect(recovery).toContainText('任务已暂停')
    await expect(recovery).toContainText('还有 1 个批次未确认结束')
    await expect(recovery).toContainText('功能分析 · 第 8 批')
    await expect(recovery.getByRole('checkbox')).toHaveCount(0)
    await expect(recovery).not.toContainText('后续批次继续执行')
    const diagnostics = page.getByRole('region', { name: '批次运行诊断' })
    await expect(diagnostics).toContainText('本轮自动重试已用 1/3 次')
    await expect(diagnostics).toContainText('连续 2 次未能完成检查')
    await diagnostics.getByRole('button', { name: '重新检查会话' }).click()
    await expect(diagnostics).toContainText('已请求重新检查原会话')
    await page.screenshot({ path: `/tmp/loopper-batch-resilience-${width}.png`, fullPage: true })
    expect(await page.locator('body').evaluate(node => node.scrollWidth <= innerWidth)).toBeTruthy()
    await recovery.getByRole('button', { name: '重新检查并恢复原批次' }).click()
    await expect(recovery.getByRole('button', { name: '重新检查并恢复原批次' })).toHaveCount(0)
    expect(posted).toEqual([
      { path: '/api/tasks/fixture/session-diagnostics/unfinished-11/check', body: { expectedVersion: 9 } },
      { path: '/api/template-tasks/fixture/recheck', body: { expectedVersion: 21 } },
    ])
  })
}
