import { expect, test } from '@playwright/test'
import { execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import { newNode } from '../src/components/workflow/graph'
import type { WorkflowGraph } from '../src/types/workflow'
for (const width of [1600, 390]) test(`固定知识交付及后继输入 ${width}`, async ({ page }) => {
  const research = { ...newNode('free.readonly'), id: 'research', title: '查阅知识并交付证据', moduleId: 'knowledge.research', roleId: 'builtin.knowledge', task: '读取退款规则并交付实际证据。', outputs: [{ name: 'result', title: '检索结论', kind: 'TEXT' as const, required: true }, { name: 'evidence', title: '固定来源证据', kind: 'JSON' as const, required: true }] }
  const summary = { ...newNode('free.readonly'), id: 'summary', title: '整理资料形成说明', roleId: 'builtin.designer', task: '完整读取上游固定资料，整理说明。', inputs: [{ name: 'evidence', source: 'NODE' as const, sourceId: 'research', output: 'evidence', kind: 'JSON' as const, required: true }] }
  const graph: WorkflowGraph = { schemaVersion: 1, nodes: [research, summary], edges: [{ id: 'e', from: 'research', to: 'summary', outcome: null }], inputs: [] }
  const req = requirement({ id: 'knowledge-handoff', title: '知识检索与资料整理', state: 'COMPLETED', graph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = graph.nodes.map(node => ({ id: `run-${node.id}`, nodeKey: node.id, state: 'SUCCEEDED' as const, attemptCount: 1, latestAttemptId: `${node.id}-attempt`, version: 1, outcome: null }))
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: 'COMPLETED', reasonCode: null }
  const bundle = { version: 1, type: 'KNOWLEDGE_EVIDENCE', entries: [{ reference: 'call:private-receipt', toolName: 'read_knowledge_source', createdAt: '2026-09-29T02:00:00Z', sha256: 'private-content-hash', content: { kind: 'DOCUMENT', name: '退款申请规则.md', location: '退款申请规则.md · 第 1 段', startLine: 1, endLine: 2, sha256: 'a'.repeat(64), text: '# 退款规则\n退款申请需要核对订单状态。' } }], limitations: ['只核对当前项目资料，尚未查询历史提交。'] }
  const text = JSON.stringify(bundle), split = 180
  let results = 0, inputReads = 0, privateReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    const node = path.includes('/nodes/summary') ? summary : research
    const run = attempt({ id: `${node.id}-attempt`, state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, modelState: 'SUCCEEDED', roleName: node.id === 'research' ? '知识库助手' : '设计师' })
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/knowledge-handoff') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith(`/attempts/${node.id}-attempt`)) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) { results++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'fixed-delivery', delivery: { summary: '交付退款规则及原文依据', outputs: { result: { kind: 'TEXT', content: '依据保存的原文，退款前须核对订单状态。' }, evidence: { kind: 'JSON', content: bundle } } } } }) }
    if (path.endsWith('/inputs')) return route.fulfill({ json: { objective: req.objective, values: [{ name: 'evidence', kind: 'JSON', source: 'NODE', sourceId: 'research', outputName: 'evidence', attemptId: 'research-attempt', sha256: 'fixed-delivery', content: null, reference: { version: 1, contentSha256: 'fixed-body', sizeBytes: text.length } }] } })
    if (path.endsWith('/inputs/evidence/content')) {
      inputReads++; const offset = Number(url.searchParams.get('offset'))
      return route.fulfill({ json: { name: 'evidence', kind: 'JSON', sha256: 'fixed-delivery', offset, text: offset ? text.slice(split) : text.slice(0, split), nextOffset: offset ? null : split, totalLength: text.length } })
    }
    if (path.includes('/knowledge')) { privateReads++; return route.fulfill({ json: { items: [], nextCursor: null } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width, height: width === 390 ? 844 : 1000 }); await page.goto('/requirements/knowledge-handoff')
  await page.locator('.workflow-module-rail button').filter({ hasText: research.title }).click(); expect(results).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click()
  let evidence = page.getByRole('region', { name: '交付的来源证据' })
  await expect(evidence).toContainText('尚未查询历史提交'); await expect(evidence).not.toContainText('private-')
  await evidence.getByRole('button').click(); await expect(evidence).toContainText('退款申请需要核对订单状态')
  await page.locator('.workflow-module-rail button').filter({ hasText: summary.title }).click()
  await page.getByRole('button', { name: '固定输入', exact: true }).click(); expect(inputReads).toBe(0)
  await page.getByRole('button', { name: '查看固定版本正文' }).click(); await expect(page.getByRole('status').filter({ hasText: '正文尚未读完' })).toBeVisible()
  await expect(page.getByRole('region', { name: '交付的来源证据' })).toHaveCount(0)
  await page.getByRole('button', { name: '继续读取正文' }).click()
  evidence = page.getByRole('region', { name: '交付的来源证据' }); await expect(evidence).not.toContainText('private-')
  await evidence.getByRole('button').click(); await expect(evidence).toContainText('退款申请需要核对订单状态')
  await evidence.getByRole('button', { name: '阅读视图' }).click(); await expect(evidence.locator('.markdown-document')).toContainText('核对订单状态')
  expect(inputReads).toBe(2); expect(privateReads).toBe(0); expect(results).toBe(1)
  expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-knowledge-handoff-${width}.png`, fullPage: true })
})
