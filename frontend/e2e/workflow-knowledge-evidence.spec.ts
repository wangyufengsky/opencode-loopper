import { expect, test } from '@playwright/test'
import { execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import { newNode } from '../src/components/workflow/graph'
import type { WorkflowGraph } from '../src/types/workflow'
for (const width of [1600, 390]) test(`节点知识原文按需查看与恢复 ${width}`, async ({ page }) => {
  const node = { ...newNode('free.readonly'), id: 'research', title: '查阅退款规则', task: '读取项目资料，提供原文依据。', outputs: [{ name: 'result', title: '资料结论', kind: 'TEXT' as const, required: true }] }
  const graph: WorkflowGraph = { schemaVersion: 1, nodes: [node], edges: [], inputs: [] }
  const req = requirement({ id: 'knowledge-evidence', title: '退款流程资料核对', state: 'COMPLETED', graph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = [{ id: 'run-research', nodeKey: 'research', state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'research-attempt', version: 1, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: 'COMPLETED', reasonCode: null }
  const run = attempt({ id: 'research-attempt', state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, modelState: 'SUCCEEDED', roleName: '知识库助手' })
  let lists = 0, bodies = 0
  const entry = { id: 'private-entry', toolName: 'read_knowledge_source', createdAt: '2026-09-29T02:00:00Z' }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/knowledge-evidence') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/research-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/knowledge')) {
      lists++
      return route.fulfill({ json: { items: url.searchParams.get('cursor') ? [{ ...entry, id: 'next-entry', createdAt: '2026-09-29T01:00:00Z' }] : [entry], nextCursor: url.searchParams.get('cursor') ? null : 'next' } })
    }
    if (path.endsWith('/knowledge/private-entry')) {
      bodies++
      if (bodies === 1) return route.fulfill({ status: 503, json: { detail: '原文暂时无法读取，请重试。' } })
      return route.fulfill({ json: { ...entry, content: { kind: 'DOCUMENT', name: '业务规范/售后服务/退款申请与订单状态校验规则.md', location: '退款申请与订单状态校验规则.md · 第 1 段', sha256: 'a'.repeat(64), startLine: 1, endLine: 3, text: '# 退款规则\n退款申请需要核对订单状态。\n已关闭的订单应返回明确原因。', collectedAt: entry.createdAt } } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 }); await page.goto('/requirements/knowledge-evidence')
  await page.locator('.workflow-module-rail button').filter({ hasText: node.title }).click()
  expect(lists).toBe(0); expect(bodies).toBe(0)
  await page.getByRole('button', { name: '检索证据', exact: true }).click()
  const evidence = page.getByRole('region', { name: '本次检索证据' }); await expect(evidence.locator('li')).toHaveCount(1)
  expect(bodies).toBe(0); await expect(evidence).not.toContainText('private-entry')
  await evidence.locator('li button').click(); await evidence.getByRole('button', { name: '重试读取正文' }).click()
  await expect(evidence).toContainText('退款申请需要核对订单状态'); expect(bodies).toBe(2)
  await evidence.locator('li button').click(); expect(bodies).toBe(2)
  await evidence.getByRole('button', { name: '更多证据' }).click(); await expect(evidence.locator('li')).toHaveCount(2)
  expect(lists).toBe(2); await expect(evidence).toContainText('退款申请需要核对订单状态')
  await evidence.getByRole('button', { name: '阅读视图' }).click(); await expect(evidence.locator('.markdown-document')).toContainText('已关闭的订单应返回明确原因')
  expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-knowledge-evidence-${width}.png`, fullPage: true })
})
