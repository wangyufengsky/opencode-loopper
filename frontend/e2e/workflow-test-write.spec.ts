import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowGraph, WorkflowNode } from '../src/types/workflow'
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
const preset = (id: string, key: string): WorkflowNode => ({ ...presets.find(p => p.id === id)!.node, id: key })
const source = { ...preset('source.snapshot', 'source'), parameters: { sourcePurpose: 'UNIT_TEST' }, inputs: [{ name: 'path', source: 'REQUIREMENT', sourceId: 'path', output: null, kind: 'TEXT', required: true }] } as WorkflowNode
const profile = { ...preset('source.test-profile', 'profile'), inputs: [{ name: 'source', source: 'NODE', sourceId: 'source', output: 'source', kind: 'DOCUMENT', required: true }] } as WorkflowNode
const design = { ...preset('source.test-design', 'design'), inputs: [{ name: 'source', source: 'NODE', sourceId: 'source', output: 'source', kind: 'DOCUMENT', required: true }, { name: 'profile', source: 'NODE', sourceId: 'profile', output: 'profile', kind: 'JSON', required: true }] } as WorkflowNode
const node = { ...preset('source.test-write', 'write'), inputs: [...design.inputs, { name: 'design', source: 'NODE', sourceId: 'design', output: 'design', kind: 'JSON', required: true }] } as WorkflowNode
const graph: WorkflowGraph = { schemaVersion: 1, nodes: [source, profile, design, node], edges: [{ id: 'sp', from: 'source', to: 'profile', outcome: null }, { id: 'pd', from: 'profile', to: 'design', outcome: null }, { id: 'dw', from: 'design', to: 'write', outcome: null }], inputs: [{ name: 'path', title: '源码路径', kind: 'TEXT', required: true }] }
for (const passed of [true, false]) test(`单测编写${passed ? '范围通过与测试结果分开' : '失败原因和固定成果仍可查看'}`, async ({ page }) => {
  const req = requirement({ id: 'test-write', title: '计算器单测编写', state: passed ? 'COMPLETED' : 'STALLED', graph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = graph.nodes.map(n => ({ id: `run-${n.id}`, nodeKey: n.id, state: !passed && n.id === 'write' ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: `${n.id}-attempt`, version: 1, outcome: null }))
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: passed ? 'COMPLETED' : 'PAUSED', reasonCode: passed ? null : 'WORKFLOW_RETRY_EXHAUSTED' }
  const run = attempt({ id: 'write-attempt', state: passed ? 'SUCCEEDED' : 'FAILED', deliveryAccepted: true, stopConfirmed: true, modelState: passed ? 'SUCCEEDED' : 'FAILED', roleName: '开发工程师' })
  let resultReads = 0, fileReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/test-write') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/write-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/outputs/code/files')) { fileReads++; return route.fulfill({ json: { items: [{ path: 'tests/test_calculator.py', sizeBytes: 120, sha256: 'fixed-hash', mode: '100644' }], nextCursor: null } }) }
    if (path.endsWith('/result')) {
      resultReads++
      return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery', delivery: { summary: '测试文件已保存，实际测试由后续验证节点执行。', outcome: null, outputs: {
        code: { kind: 'CODE', content: { version: 1, snapshotId: 'private-code', sha256: 'private-hash' } },
        scope: { kind: 'JSON', content: { version: 1, type: 'SOURCE_TEST_SCOPE', passed, testsExecuted: false, message: passed ? '测试文件范围检查通过。' : 'tests/test_existing.py：已有测试正文或断言被移除或改写，请保留已有测试。' } },
        summary: { kind: 'TEXT', content: '补充整数求和场景。' },
      } } } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/test-write')
  await selectWorkflowNode(page, node.title); expect(resultReads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click()
  const report = page.locator('.workflow-test-scope-report')
  await expect(report).toContainText(passed ? '范围检查通过' : '范围检查未通过'); await expect(report).toContainText('实际测试结果请查看后续验证节点')
  await expect(report).not.toContainText('测试通过'); await expect(report).not.toContainText('private-')
  if (!passed) { await expect(report).toContainText('已有测试正文或断言被移除或改写'); await expect(report).toContainText('失败成果不能直接用于后续执行') }
  expect(fileReads).toBe(0); await page.getByRole('button', { name: '查看固定版本文件' }).click()
  await expect(page.getByRole('link', { name: 'tests/test_calculator.py' })).toHaveAttribute('href', /outputs\/code\/file\?path=tests%2Ftest_calculator.py/)
  await page.screenshot({ path: `test-results/workflow-test-write-${passed ? 'passed' : 'failed'}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-test-write-${passed ? 'passed' : 'failed'}-mobile.png`, fullPage: true })
})
