import { expect, test } from '@playwright/test'

test('多行提交说明的预览与实际提交一致', async ({ page }) => {
  const multiline = '目标：创建arch网页\n使用场景：旧代码梳理\n验收标准：打开网页搜索'
  let submitted: unknown
  const task = {
    id: 'publication-fixture', projectId: 'project', projectName: '隔离验收', title: multiline, goal: multiline,
    branch: 'loopper/fixture', status: 'SUCCEEDED', executionResult: 'SUCCEEDED',
    loopRetryAvailable: false, cancellationAvailable: false, hasDesignHistory: false, archived: false,
    stages: [], attempts: [], createdAt: '2026-09-09T00:00:00Z', updatedAt: '2026-09-09T00:00:00Z',
  }
  const ready = {
    state: 'READY', available: true, branch: task.branch, remoteName: 'origin', provider: 'GITLAB',
    targetBranches: [], hasChanges: true, conflictCount: 0, resolvedCount: 0,
    deliveryState: 'NOT_STARTED', deliveryFinal: false, reconciliationAvailable: false,
  }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/overview')) return route.fulfill({ json: task })
    if (path.endsWith('/commit-message')) return route.fulfill({ json: { subject: multiline, aiGenerated: true } })
    if (path.endsWith('/publication')) {
      if (route.request().method() === 'POST') {
        submitted = route.request().postDataJSON()
        return route.fulfill({ json: { ...ready, state: 'PUSHED', deliveryState: 'PUSHED', hasChanges: false } })
      }
      return route.fulfill({ json: ready })
    }
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
    return route.fulfill({ json: { attempts: [], errors: [], judges: [], artifacts: [], items: [] } })
  })
  await page.goto('/tasks/publication-fixture')
  await page.getByRole('button', { name: '提交', exact: true }).click()
  await page.getByRole('textbox', { name: '4 位数字工单号' }).fill('2026')
  await page.getByRole('textbox', { name: 'AI 提交说明' }).fill(multiline)
  const expected = '#2026_目标：创建arch网页 使用场景：旧代码梳理 验收标准：打开网页搜索'
  await expect(page.locator('.commit-preview code')).toHaveText(expected)
  await page.getByRole('button', { name: '确认提交并推送', exact: true }).click()
  await page.locator('.el-message-box').getByRole('button', { name: '提交并推送', exact: true }).click()
  await expect.poll(() => submitted).toEqual({ commitMessage: expected })
  await expect(page.locator('.publication-error')).toHaveCount(0)
})
