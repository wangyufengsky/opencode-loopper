import { expect, test } from '@playwright/test'
import { requirement, execution } from '../src/components/workflow/workflowRunTestFixtures'
import { newNode } from '../src/components/workflow/graph'
import type { WorkflowPushRequest, WorkflowPushView } from '../src/types/domain'
test('用户核对远端后明确推送，未知回执重试原请求并在刷新后恢复记录', async ({ page }) => {
  const node = { ...newNode('free.write'), id: 'work', title: '开发与修正' }
  const req = requirement({ id: 'publication-push', title: '订单流程开发', state: 'COMPLETED', revision: 2, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 2; snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const branch = 'loopper/results/89b76d4b-73d0-4c90-99b5-31e13007bb28', commit = 'abcd1234'.repeat(5), url = 'https://git.example.org/研发团队/订单应用.git'
  let checks = 0, confirmations = 0, retries = 0, reads = 0, original: WorkflowPushRequest | null = null, status: WorkflowPushView | null = null
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname, method = route.request().method()
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === `/api/workflows/requirements/${req.id}`) return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/publication')) return route.fulfill({ json: { requirementId: req.id, state: 'COMMITTED', version: 3, nodeTitle: node.title, outputTitle: '代码成果', attemptState: 'SUCCEEDED', branch, message: '完成订单处理与测试', commit, createdAt: '2026-09-29T08:00:00Z' } })
    if (path.endsWith('/publication/sources')) return route.fulfill({ json: { items: [{ nodeKey: node.id, nodeTitle: node.title, attemptId: 'private-attempt', ordinal: 1, attemptState: 'SUCCEEDED', outputName: 'code', outputTitle: '代码成果', createdAt: '2026-09-29T08:00:00Z', changedFiles: 3, totalFiles: 24 }], nextCursor: null } })
    if (path.endsWith('/push/remotes')) { reads++; return route.fulfill({ json: ['origin', 'backup'] }) }
    if (path.endsWith('/push/preview')) { checks++; expect(method).toBe('POST'); expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); expect(route.request().postDataJSON()).toEqual({ remote: 'origin' }); return route.fulfill({ json: { requirementId: req.id, publicationVersion: 3, remote: 'origin', url, branch, commit, remoteCommit: null, sha256: 'a'.repeat(64) } }) }
    if (path.endsWith('/push/retry')) { retries++; expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); expect(route.request().postDataJSON()).toEqual({ expectedVersion: 7 }); status = { ...status!, state: 'PUSHED', version: 9, reasonCode: null }; return route.fulfill({ json: status }) }
    if (path.endsWith('/push') && method === 'POST') {
      confirmations++; expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); const body = route.request().postDataJSON()
      if (!original) { original = body; expect(body).toMatchObject({ expectedVersion: 3, remote: 'origin', previewSha256: 'a'.repeat(64) }); return route.abort('failed') }
      expect(body).toEqual(original); status = { requirementId: req.id, state: 'BLOCKED', version: 7, remote: 'origin', url, branch, commit, ordinal: 1, reasonCode: 'WORKFLOW_PUSH_RESULT_UNCONFIRMED' }; return route.fulfill({ json: status })
    }
    if (path.endsWith('/push')) return route.fulfill({ json: status })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`/requirements/${req.id}`)
  const panel = page.getByRole('region', { name: '需求代码成果' })
  await panel.getByRole('button', { name: '查看代码成果', exact: true }).click(); await expect(panel).toContainText('尚未推送到远端'); expect([reads, checks, confirmations, retries]).toEqual([0, 0, 0, 0])
  await panel.getByRole('button', { name: '推送到远端', exact: true }).click(); await expect(panel.getByLabel('推送远端')).toHaveValue('')
  await expect(panel.getByRole('button', { name: '收起代码成果' })).toBeDisabled(); await panel.getByLabel('推送远端').selectOption('origin')
  await panel.getByRole('button', { name: '检查推送目标' }).click(); await expect(panel).toContainText(url); expect(confirmations).toBe(0)
  await panel.screenshot({ path: 'test-results/workflow-publication-push-confirm.png' })
  await panel.getByRole('button', { name: '确认推送成果分支' }).click(); await expect(panel.getByRole('button', { name: '重试原推送确认' })).toBeVisible()
  await panel.getByRole('button', { name: '重试原推送确认' }).click(); await expect(panel).toContainText('远端结果尚未确认'); expect(confirmations).toBe(2)
  await page.reload(); await panel.getByRole('button', { name: '查看代码成果', exact: true }).click(); await expect(panel).toContainText('远端结果尚未确认'); expect(retries).toBe(0)
  await panel.getByRole('button', { name: '核对并恢复原推送' }).click(); await expect(panel).toContainText('已核对远端成果分支'); await expect(panel).not.toContainText('尚未推送'); expect([reads, checks, confirmations, retries]).toEqual([1, 1, 2, 1])
  await panel.screenshot({ path: 'test-results/workflow-publication-push-desktop.png' })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await panel.screenshot({ path: 'test-results/workflow-publication-push-mobile.png' })
})
