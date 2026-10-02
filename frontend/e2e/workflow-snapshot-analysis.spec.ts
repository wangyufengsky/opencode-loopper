import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
import { snapshotAnalysis, snapshotReview } from '../src/components/workflow/snapshotTestFixtures'
for (const mode of ['analysis', 'review', 'reuse']) test(`版本${mode === 'review' ? '问题复核' : mode === 'reuse' ? '复用来源' : '代码分析'}交付按需展开且窄屏可读`, async ({ page }) => {
  const review = mode === 'review', reused = mode === 'reuse'
  const node: WorkflowNode = { ...presets.find(p => p.id === (review ? 'snapshot.review' : 'snapshot.analyze'))!.node, id: 'analysis' }
  const req = requirement({ id: 'snapshot-analysis', title: '固定版本代码分析', state: 'COMPLETED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'private-node', nodeKey: node.id, state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'private-attempt', version: 3, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const run = attempt({ id: 'private-attempt', state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, roleName: '源码评审角色', roleRevisionNumber: 1, modelState: 'SUCCEEDED', modelVersion: 3 })
  const analysis = review ? snapshotReview : reused ? { ...snapshotAnalysis, reuse: { sourceRequirementId: 'private-source', sourceAttemptId: 'private-source-attempt', sourceTitle: '上周固定版本审查', sourceNodeTitle: '第一批分析', sourceDeliverySha256: 'a'.repeat(64) }, claims: { ...snapshotAnalysis.claims, findings: [], limitations: [], coverage: snapshotAnalysis.claims.coverage.map(c => ({ ...c, evidence: [], limitations: [] })) } } : snapshotAnalysis
  let bodyReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/snapshot-analysis') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/private-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) { bodyReads++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-sha', delivery: { summary: '已提交版本审查结果', outcome: null, outputs: { summary: { kind: 'TEXT', content: '静态分析结果' }, analysis: { kind: 'JSON', content: analysis } } } } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/snapshot-analysis')
  await selectWorkflowNode(page, node.title); expect(bodyReads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click(); const report = page.locator('.workflow-snapshot-report')
  if (review) { await expect(report).toContainText('候选问题独立复核'); await expect(report).toContainText('证据支持') }
  else if (reused) { await expect(report).toContainText('本次没有创建模型会话'); await expect(report.getByRole('link', { name: '上周固定版本审查' })).toHaveAttribute('href', '/requirements/private-source') }
  else { await expect(report).toContainText('第 2 / 3 批版本分析'); await expect(report).toContainText('归因未确定') }
  if (!reused) { await report.locator('summary').filter({ hasText: '第 24–25 行' }).first().click(); await expect(report).toContainText('validate(input)') }
  await expect(report).not.toContainText('private-'); expect(bodyReads).toBe(1)
  await page.screenshot({ path: `test-results/workflow-snapshot-analysis-${mode}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); await report.scrollIntoViewIfNeeded(); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-snapshot-analysis-${mode}-mobile.png`, fullPage: false })
})
