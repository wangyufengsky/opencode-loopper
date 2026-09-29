import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowGraph, WorkflowNode } from '../src/types/workflow'
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
const preset = (id: string, key: string): WorkflowNode => ({ ...presets.find(p => p.id === id)!.node, id: key })
const source = { ...preset('source.snapshot', 'source'), parameters: { sourcePurpose: 'UNIT_TEST' }, inputs: [{ name: 'path', source: 'REQUIREMENT', sourceId: 'path', output: null, kind: 'TEXT', required: true }] } as WorkflowNode
const profile = { ...preset('source.test-profile', 'profile'), inputs: [{ name: 'source', source: 'NODE', sourceId: 'source', output: 'source', kind: 'DOCUMENT', required: true }] } as WorkflowNode
const node = { ...preset('source.test-design', 'design'), inputs: [{ name: 'source', source: 'NODE', sourceId: 'source', output: 'source', kind: 'DOCUMENT', required: true }, { name: 'profile', source: 'NODE', sourceId: 'profile', output: 'profile', kind: 'JSON', required: true }] } as WorkflowNode
const graph: WorkflowGraph = { schemaVersion: 1, nodes: [source, profile, node], edges: [{ id: 'sp', from: 'source', to: 'profile', outcome: null }, { id: 'pd', from: 'profile', to: 'design', outcome: null }], inputs: [{ name: 'path', title: '源码路径', kind: 'TEXT', required: true }] }
for (const malformed of [false, true]) test(`单测场景${malformed ? '损坏格式明确提示' : '步骤期望和源码依据可查看'}`, async ({ page }) => {
  const req = requirement({ id: 'test-design', title: '计算器单测场景', state: 'COMPLETED', graph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = graph.nodes.map(n => ({ id: `run-${n.id}`, nodeKey: n.id, state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: `${n.id}-attempt`, version: 1, outcome: null }))
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: 'COMPLETED', reasonCode: null }
  const run = attempt({ id: 'design-attempt', state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, modelState: 'COMPLETED', roleName: '设计师' })
  let resultReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/test-design') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/design-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) {
      resultReads++
      return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery', delivery: { summary: '已设计场景，测试尚未执行。', outcome: null, outputs: { design: { kind: 'JSON', content: malformed ? {} : { version: 1, type: 'SOURCE_TEST_DESIGN', sourceAttemptId: 'private-source', profileAttemptId: 'private-profile', design: { title: '加法场景设计', summary: '覆盖正数求和与零值', scenarios: [{ key: 'sum', path: 'src/calculator.py', category: 'NORMAL', title: '两个正数求和', steps: ['调用 total(1, 2)'], expected: '返回 3', references: [{ path: 'src/calculator.py', startLine: 1, endLine: 2, quote: 'def total(a, b):\n    return a + b' }] }], limitations: ['边界值需人工确认'] } } } } } } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/test-design')
  await page.locator('.workflow-module-rail button').filter({ hasText: '设计单测场景' }).click(); expect(resultReads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click()
  const report = page.locator('.workflow-test-design-report')
  if (malformed) await expect(report.getByRole('alert')).toContainText('无法读取')
  else {
    await expect(report).toContainText('尚未执行测试'); await expect(report).toContainText('调用 total(1, 2)'); await expect(report).toContainText('返回 3')
    await expect(report.locator('pre')).not.toBeVisible(); await report.getByText('源码依据（1 条）', { exact: true }).click(); await expect(report.locator('pre')).toContainText('return a + b')
    await expect(report).not.toContainText('private-source'); await expect(report).not.toContainText('测试通过')
  }
  await page.screenshot({ path: `test-results/workflow-test-design-${malformed ? 'invalid' : 'ready'}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-test-design-${malformed ? 'invalid' : 'ready'}-mobile.png`, fullPage: true })
})
