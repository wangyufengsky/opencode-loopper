import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowGraph, WorkflowNode, WorkflowInput } from '../src/types/workflow'
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
const flow = (JSON.parse(readFileSync('../src/main/resources/workflows/templates.json', 'utf8')) as { flows: { id: string; nodes: { id: string; presetId: string; inputs: WorkflowInput[] }[]; edges: WorkflowGraph['edges']; inputs: WorkflowGraph['inputs'] }[] }).flows.find(f => f.id === 'builtin.workflow.source-unit-test')!
const base: WorkflowGraph = { schemaVersion: 1, nodes: flow.nodes.map(n => ({ ...presets.find(p => p.id === n.presetId)!.node, id: n.id, inputs: n.inputs })), edges: flow.edges, inputs: flow.inputs }

for (const mode of ['passed', 'none', 'failed']) test(`完整单测汇总 ${mode}`, async ({ page }) => {
  const failed = mode === 'failed', none = mode === 'none'
  const graph: WorkflowGraph = none ? { ...base, nodes: base.nodes.filter(n => !['review', 'risk'].includes(n.id)).map(n => n.id === 'summary' ? { ...n, parameters: { ...n.parameters, reviewPolicy: 'NONE' }, inputs: n.inputs.filter(i => i.kind !== 'DECISION') } : n), edges: base.edges.filter(e => !['review', 'risk'].includes(e.from) && !['review', 'risk'].includes(e.to)) } : base
  const node = graph.nodes.find(n => n.id === 'summary')!
  const req = requirement({ id: 'test-summary', title: '源码单元测试', state: failed ? 'STALLED' : 'COMPLETED', graph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = graph.nodes.map(n => ({ id: `run-${n.id}`, nodeKey: n.id, state: n.id === 'summary' && failed ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: n.id === 'summary' ? 'summary-attempt' : null, version: 1, outcome: null }))
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: failed ? 'STALLED' : 'COMPLETED', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : null }
  const run = attempt({ id: 'summary-attempt', state: failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, modelState: null, roleName: null })
  const report = { version: 1, type: 'SOURCE_TEST_SUMMARY', complete: true, passed: !failed, testPassed: true, reviewSatisfied: !failed,
    sourceCount: 2, coveredSourceCount: 2, moduleCount: 1, batchCount: 1, reviewedBatchCount: none ? 0 : 1, reviewPolicy: none ? 'NONE' : 'DUAL', writerAttempt: 'private-writer',
    modules: [{ root: '.', passed: true, counts: { total: 3, passed: 2, failed: 0, skipped: 1 } }],
    batches: [{ moduleRoot: '.', scenarioCount: 2, reviewSatisfied: !failed, reviews: none ? [] : [{ attempt: 'private-review-1' }, { attempt: 'private-review-2' }] }],
  }
  let reads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/test-summary') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/summary-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) { reads++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery', delivery: { summary: '固定最终代码汇总', outcome: failed ? 'FAIL' : 'PASS', outputs: { report: { kind: 'JSON', content: report } } } } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/test-summary')
  await selectWorkflowNode(page, node.title); expect(reads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click(); expect(reads).toBe(1)
  const content = page.locator('.workflow-test-summary-report')
  await expect(content).toContainText(failed ? '单测汇总未通过' : '单测汇总通过'); await expect(content).toContainText('同一份最终代码'); await expect(content).not.toContainText('private-')
  await content.locator('summary').click(); await expect(content).toContainText(none ? '按自定义策略不要求复核' : failed ? '复核条件未满足' : '复核通过')
  await expect(content).toContainText('实际执行 2 项'); await expect(content).toContainText('跳过 1 项')
  await page.screenshot({ path: `test-results/workflow-test-summary-${mode}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-test-summary-${mode}-mobile.png`, fullPage: true })
})
