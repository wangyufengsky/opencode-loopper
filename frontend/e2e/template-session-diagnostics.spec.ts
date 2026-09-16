import { expect, test } from '@playwright/test'

for (const width of [1440, 390]) {
  test(`已接受但忙碌的批次可独立收尾，未知请求复用命令标识 ${width}px`, async ({ page }) => {
    const requests: { batchId: string; body: { action: string; expectedVersion: number; commandId: string } }[] = []
    const filters: string[] = []
    const diagnostic = (ordinal: number, accepted: boolean) => ({
      batchId: `batch-${ordinal}`, batchVersion: 7, sessionKey: `execution:local-${ordinal}`, localSessionId: `local-${ordinal}`,
      externalSessionId: `ses_remote-${ordinal}`, purpose: 'SNAPSHOT_LINKS', ordinal, generation: 1, stageOrdinal: 2,
      state: 'RUNNING', phase: accepted ? 'ACCEPTED_WAITING_STOP' : 'STALLED',
      reason: accepted ? '结果已接受，等待会话结束；超出收尾窗口将自动请求停止' : '至少五分钟没有观察到新活动，疑似停滞；尚未判定执行失败',
      acceptedAt: accepted ? '2026-09-16T01:39:31Z' : null, observedAt: '2026-09-16T02:28:54Z',
      lastActivityAt: '2026-09-16T01:39:31Z', lastProgressAt: '2026-09-16T01:39:31Z', remoteState: 'busy', connected: true,
      stopProof: null, stopConfirmedAt: null, canFinalize: accepted, canStop: !accepted,
      candidateAccepted: accepted, submissionRevision: accepted ? 1 : 0,
      worktreePath: '/data/template-tasks/fixture', requestMessageId: `msg-${ordinal}`, recoveryRequestedAt: null, recoveryAction: null,
    })
    const rows = [diagnostic(12, true), diagnostic(13, false)]
    const sessions = [12, 13, 14].map(ordinal => ({
      key: `execution:local-${ordinal}`, kind: 'IMPLEMENTATION', label: 'Implementation Session', localSessionId: `local-${ordinal}`,
      externalSessionId: `ses_remote-${ordinal}`, state: ordinal === 14 ? 'COMPLETED' : 'RUNNING', stageOrdinal: 2,
      createdAt: '2026-09-16T01:30:00Z', templateBatch: { purpose: 'SNAPSHOT_LINKS', ordinal, repairRound: 0, cleanup: false },
    }))
    await page.route('http://127.0.0.1:41773/api/**', async route => {
      const url = new URL(route.request().url())
      const path = decodeURIComponent(url.pathname)
      if (path.endsWith('/overview')) return route.fulfill({ json: {
        id: 'fixture', projectId: 'p', projectName: '会话收尾验收', title: '百批次任务的独立恢复', goal: '保留已完成批次，只恢复目标会话',
        status: 'RUNNING', executionMode: 'TEMPLATE_REPORT', workspacePolicy: 'ISOLATED_REPORT',
        loopRetryAvailable: false, cancellationAvailable: true, hasDesignHistory: false, archived: false,
        attemptCount: 1, maxAttempts: 10, stages: [], version: 2,
        templateProgress: { reviewBatches: 104, contributorBatches: 0, completedReviews: 102, completedContributors: 0,
          activeBatches: 2, failedBatches: 0, repairRound: 0, dualReviewRequired: false, reportCount: 0 },
        createdAt: '2026-09-16T01:00:00Z', updatedAt: '2026-09-16T02:28:54Z',
      } })
      if (path.endsWith('/session-diagnostics')) {
        filters.push(url.searchParams.get('filter') ?? '')
        expect(url.searchParams.get('limit')).toBe('50')
        return route.fulfill({ json: { items: rows.map(({ worktreePath: _path, requestMessageId: _message, ...row }) => row), facets: {}, nextCursor: null } })
      }
      const recovery = path.match(/\/session-diagnostics\/(batch-\d+)\/recover$/)
      if (recovery) {
        expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
        const body = route.request().postDataJSON()
        requests.push({ batchId: recovery[1]!, body })
        if (requests.length === 1) return route.abort('failed')
        const row = rows.find(item => item.batchId === recovery[1])!
        row.phase = 'STOP_REQUESTED'; row.reason = '已请求停止当前批次，等待停止确认'
        row.canFinalize = false; row.canStop = false
        return route.fulfill({ json: row })
      }
      const detail = path.match(/\/session-diagnostics\/(batch-\d+)$/)
      if (detail) return route.fulfill({ json: rows.find(row => row.batchId === detail[1]) })
      if (path.endsWith('/sessions')) return route.fulfill({ json: sessions })
      if (path.includes('/sessions/execution:')) {
        const session = sessions.find(item => path.endsWith(item.key))!
        return route.fulfill({ json: { session, remoteState: 'busy', live: true, observedAt: '2026-09-16T02:28:54Z', parts: [],
          pendingQuestions: [], todoCapability: 'UNAVAILABLE', todos: [], todoTruncated: false, usage: { totalTokens: 30538143, unknownUsageCount: 0 } } })
      }
      if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: '' })
      return route.fulfill({ json: { items: [], attempts: [], errors: [], judges: [], artifacts: [] } })
    })
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/tasks/fixture')
    const panel = page.getByRole('region', { name: '批次运行诊断' })
    await expect(panel.getByText('结果已接受，等待会话结束', { exact: true })).toBeVisible()
    await expect(panel.getByText('49 分钟无新活动', { exact: false }).first()).toBeVisible()
    expect(filters[0]).toBe('ATTENTION')
    await panel.getByRole('button', { name: '未完成', exact: true }).click()
    await expect.poll(() => filters.at(-1)).toBe('ACTIVE')
    const accepted = panel.locator('article').filter({ hasText: '阶段 2 · 衔接规划 · 第 12 批' })
    const unaccepted = panel.locator('article').filter({ hasText: '阶段 2 · 衔接规划 · 第 13 批' })
    await accepted.getByRole('button', { name: '查看诊断详情' }).click()
    await expect(accepted.locator('pre')).toContainText('"sessionKey": "execution:local-12"')
    await expect(accepted.locator('pre')).toContainText('"externalSessionId": "ses_remote-12"')
    await expect(accepted.locator('pre')).toContainText('"requestMessageId": "msg-12"')
    await expect(accepted.locator('pre')).not.toContainText('password')
    await page.screenshot({ path: `/tmp/loopper-session-diagnostics-${width}-before.png`, fullPage: true })
    await panel.screenshot({ path: `/tmp/loopper-session-diagnostics-${width}-panel-before.png` })
    expect(await page.locator('body').evaluate(node => node.scrollWidth <= window.innerWidth)).toBeTruthy()
    await accepted.getByRole('button', { name: '查看对应会话' }).click()
    await expect(page.locator('.session-option.selected')).toContainText('第 12 批')
    await accepted.getByRole('button', { name: '结束会话并收尾' }).click()
    await expect(panel.getByRole('alert')).toBeVisible()
    await panel.getByRole('button', { name: '刷新状态' }).click()
    await accepted.getByRole('button', { name: '结束会话并收尾' }).click()
    await expect(accepted.getByText('已请求停止', { exact: true })).toBeVisible()
    expect(requests).toHaveLength(2)
    expect(requests[0]!.body).toMatchObject({ action: 'FINALIZE', expectedVersion: 7 })
    expect(requests[1]).toEqual(requests[0])
    expect(requests[0]!.batchId).toBe('batch-12')
    await unaccepted.getByRole('button', { name: '停止此批次' }).click()
    const confirmation = page.getByRole('dialog', { name: '停止此批次' })
    await expect(confirmation).toBeVisible()
    await confirmation.getByRole('button', { name: '确认停止此批次' }).click()
    await expect(unaccepted.getByText('已请求停止', { exact: true })).toBeVisible()
    await expect(confirmation).toBeHidden()
    expect(requests).toHaveLength(3)
    expect(requests[2]).toMatchObject({ batchId: 'batch-13', body: { action: 'STOP', expectedVersion: 7 } })
    expect(requests[2]!.body.commandId).not.toBe(requests[0]!.body.commandId)
    expect(requests.some(request => request.batchId === 'batch-14')).toBe(false)
    await accepted.getByRole('button', { name: '收起诊断' }).click()
    await panel.locator('.diagnostic-rows').evaluate(node => { node.scrollTop = 0 })
    await page.screenshot({ path: `/tmp/loopper-session-diagnostics-${width}-after.png`, fullPage: true })
    await panel.screenshot({ path: `/tmp/loopper-session-diagnostics-${width}-panel-after.png` })
  })
}
