import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
const source = { fileId: 'DOC-1', section: 1 }
for (const mode of ['assessment', 'pass', 'revise']) test(`需求代码评审 ${mode} 在画布按需读取，保留真实意见`, async ({ page }) => {
  const review = mode !== 'assessment', approved = mode === 'pass'
  const node = { ...presets.find(value => value.id === (review ? 'document.direct-review-check' : 'document.direct-review'))!.node, id: 'review' }
  const req = requirement({ id: 'document-review-fixture', title: '订单需求与代码核对', state: 'COMPLETED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'review-run', nodeKey: node.id, state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'review-attempt', version: 2, outcome: review ? approved ? 'PASS' : 'REVISE' : null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const run = attempt({ id: 'review-attempt', state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, roleName: '需求代码评审员', roleRevisionNumber: 1, modelState: 'COMPLETED', modelVersion: 5 })
  const content = review ? { approved, reviewedRequirementKeys: ['RQ-1'], reviewedFindingKeys: [], checkedSections: [source], corrections: approved ? [] : [{ requirementKey: 'RQ-1', findingKey: null, source, detail: '补充校验条件的入口调用证据' }] }
    : { entries: [{ title: '金额正数校验', statement: '订单金额必须大于零', sources: [source], issues: [], assessment: { requirementKey: 'RQ-1', conclusion: 'SATISFIED', rationale: '固定代码包含金额校验', checkedPaths: ['src/Order.java'], evidence: [{ path: 'src/Order.java', blobSha: 'private-hash', startLine: 1, endLine: 1, quote: 'boolean allowed = amount > 0;' }], testSourceCoverage: '尚无测试执行证据', missingEntryEvidence: null, limitations: ['没有运行代码'] } }], findings: [], skippedSections: [], limitations: ['外部服务不在本次范围'] }
  let resultReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/document-review-fixture') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/review-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) { resultReads++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery-hash', delivery: { summary: '按用户选择完成本批工作', outcome: review ? approved ? 'PASS' : 'REVISE' : null, outputs: { [review ? 'review' : 'assessment']: { kind: review ? 'DECISION' : 'JSON', content } } } } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/document-review-fixture')
  await selectWorkflowNode(page, node.title); expect(resultReads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click(); const report = page.locator('.workflow-document-review-report')
  await expect(report).toContainText(review ? approved ? '复核通过' : '需要返修' : '金额正数校验'); expect(resultReads).toBe(1)
  if (!review) { await expect(report).toContainText('静态代码评审 · 未运行测试'); await report.getByText('依据与局限', { exact: true }).click(); await expect(report.locator('pre')).toContainText('amount > 0') }
  if (mode === 'revise') { await expect(report).not.toContainText('复核通过'); await expect(report).toContainText('补充校验条件的入口调用证据') }
  await expect(report).not.toContainText('private-'); await expect(report).not.toContainText('DOC-1')
  await page.screenshot({ path: `test-results/workflow-document-review-${mode}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-document-review-${mode}-mobile.png`, fullPage: true })
})
