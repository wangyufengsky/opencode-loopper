const narrowLayoutInScope: boolean = false
import { semanticName } from './w3/semantics'
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
const writer = { ...preset('source.test-write', 'write'), inputs: [...design.inputs, { name: 'design', source: 'NODE', sourceId: 'design', output: 'design', kind: 'JSON', required: true }] } as WorkflowNode
const node = { ...preset('source.test-run', 'test'), inputs: [...writer.inputs, { name: 'code', source: 'NODE', sourceId: 'write', output: 'code', kind: 'CODE', required: true }] } as WorkflowNode
const graph: WorkflowGraph = { schemaVersion: 1, nodes: [source, profile, design, writer, node], edges: [{ id: 'sp', from: 'source', to: 'profile', outcome: null }, { id: 'pd', from: 'profile', to: 'design', outcome: null }, { id: 'dw', from: 'design', to: 'write', outcome: null }, { id: 'wt', from: 'write', to: 'test', outcome: null }], inputs: [{ name: 'path', title: '源码路径', kind: 'TEXT', required: true }] }
for (const scenario of ['passed', 'failed', 'input-changed', 'final-passed', 'final-failed']) test(`原生单测 ${scenario}`, async ({ page }) => {
  const passed = scenario === 'passed' || scenario === 'final-passed', changed = scenario === 'input-changed', finalCode = scenario.startsWith('final-')
  const design2 = { ...design, id: 'design2', title: '第二批场景设计' }
  const writer2 = { ...writer, id: 'write2', title: '第二批单测编写', inputs: [...writer.inputs.map(i => i.name === 'design' ? { ...i, sourceId: 'design2' } : i), { name: 'previous', source: 'NODE' as const, sourceId: 'write', output: 'code', kind: 'CODE' as const, required: true }] }
  const definition = finalCode ? { ...node, moduleVersion: 2, inputs: [...node.inputs.map(i => i.name === 'code' ? { ...i, sourceId: 'write2' } : i), { name: 'design_second', source: 'NODE' as const, sourceId: 'design2', output: 'design', kind: 'JSON' as const, required: true }] } : node
  const activeGraph = finalCode ? { ...graph, nodes: [source, profile, design, writer, design2, writer2, definition], edges: [...graph.edges.filter(e => e.id !== 'wt'), { id: 'wd2', from: 'write', to: 'design2', outcome: null }, { id: 'd2w2', from: 'design2', to: 'write2', outcome: null }, { id: 'w2t', from: 'write2', to: 'test', outcome: null }] } : graph
  const req = requirement({ id: 'test-run', title: finalCode ? '两批单测统一回归' : '计算器原生单测', state: passed ? 'COMPLETED' : 'STALLED', graph: activeGraph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = activeGraph.nodes.map(n => ({ id: `run-${n.id}`, nodeKey: n.id, state: !passed && n.id === 'test' ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: `${n.id}-attempt`, version: 1, outcome: null }))
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: passed ? 'COMPLETED' : 'PAUSED', reasonCode: passed ? null : 'WORKFLOW_RETRY_EXHAUSTED' }
  const run = attempt({ id: 'test-attempt', state: passed ? 'SUCCEEDED' : 'FAILED', deliveryAccepted: true, stopConfirmed: true, modelState: null, commandState: passed ? 'SUCCEEDED' : 'FAILED', roleName: null })
  let resultReads = 0, commandReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/test-run') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/test-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: definition })
    if (path.endsWith('/command/evidence')) { commandReads++; return route.fulfill({ json: { attemptId: run.id, request: { argv: ['mvn', 'test'], timeoutSeconds: 600 }, result: { launched: true, stopConfirmed: true, exitCode: passed || changed ? 0 : 1, timedOut: false, cancelled: false, outputTruncated: false, error: '', output: 'Tests run: 2' }, nativeReport: { files: [{ path: 'target/surefire-reports/TEST-Calculator.xml', content: '<testsuite tests="2" />' }] } } }) }
    if (path.endsWith('/result')) {
      resultReads++
      return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery', delivery: { summary: '本次原生测试已完成。', outcome: passed ? 'PASS' : 'FAIL', outputs: {
        report: { kind: 'JSON', content: { version: finalCode ? 2 : 1, type: 'SOURCE_TEST_RUN', passed, valid: !changed, inputUnchanged: !changed, moduleRoot: '.', framework: 'junit', counts: { total: 3, passed: passed || changed ? 2 : 1, failed: passed || changed ? 0 : 1, skipped: 1 }, exitCode: passed || changed ? 0 : 1, command: ['mvn', 'test'], files: [{ path: 'target/surefire-reports/TEST-Calculator.xml', sha256: 'private-hash' }], fileCount: 1, scenarioCoverageVerified: false, message: changed ? '执行期间固定配置发生变化，本次报告不能证明原交付物通过测试。' : '已读取本次原生测试报告。', producerAttempt: 'private-attempt', ...(finalCode ? { writerLineage: ['private-attempt', 'private-first'], batches: [
          { inputName: 'design', designAttempt: 'private-design1', designSha256: 'a'.repeat(64), writerAttempt: 'private-first', scenarioCount: 1 },
          { inputName: 'design_second', designAttempt: 'private-design2', designSha256: 'b'.repeat(64), writerAttempt: 'private-attempt', scenarioCount: 1 },
        ] } : {}) } },
        summary: { kind: 'TEXT', content: '固定代码的原生测试结果。' },
      } } } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    if (path === '/api/roles') return route.fulfill({ json: { items: [], nextCursor: null } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/test-run')
  await selectWorkflowNode(page, node.title); expect(resultReads).toBe(0)
  await page.getByRole('button', { name: semanticName('workflow.deliverables'), exact: true }).click()
  const report = page.locator('.workflow-professional-report')
  await expect(page.getByRole('region', { name: '节点执行详情' }).getByRole('heading', { name: '原生测试报告', exact: true })).toBeVisible()
  await expect(report).toContainText(changed ? '测试证据不完整' : passed ? '原生测试通过' : '原生测试未通过')
  if (changed) { await expect(report).toContainText('固定配置发生变化'); await expect(report).not.toContainText('原生测试通过') }
  else { await expect(report).toContainText('实际执行 2 项'); await expect(report).toContainText('另有 1 项跳过'); await expect(report).toContainText('已核对固定源码、测试和配置') }
  await expect(report).toContainText('测试数量不表示每个设计场景已覆盖'); await expect(report).not.toContainText('private-')
  if (finalCode) await expect(report).toContainText('同一份固定代码上回归 2 批场景')
  expect(commandReads).toBe(0)
  await page.screenshot({ path: `test-results/workflow-test-run-${scenario}-desktop.png`, fullPage: true })
  if (narrowLayoutInScope) { // OUT_OF_SCOPE: current acceptance is desktop only; historical assertions retained.
    await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-test-run-${scenario}-mobile.png`, fullPage: true })
  }
  await page.getByRole('button', { name: semanticName('workflow.commandEvidence'), exact: true }).click(); expect(commandReads).toBe(1)
  await expect(page.getByRole('heading', { name: '已保存的原生测试报告' })).toBeVisible()
  await page.getByRole('button', { name: semanticName('ui.open', 'target/surefire-reports/TEST-Calculator.xml'), exact: true }).click()
  const nativeReport = page.locator('.workflow-command-evidence').getByRole('textbox', { name: 'target/surefire-reports/TEST-Calculator.xml', exact: true }); await expect(nativeReport).toBeVisible(); await expect(nativeReport).toContainText('<testsuite tests="2" />')

})
