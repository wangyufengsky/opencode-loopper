const narrowLayoutInScope: boolean = false
import { semanticName } from './w3/semantics'
import { expect, test, type Page } from '@playwright/test'
import { commandPreset } from '../src/components/workflow/workflowTestFixtures'
import { attempt, execution, requirement } from '../src/components/workflow/workflowRunTestFixtures'

// Explicit HTTP fixture for browser interaction. The real process/SQLite chain has separate integration coverage.
async function fixture(page: Page, loseStopReceipt = false, preparationFailed = false) {
  const node = { ...commandPreset().node, id: 'verify' }, req = requirement({ state: 'STALLED' }), state = execution('STALLED')
  req.graph.nodes = [node]
  const run = attempt({ state: 'RUNNING', commandState: 'RUNNING', commandVersion: 7, suspended: true, errorCode: 'WORKFLOW_COMMAND_RECOVERY_REQUIRED' })
  state.execution.nodes = [{ id: 'node', nodeKey: node.id, state: 'ACTIVE', attemptCount: 1, latestAttemptId: run.id, version: 1, outcome: null }]
  state.control = { ...state.control, configured: true, mode: 'CONTINUOUS', state: 'STALLED', reasonCode: 'WORKFLOW_COMMAND_RECOVERY_REQUIRED' }
  const mutations: Array<{ action: string; body: unknown }> = []; let evidenceReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/req') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: state.execution.state, version: state.execution.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: state })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/run')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/command/resume') || path.endsWith('/command/stop')) {
      const action = path.endsWith('/stop') ? 'stop' : 'resume'; expect(route.request().headers()['x-loopper-local-ui']).toBe('1')
      mutations.push({ action, body: route.request().postDataJSON() }); expect(mutations[0]!.body).toMatchObject({ expectedVersion: 7 })
      run.commandVersion = 8; run.suspended = false; run.errorCode = null
      if (action === 'stop') { run.state = 'STOPPING'; run.commandState = 'STOPPING'; if (loseStopReceipt && mutations.length === 1) return route.abort('failed') }
      return route.fulfill({ json: { attemptId: run.id, state: run.commandState, version: run.commandVersion, suspended: false, errorCode: null } })
    }
    if (path.endsWith('/result')) return route.fulfill({ json: { attemptId: run.id, state: 'FAILED', sha256: 'report-hash', delivery: { summary: '检查命令未通过。', outcome: 'FAIL', outputs: { report: { kind: 'JSON', content: { version: 1, type: 'COMMAND', valid: true, passed: false, exitCode: 3, timedOut: false, cancelled: false, outputTruncated: false, errorCode: '', output: '1 failed, 2 passed', reportExcerpt: false } } } } } })
    if (path.endsWith('/command/evidence')) {
      evidenceReads++
      if (preparationFailed) return route.fulfill({ json: { attemptId: run.id,
        request: { id: run.id, directory: '/private/inspection', argv: ['npm', 'test'], timeoutSeconds: 120,
          preparations: [{ name: 'NPM_INSTALL', directory: '/private/inspection', argv: ['npm', 'ci', '--no-audit', '--no-fund'] }] },
        result: { exitCode: null, launched: false, timedOut: false, cancelled: false, outputTruncated: false, stopConfirmed: true, output: '', error: 'COMMAND_PREPARATION_FAILED', children: [],
          preparations: [{ exitCode: 7, launched: true, timedOut: false, cancelled: false, outputTruncated: false, stopConfirmed: true, output: '无法取得项目锁定的测试依赖，请检查配置与网络后重试。', error: '', children: [] }] } } })
      return route.fulfill({ json: { attemptId: run.id, requestSha256: 'request-hash', resultSha256: 'result-hash', registration: { requestSha256: 'request-hash', worker: { pid: 123, startedAt: '2026-09-28T00:00:00Z' } }, request: { id: run.id, directory: '/private/inspection', argv: ['mvn', 'test', '-Dtest=OrderFlowTest'], timeoutSeconds: 120 }, result: { requestSha256: 'request-hash', worker: { pid: 123, startedAt: '2026-09-28T00:00:00Z' }, exitCode: 3, launched: true, timedOut: false, cancelled: false, outputTruncated: false, stopConfirmed: true, output: 'OrderFlowTest\n1 failed, 2 passed\nAssertion: expected ready', error: '', children: [] } } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    if (path === '/api/roles') return route.fulfill({ json: { items: [], nextCursor: null } })
    return route.fulfill({ json: [] })
  })
  return { mutations, evidenceReads: () => evidenceReads, finish: () => { run.state = 'FAILED'; run.commandState = 'FAILED'; run.commandVersion = 12; run.deliveryAccepted = true; run.stopConfirmed = true; run.suspended = false; run.errorCode = null; state.execution.nodes[0]!.state = 'FAILED'; state.execution.nodes[0]!.outcome = 'FAIL'; state.control.reasonCode = 'WORKFLOW_RETRY_EXHAUSTED' } }
}

test('原命令恢复后可查看失败报告和执行记录，按需读取且不显示模型操作', async ({ page }) => {
  const data = await fixture(page); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/req'); await page.locator('.workflow-node').click()
  const panel = page.getByRole('region', { name: '节点执行详情' })
  await expect(panel).toContainText('命令检查需要恢复'); expect(data.evidenceReads()).toBe(0)
  await page.getByRole('button', { name: semanticName('workflow.nodeResume'), exact: true }).click(); await expect(panel.getByRole('button', { name: semanticName('workflow.nodeResume'), exact: true })).toHaveCount(0)
  expect(data.mutations).toHaveLength(1); expect(data.mutations[0]!.action).toBe('resume')
  data.finish(); await page.reload(); await page.locator('.workflow-node').click(); await page.getByRole('button', { name: semanticName('workflow.deliverables'), exact: true }).click()
  await expect(panel.locator('.workflow-command-report').getByText('检查未通过', { exact: true })).toBeVisible(); await expect(panel.getByLabel('命令输出摘要')).toContainText('1 failed, 2 passed'); expect(data.evidenceReads()).toBe(0)
  await page.screenshot({ path: 'test-results/workflow-command-report-desktop.png', fullPage: true })
  await page.getByRole('button', { name: semanticName('workflow.commandEvidence'), exact: true }).click(); await expect(panel.getByLabel('已保存命令输出')).toContainText('OrderFlowTest'); expect(data.evidenceReads()).toBe(1)
  await expect(panel).toContainText('已取得停止证明'); await expect(panel.getByRole('button', { name: semanticName('workflow.modelActivity'), exact: true })).toHaveCount(0); await expect(panel.getByRole('button', { name: semanticName('workflow.nodeStop'), exact: true })).toHaveCount(0)
  await page.screenshot({ path: 'test-results/workflow-command-evidence-desktop.png', fullPage: true })
  if (narrowLayoutInScope) { // OUT_OF_SCOPE: current acceptance is desktop only; historical assertions retained.
    await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true); await page.screenshot({ path: 'test-results/workflow-command-evidence-mobile.png', fullPage: true })
  }
})

test('命令停止回执未知时重放原操作，收到回执后保持等待停止确认', async ({ page }) => {
  const data = await fixture(page, true); page.on('dialog', dialog => dialog.accept()); await page.goto('/requirements/req'); await page.locator('.workflow-node').click()
  await page.getByRole('button', { name: semanticName('workflow.nodeStop'), exact: true }).click(); await page.getByRole('dialog', { name: '停止当前节点' }).getByRole('button', { name: semanticName('workflow.nodeStop'), exact: true }).click(); await page.getByRole('button', { name: semanticName('receipt.retryOriginal'), exact: true }).click()
  await expect(page.getByText('等待停止确认', { exact: true })).toBeVisible(); expect(data.mutations).toHaveLength(2); expect(data.mutations[1]).toEqual(data.mutations[0])
  await expect(page.getByRole('button', { name: semanticName('workflow.nodeResume'), exact: true })).toHaveCount(0)
})

test('依赖准备失败单独显示，检查命令未启动且输出可展开', async ({ page }) => {
  const data = await fixture(page, false, true); data.finish(); await page.setViewportSize({ width: 1600, height: 1000 })
  await page.goto('/requirements/req'); await page.locator('.workflow-node').click(); expect(data.evidenceReads()).toBe(0)
  await page.getByRole('button', { name: semanticName('workflow.commandEvidence'), exact: true }).click(); expect(data.evidenceReads()).toBe(1)
  const evidence = page.locator('.workflow-command-evidence'), preparation = page.getByLabel('依赖准备结果')
  await expect(preparation).toContainText('进程退出码：7'); await expect(evidence).toContainText('检查命令没有启动')
  await expect(evidence).toContainText('依赖准备未完成'); await expect(evidence).not.toContainText('NPM_INSTALL')
  await preparation.getByText('查看准备命令和输出', { exact: true }).click()
  await expect(preparation.getByRole('textbox', { name: '准备步骤输出', exact: true })).toBeVisible()
  await expect(evidence).toContainText('总执行时限（包含依赖准备）')
  await evidence.getByText('依赖准备未完成，检查命令没有启动。请查看准备步骤的结果并处理后重试。', { exact: true }).scrollIntoViewIfNeeded()
  await page.screenshot({ path: 'test-results/workflow-preparation-desktop.png', fullPage: true })
  if (narrowLayoutInScope) { // OUT_OF_SCOPE: current acceptance is desktop only; historical assertions retained.
    await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-preparation-mobile.png', fullPage: true })
  }
})
