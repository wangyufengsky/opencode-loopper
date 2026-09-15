import { expect, test } from '@playwright/test'

for (const width of [1440, 390]) {
  test(`第39批失败后继续后续批次，结束后统一选择重新触发 ${width}px`, async ({ page }) => {
    let ready = false
    let submitted: unknown[] = []
    let failed = [39, 42].map(ordinal => ({ id: `failed-${ordinal}`, ordinal: ordinal - 1, purpose: 'REVIEW', generation: 0, state: 'FAILED', errorMessage: '模型会话已结束，但未提交有效分析结果。', version: 7, createdAt: '2026-09-15T00:00:00Z' }))
    await page.route('http://127.0.0.1:41773/api/**', async route => {
      const path = new URL(route.request().url()).pathname
      if (path.endsWith('/overview')) return route.fulfill({ json: {
        id: 'fixture', projectId: 'p', projectName: '批次恢复示例', title: '代码审查', goal: '失败批次统一处理', status: ready ? 'WAITING_INPUT' : 'RUNNING',
        loopRetryAvailable: false, cancellationAvailable: true, hasDesignHistory: false, archived: false,
        executionMode: 'TEMPLATE_REPORT', workspacePolicy: 'ISOLATED_REPORT', attemptCount: 2, maxAttempts: 10, stages: [],
        templateProgress: { reviewBatches: 50, contributorBatches: 0, completedReviews: ready ? 48 : 38, completedContributors: 0, activeBatches: ready ? 0 : 4, failedBatches: failed.length, repairRound: 0, dualReviewRequired: false, reportCount: 0 },
        createdAt: '2026-09-15T00:00:00Z', updatedAt: '2026-09-15T00:00:00Z',
      } })
      if (path.endsWith('/failed-batches')) return route.fulfill({ json: { items: failed, facets: { retrySelectionReady: ready ? 1 : 0 } } })
      if (path.endsWith('/batches/retry')) {
        expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
        submitted.push(route.request().postDataJSON())
        failed = []; ready = false
        return route.fulfill({ json: [{ id: 'retry-39', state: 'PREPARED' }, { id: 'retry-42', state: 'PREPARED' }] })
      }
      if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
      return route.fulfill({ json: { items: [], attempts: [], errors: [], judges: [], artifacts: [] } })
    })
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/tasks/fixture')
    const panel = page.getByRole('region', { name: '批次恢复' })
    await expect(panel.getByText(/后续批次继续执行/)).toBeVisible()
    await expect(panel.getByRole('checkbox')).toHaveCount(0)
    ready = true
    await page.reload()
    await expect(panel.getByText('代码分析 · 第 39 批', { exact: true })).toBeVisible()
    await expect(panel.getByText('代码分析 · 第 42 批', { exact: true })).toBeVisible()
    await expect(panel.getByText(/第 40 批/)).toHaveCount(0)
    await panel.getByRole('checkbox', { name: '选择已加载批次' }).check()
    await page.screenshot({ path: `/tmp/loopper-batch-selection-${width}.png`, fullPage: true })
    expect(await page.locator('body').evaluate(node => node.scrollWidth <= window.innerWidth)).toBeTruthy()
    await panel.getByRole('button', { name: '重新触发所选批次（2）' }).click()
    await expect(panel).toHaveCount(0)
    expect(submitted).toEqual([{ batches: [{ id: 'failed-39', expectedVersion: 7 }, { id: 'failed-42', expectedVersion: 7 }] }])
  })
}
