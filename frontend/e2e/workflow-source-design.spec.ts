import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'

const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
const references = [{ path: 'src/OrderService.java', sha256: 'private-source-hash', startLine: 1, endLine: 2, quote: 'class OrderService {\n  void submit() {}' }]
for (const mode of ['design', 'pass', 'revise']) test(`专业设计 ${mode} 按需读取交付和依据，窄屏可读`, async ({ page }) => {
  const review = mode !== 'design', failed = mode === 'revise'
  const node = { ...presets.find(value => value.id === (review ? 'source.design-review' : 'source.design'))!.node, id: 'design' }
  const req = requirement({ id: 'source-design-fixture', title: '订单详细设计', state: failed ? 'STALLED' : 'COMPLETED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'design-run', nodeKey: node.id, state: failed ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'design-attempt', version: 2, outcome: review ? failed ? 'REVISE' : 'PASS' : null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: failed ? 'STALLED' : 'DONE', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : null }
  const run = attempt({ id: 'design-attempt', state: failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, roleName: review ? '源码详细设计复核员' : '源码详细设计作者', roleRevisionNumber: 1, modelState: 'COMPLETED', modelVersion: 5 })
  const content = review ? { verdict: failed ? 'REVISE' : 'PASS', reason: failed ? '事务异常处理描述不完整' : '已独立核对源码和设计稿', checkedPaths: ['src/OrderService.java'], references, issues: failed ? [{ sectionKey: 'transaction', detail: '遗漏失败后的回滚行为', recommendation: '根据源码补充异常路径和回滚范围' }] : [] }
    : { title: '订单模块设计', summary: '说明订单提交的职责与事务边界', sections: [{ key: 'transaction', title: '事务与异常', markdown: '调用提交方法后记录业务结果。\n\n**需区分**正常路径与异常回滚。', paths: ['src/OrderService.java'], references }], limitations: ['外部服务的实现未纳入本批源码'] }
  let resultReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/source-design-fixture') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/design-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) { resultReads++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery-hash', delivery: { summary: '本批专业工作已交付', outcome: review ? failed ? 'REVISE' : 'PASS' : null, outputs: { [review ? 'review' : 'design']: { kind: review ? 'DECISION' : 'JSON', content } } } } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/source-design-fixture')
  await page.locator('.workflow-module-rail button').filter({ hasText: node.title }).click(); expect(resultReads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click()
  const report = page.locator('.workflow-source-design-report')
  await expect(report).toContainText(review ? failed ? '需要返修' : '复核通过' : '订单模块设计'); expect(resultReads).toBe(1)
  if (failed) { await expect(report).toContainText('根据源码补充异常路径和回滚范围'); await expect(report).not.toContainText('复核通过') }
  await expect(report).not.toContainText('private-source-hash')
  await report.getByText('源码依据（1 条）', { exact: true }).click(); await expect(report.locator('pre')).toContainText('class OrderService')
  await page.screenshot({ path: `test-results/workflow-source-design-${mode}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-source-design-${mode}-mobile.png`, fullPage: true })
})
