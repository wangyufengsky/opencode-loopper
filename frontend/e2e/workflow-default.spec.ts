import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test, type Page } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { template } from '../src/components/workflow/workflowTestFixtures'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowGraph, WorkflowLayout, WorkflowNode } from '../src/types/workflow'

// Use the actual bundled composition, with explicit HTTP fixtures for browser-only evidence.
const presets = JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }
const flows = JSON.parse(readFileSync('../src/main/resources/workflows/templates.json', 'utf8')) as { flows: { id: string; title: string; description: string; nodes: { id: string; presetId: string; inputs: WorkflowNode['inputs']; title?: string; task?: string }[]; edges: WorkflowGraph['edges']; inputs: WorkflowGraph['inputs']; layout: WorkflowLayout }[] }
const bundled = flows.flows.find(flow => flow.id === 'builtin.workflow.development')!
const graph: WorkflowGraph = { schemaVersion: 1, nodes: bundled.nodes.map(value => {
  const source = presets.presets.find(preset => preset.id === value.presetId)!.node
  return { ...structuredClone(source), id: value.id, title: value.title || source.title, task: value.task || source.task, inputs: value.inputs, roleRevisionId: source.kind === 'WORK' ? 'fixture-role-version' : null }
}), edges: bundled.edges, inputs: bundled.inputs }
const flow = template({ id: bundled.id, title: bundled.title, description: bundled.description, builtin: true, revision: 1, headRevision: 1, graph, layout: { ...bundled.layout, zoom: .55 } })
async function fixture(page: Page, failed = false) {
  const req = requirement({ state: failed ? 'STALLED' : 'COMPLETED', graph, layout: flow.layout }), snapshot = execution(req.state)
  req.id = 'review-fixture'; req.title = '订单流程开发'; snapshot.execution.id = req.id
  snapshot.execution.nodes = graph.nodes.map(node => ({ id: `run-${node.id}`, nodeKey: node.id, state: node.id === 'acceptance' && failed ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: `attempt-${node.id}`, version: 2, outcome: node.id === 'acceptance' ? (failed ? 'FAIL' : 'PASS') : null }))
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, mode: 'CONTINUOUS', state: failed ? 'STALLED' : 'DONE', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : null }
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/templates/builtin.workflow.development') return route.fulfill({ json: flow })
    if (path === '/api/template-tasks/projects/entry-project') return route.fulfill({ json: { id: 'entry-project', name: '订单服务', createdAt: '2026-09-29T00:00:00Z' } })
    if (path === '/api/workflows/requirements/review-fixture') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: snapshot.execution.state, version: snapshot.execution.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    const key = path.split('/nodes/')[1]?.split('/')[0], node = graph.nodes.find(n => n.id === key)
    const run = attempt({ id: `attempt-${key}`, state: key === 'acceptance' && failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith(`/attempts/attempt-${key}`)) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'fixture-hash', delivery: { summary: failed ? '验收未通过，风险意见需要处理。' : '程序验证与同批双评审通过。', outcome: failed ? 'FAIL' : 'PASS', outputs: { report: { kind: 'JSON', content: {
      version: 1, type: 'DUAL_REVIEW', passed: !failed, verificationPassed: true, basisSha256: 'private-basis', reviews: [
        { perspective: 'REQUIREMENT', verdict: 'PASS', reason: '退款入口及余额恢复满足已确认设计。', attempt: 'private-attempt' },
        { perspective: 'RISK', verdict: failed ? 'BLOCKED' : 'PASS', reason: failed ? '并发重复退款路径缺少验证，请补充后重做。' : '重复退款与异常回滚路径已有固定代码和测试证据。', attempt: 'private-attempt' },
      ],
    } } } } } })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
}
test('内置默认流程显示八个模块，新需求读取默认流程版本', async ({ page }) => {
  await fixture(page); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/workflows/builtin.workflow.development')
  await expect(page.locator('.workflow-node')).toHaveCount(8); await expect(page.locator('.workflow-node').filter({ hasText: '需求独立评审' })).toHaveCount(1)
  await expect(page.locator('.workflow-node').filter({ hasText: '风险独立评审' })).toHaveCount(1)
  await page.getByRole('button', { name: '流程设置', exact: true }).click()
  await expect(page.getByRole('combobox', { name: '内容类型', exact: true })).toHaveValue('DOCUMENT')
  await expect(page.getByRole('combobox', { name: '内容类型', exact: true }).locator('option:checked')).toHaveText('上传文档')
  await expect(page.getByRole('combobox', { name: '内容类型', exact: true })).toBeDisabled()
  await page.screenshot({ path: 'test-results/workflow-default-canvas.png', fullPage: true })
  await page.goto('/designer?projectId=entry-project')
  await expect(page).toHaveURL(/\/requirements\/new\?/); await expect(page.getByRole('button', { name: '选择流程', exact: true })).toContainText('默认开发流程')
  await expect(page.getByRole('button', { name: '选择项目', exact: true })).toContainText('订单服务')
  await expect(page.getByRole('button', { name: '进入规划画布', exact: true })).toBeDisabled()
  await page.screenshot({ path: 'test-results/workflow-new-project-entry.png', fullPage: true })
})
for (const failed of [false, true]) test(`验收报告${failed ? '保留阻断意见' : '显示同批通过'}且窄屏可读`, async ({ page }) => {
  await fixture(page, failed); await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/review-fixture')
  await selectWorkflowNode(page, '双评审验收')
  await page.getByRole('button', { name: '交付物', exact: true }).click(); const report = page.locator('.workflow-review-report')
  await expect(report).toContainText(failed ? '验收未通过' : '验收通过'); await expect(report).toContainText('需求评审'); await expect(report).toContainText('风险评审'); await expect(report).not.toContainText('private-')
  if (failed) await expect(report).toContainText('并发重复退款路径缺少验证')
  await page.screenshot({ path: `test-results/workflow-review-${failed ? 'blocked' : 'passed'}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); await expect(report).toBeVisible(); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-review-${failed ? 'blocked' : 'passed'}-mobile.png`, fullPage: true })
})
