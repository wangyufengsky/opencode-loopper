import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { execution, requirement, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowGraph, WorkflowNode } from '../src/types/workflow'

const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
const node: WorkflowNode = { ...presets.find(p => p.id === 'source.test-profile')!.node, id: 'profile', inputs: [{ name: 'source', source: 'NODE', sourceId: 'source', output: 'source', kind: 'DOCUMENT', required: true }] }
const source: WorkflowNode = { ...presets.find(p => p.id === 'source.snapshot')!.node, id: 'source', parameters: { sourcePurpose: 'UNIT_TEST' }, inputs: [{ name: 'path', source: 'REQUIREMENT', sourceId: 'path', output: null, kind: 'TEXT', required: true }] }
const graph: WorkflowGraph = { schemaVersion: 1, nodes: [source, node], edges: [{ id: 'source-profile', from: 'source', to: 'profile', outcome: null }], inputs: [{ name: 'path', title: '源码路径', kind: 'TEXT', required: true }] }
for (const failed of [false, true]) test(`测试配置${failed ? '缺失原因' : '固定范围和命令'}按需查看且不冒充测试通过`, async ({ page }) => {
  const req = requirement({ id: 'test-profile', title: '识别项目测试配置', state: failed ? 'STALLED' : 'COMPLETED', graph, revision: 1, headRevision: 1 })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 1
  snapshot.execution.nodes = graph.nodes.map(n => ({ id: `run-${n.id}`, nodeKey: n.id, state: n.id === 'profile' && failed ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: n.id === 'profile' ? 'profile-attempt' : 'source-attempt', version: 1, outcome: null }))
  snapshot.control = { ...snapshot.control, id: req.id, revision: 1, configured: true, mode: 'CONTINUOUS', state: failed ? 'STALLED' : 'COMPLETED', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : null }
  const run = attempt({ id: 'profile-attempt', state: failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, modelState: null, roleName: null })
  let resultReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/test-profile') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/candidates')) return route.fulfill({ json: { items: [], nextCursor: null } })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/profile-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/result')) {
      resultReads++
      return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery', delivery: { summary: failed ? '测试配置未确定' : '配置已识别，测试尚未执行。', outcome: null, outputs: {
        ...(failed ? {} : { profile: { kind: 'JSON', content: { version: 1, type: 'SOURCE_TEST_PROFILE', sourceAttemptId: 'private-source-attempt', source: { snapshotId: 'private-snapshot' }, profile: { manifestSha256: 'a'.repeat(64), modules: [{ root: 'billing', framework: 'junit', sourcePaths: ['billing/src/main/java/BillingService.java'], testRoots: ['billing/src/test/java'], fixtureRoots: ['billing/src/test/resources'], command: ['mvn', '-f', 'pom.xml', '-pl', 'billing', '-am', 'test'] }] } } } }),
        report: { kind: 'JSON', content: { version: 1, type: 'SOURCE_TEST_PROFILE', complete: !failed, moduleCount: 1, sourceCount: 1, code: failed ? 'SOURCE_TEST_CONFIGURATION_REQUIRED' : null, message: failed ? 'billing/pom.xml：没有可证实的 JUnit 或 TestNG 配置，请确认项目测试入口后重新采集。' : null } },
      } } } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/test-profile')
  await page.locator('.workflow-module-rail button').filter({ hasText: '识别测试配置' }).click(); expect(resultReads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click()
  const detail = page.getByRole('complementary', { name: '节点执行详情' })
  await expect(detail).toContainText(failed ? '配置未确定' : '测试尚未执行'); await expect(detail.locator('.workflow-test-profile-report').filter({ hasText: '测试通过' })).toHaveCount(0)
  if (failed) await expect(detail).toContainText('billing/pom.xml')
  else {
    await expect(detail).toContainText('billing/src/test/java'); await expect(detail.locator('.workflow-test-command code')).toHaveText(['mvn', '-f', 'pom.xml', '-pl', 'billing', '-am', 'test'])
    await detail.getByText('1 个目标源码文件', { exact: true }).click(); await expect(detail.getByText('billing/src/main/java/BillingService.java', { exact: true })).toBeVisible()
  }
  await expect(detail).not.toContainText('private-source-attempt'); await expect(detail).not.toContainText('system.source.test-profile')
  await page.screenshot({ path: `test-results/workflow-test-profile-${failed ? 'failed' : 'ready'}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-test-profile-${failed ? 'failed' : 'ready'}-mobile.png`, fullPage: true })
})
