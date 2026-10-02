import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
const source = { version: 1, type: 'GIT_HISTORY', snapshotId: 'private-source', sha256: 'a'.repeat(64) }
const assessment = { level: 2, reason: '变更提供了实现及相关验证依据', evidenceIds: ['private-unit'] }
for (const contribution of [false, true]) test(`历史${contribution ? '贡献评价' : '代码审查'}交付按需展开且窄屏可读`, async ({ page }) => {
  const node: WorkflowNode = { ...presets.find(p => p.id === (contribution ? 'history.contribution' : 'history.review'))!.node, id: 'analysis' }
  const req = requirement({ id: 'history-analysis', title: '历史代码分析', state: 'COMPLETED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'private-node', nodeKey: node.id, state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'private-attempt', version: 3, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const run = attempt({ id: 'private-attempt', state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, roleName: '模板分析角色', roleRevisionNumber: 1, modelState: 'SUCCEEDED', modelVersion: 3 })
  const analysis = contribution ? { version: 1, type: 'HISTORY_CONTRIBUTION', source, reviewAttempts: ['private-review'],
    person: { author: { name: '开发者', email: 'developer@example.test', identity: 'private-person', robot: false }, commits: ['a'], evidenceIds: ['private-unit'], rawLines: 80, effectiveLines: 60 },
    assessment: { identity: 'private-person', summary: '完成输入校验和失败处理', value: assessment, difficulty: assessment, quality: assessment, maintenance: assessment } }
    : { version: 1, type: 'HISTORY_REVIEW', source, batchOrdinal: 1, batchCount: 3, locations: [{ unitId: 'private-unit', commitSha: 'b'.repeat(40), path: 'src/main/java/example/Validation.java' }],
      reviews: [{ unitId: 'private-unit', summary: '检查了输入校验和异常处理', findings: [{ severity: 'HIGH', side: 'AFTER', line: 24, title: '空输入未被处理', detail: '空值进入后续调用时会抛出异常。', recommendation: '明确校验空值，并覆盖相关场景。' }], limitations: ['静态审查，未运行测试。'] }] }
  let bodyReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/history-analysis') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/private-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) { bodyReads++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-sha', delivery: { summary: '已提交历史分析结果', outcome: null, outputs: { summary: { kind: 'TEXT', content: '静态分析结果' }, analysis: { kind: 'JSON', content: analysis } } } } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/history-analysis')
  await selectWorkflowNode(page, node.title); expect(bodyReads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click(); const report = page.locator('.workflow-history-analysis-report')
  if (contribution) { await expect(report).toContainText('开发者 · 个人贡献评价'); await expect(report).toContainText('质量与验证证据 · 2 / 4') }
  else { await expect(report).toContainText('第 2 / 3 批历史审查'); await report.locator('summary').click(); await expect(report).toContainText('变更后第 24 行'); await expect(report).toContainText('静态审查，未运行测试') }
  await expect(report).not.toContainText('private-'); expect(bodyReads).toBe(1)
  await page.screenshot({ path: `test-results/workflow-history-analysis-${contribution ? 'contribution' : 'review'}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); await report.scrollIntoViewIfNeeded(); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-history-analysis-${contribution ? 'contribution' : 'review'}-mobile.png`, fullPage: false })
})
