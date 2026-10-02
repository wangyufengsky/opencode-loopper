import { workflowTool } from './fixtures/workflowNavigation'
import { expect, test, type Page } from '@playwright/test'
import { commandPreset } from '../src/components/workflow/workflowTestFixtures'
import { attempt, execution, requirement } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowFinish, WorkflowFinishRequest } from '../src/types/workflow'

// Explicit HTTP responses qualify canvas interaction only. Backend tests exercise real SQLite/Git/native processes.
async function fixture(page: Page, loseReceipt = false) {
  const node = { ...commandPreset().node, id: 'verify' }, req = requirement({ state: 'STALLED' }), snapshot = execution('STALLED')
  req.graph.nodes = [node]
  const run = attempt({ state: 'RUNNING', commandState: 'RUNNING', commandVersion: 7, suspended: true, errorCode: 'WORKFLOW_COMMAND_STOP_UNCONFIRMED' })
  snapshot.execution.nodes = [{ id: 'node', nodeKey: node.id, state: 'ACTIVE', attemptCount: 1, latestAttemptId: run.id, version: 1, outcome: null }]
  snapshot.control = { ...snapshot.control, configured: true, mode: 'CONTINUOUS', state: 'STALLED', reasonCode: 'WORKFLOW_COMMAND_STOP_UNCONFIRMED' }
  const saved: WorkflowFinish = { requirementId: req.id, state: 'STALLED', version: snapshot.execution.version, intent: null, pending: { attempts: 0, resources: 0 } }
  const mutations: WorkflowFinishRequest[] = []
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/req') return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/finish')) {
      if (route.request().method() === 'GET') return route.fulfill({ json: saved })
      expect(route.request().headers()['x-loopper-local-ui']).toBe('1'); const body = route.request().postDataJSON() as WorkflowFinishRequest; mutations.push(body)
      if (!saved.intent) {
        expect(body.expectedVersion).toBe(snapshot.execution.version)
        saved.intent = { requirementId: req.id, targetState: body.target, reason: body.reason, planRevision: req.revision, requestedVersion: body.expectedVersion, requestedAt: '2026-09-28T12:00:00Z', finalizedAt: null }
        saved.state = req.state = snapshot.execution.state = 'STOPPING'; saved.version = ++snapshot.execution.version
        saved.pending = { attempts: 1, resources: 1 }; snapshot.control.state = 'PAUSED'; snapshot.control.reasonCode = 'WORKFLOW_FINISHING'
        run.state = run.commandState = 'STOPPING'
        if (loseReceipt) return route.abort('failed')
      }
      return route.fulfill({ json: { id: req.id, revision: req.revision, version: saved.version, layoutVersion: req.layoutVersion, state: saved.state } })
    }
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/run')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  return { mutations, finish: () => {
    saved.state = req.state = snapshot.execution.state = saved.intent!.targetState; saved.intent!.finalizedAt = '2026-09-28T12:01:00Z'
    saved.version = ++snapshot.execution.version; saved.pending = { attempts: 0, resources: 0 }; snapshot.control.state = 'DONE'; snapshot.control.reasonCode = 'WORKFLOW_USER_FINISHED'
    run.state = run.commandState = 'CANCELLED'; run.stopConfirmed = true; run.suspended = false; run.errorCode = null; snapshot.execution.nodes[0]!.state = 'CANCELLED'
  } }
}
test('画布明确确认人工成功，停止未知时仍可查看原节点并在重开后恢复结束记录', async ({ page }) => {
  const data = await fixture(page); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/req')
  await workflowTool(page, '提前结束需求')
  await expect(page.getByRole('button', { name: '确认结束需求', exact: true })).toBeDisabled()
  await page.getByLabel('结束结果', { exact: true }).selectOption('COMPLETED'); await page.getByLabel('结束原因', { exact: true }).fill('已保存阶段成果，本次按人工决定结束。')
  await expect(page.getByRole('button', { name: '连续执行', exact: true })).toBeDisabled()
  await page.screenshot({ path: 'test-results/workflow-finish-confirm-desktop.png', fullPage: true })
  await page.getByRole('button', { name: '确认结束需求', exact: true }).click()
  const panel = page.getByRole('region', { name: '结束需求', exact: true })
  await expect(panel).toContainText('正在结束需求'); await expect(panel).toContainText('1 次节点执行，1 项运行或目录记录')
  await page.locator('.workflow-node').click(); await expect(page.getByRole('button', { name: '重新检查停止', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: '连续执行', exact: true })).toHaveCount(0)
  await page.screenshot({ path: 'test-results/workflow-finish-stopping-desktop.png', fullPage: true })
  await page.reload(); await expect(panel).toContainText('正在结束需求'); expect(data.mutations).toHaveLength(1)
  data.finish(); await page.reload(); await expect(panel).toContainText('人工认定成功'); await expect(panel).toContainText('原有交付物、检查与审查结论均保留')
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-finish-completed-mobile.png', fullPage: true })
})
test('结束回执丢失后重试同一操作，用户决定不会重复应用', async ({ page }) => {
  const data = await fixture(page, true); await page.goto('/requirements/req')
  await workflowTool(page, '提前结束需求'); await page.getByLabel('结束原因', { exact: true }).fill('取消本次需求')
  await page.getByRole('button', { name: '确认结束需求', exact: true }).click(); await page.getByRole('button', { name: '重试原结束操作', exact: true }).click()
  await expect(page.getByRole('region', { name: '结束需求', exact: true })).toContainText('正在结束需求')
  expect(data.mutations).toHaveLength(2); expect(data.mutations[1]).toEqual(data.mutations[0])
})
