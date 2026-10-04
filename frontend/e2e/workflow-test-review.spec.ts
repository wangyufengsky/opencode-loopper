const narrowLayoutInScope: boolean = false
import { semanticName } from './w3/semantics'
import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowGraph, WorkflowNode } from '../src/types/workflow'
const presets = JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }
const node = { ...presets.presets.find(p => p.id === 'source.test-review')!.node, id: 'review' }
const graph: WorkflowGraph = { schemaVersion: 1, nodes: [node], edges: [], inputs: [] }
for (const failed of [false, true]) test(`场景复核${failed ? '保留未执行及缺失' : '展示独立意见和固定断言'}`, async ({ page }) => {
  const req = requirement({ id: 'review-run', title: '计算器场景复核', state: failed ? 'STALLED' : 'COMPLETED', graph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = [{ id: 'review-node', nodeKey: node.id, state: failed ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'review-attempt', version: 1, outcome: failed ? 'REVISE' : 'PASS' }]
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: failed ? 'PAUSED' : 'COMPLETED', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : null }
  const run = attempt({ id: 'review-attempt', state: failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, modelState: 'SUCCEEDED', roleName: '需求评审员' })
  let reads = 0
  const report = { version: 1, type: 'SOURCE_TEST_REVIEW', verdict: failed ? 'REVISE' : 'PASS', nativePassed: true, inputUnchanged: true, reason: '依据固定测试文件逐场景核对断言，未执行的场景不能认定覆盖。', scenarios: [{ key: 'sum', title: '正常求和', path: 'src/Calculator.java', assessment: 'COVERED', status: failed ? 'NOT_EXECUTED' : 'COVERED', reason: failed ? '对应测试被跳过，尚未证明执行结果。' : '断言验证 total(1, 2) 返回 3。', tests: [{ id: 'private-test-id', name: 'sum', status: failed ? 'SKIPPED' : 'PASSED', reportPath: 'target/surefire-reports/TEST-AddedTest.xml' }], references: [{ path: 'src/test/java/AddedTest.java', startLine: 3, endLine: 3, quote: 'assertEquals(3, Calculator.total(1, 2));' }] }, ...(failed ? [{ key: 'error', title: '异常输入', path: 'src/Calculator.java', assessment: 'MISSING', status: 'MISSING', reason: '缺少异常路径断言。', tests: [], references: [] }] : [])] }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/review-run') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/review-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) { reads++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery-hash', delivery: { summary: '完成本次独立复核。', outcome: report.verdict, outputs: { review: { kind: 'DECISION', content: report }, summary: { kind: 'TEXT', content: '逐场景核对已固定的测试结果。' } } } } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    if (path === '/api/roles') return route.fulfill({ json: { items: [], nextCursor: null } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/review-run')
  await selectWorkflowNode(page, node.title); expect(reads).toBe(0)
  await page.getByRole('button', { name: semanticName('workflow.deliverables'), exact: true }).click(); await expect.poll(() => reads).toBe(1)
  const content = page.locator('.workflow-professional-report')
  await expect(content).toContainText(failed ? '需要修订' : '复核通过'); await expect(content).toContainText('覆盖判断来自独立评审；执行结果和固定版本由程序核对'); await expect(content).not.toContainText('private-')
  if (failed) { await expect(content).toContainText('关联测试未执行'); await expect(content).toContainText('缺少对应测试'); await expect(content).not.toContainText('评审认为已覆盖') }
  else { await expect(content).toContainText('评审认为已覆盖'); await expect(content).toContainText('执行通过') }
  await content.getByText('测试代码依据（1 条）', { exact: true }).click(); await expect(content.getByText('assertEquals(3, Calculator.total(1, 2));', { exact: true })).toBeVisible()
  await page.screenshot({ path: `test-results/workflow-test-review-${failed ? 'revise' : 'passed'}-desktop.png`, fullPage: true })
  if (narrowLayoutInScope) { // OUT_OF_SCOPE: current acceptance is desktop only; historical assertions retained.
    await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-test-review-${failed ? 'revise' : 'passed'}-mobile.png`, fullPage: true })
  }
})
