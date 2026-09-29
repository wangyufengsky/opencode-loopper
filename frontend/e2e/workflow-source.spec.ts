import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'

const preset = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets.find(value => value.id === 'source.snapshot')!.node
for (const failed of [false, true]) test(`源码采集${failed ? '未完成可恢复' : '固定文件可读取'}且窄屏可读`, async ({ page }) => {
  const node: WorkflowNode = { ...preset, id: 'source', inputs: [{ name: 'path', source: 'REQUIREMENT', sourceId: 'path', output: null, kind: 'TEXT', required: true }] }
  const req = requirement({ id: 'source-fixture', title: '项目源码分析', state: failed ? 'STALLED' : 'COMPLETED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [{ name: 'path', title: '源码路径', kind: 'TEXT', required: true }] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'source-run', nodeKey: node.id, state: failed ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'source-attempt', version: 2, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: failed ? 'STALLED' : 'DONE', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : null }
  const run = attempt({ id: 'source-attempt', state: failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true })
  let fileReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/source-fixture') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/source-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/files')) { fileReads++; return route.fulfill({ json: { items: [
      { path: 'src/OrderService.java', sizeBytes: 2300, sha256: 'fixed-hash', mode: null, target: true, exclusion: null },
      { path: '.env', sizeBytes: 40, sha256: null, mode: null, target: false, exclusion: '受保护文件不提供读取' },
    ], nextCursor: null } }) }
    if (path.endsWith('/result')) return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'fixed-delivery', delivery: { summary: failed ? '目标源码未能完整读取。' : '源码已冻结。', outcome: null, outputs: {
      ...(failed ? {} : { source: { kind: 'DOCUMENT', content: { version: 1, snapshotId: 'private-source-id', sha256: 'private-hash' } } }),
      report: { kind: 'JSON', content: { version: 1, type: 'SOURCE_SNAPSHOT', complete: !failed, sourcePath: 'src', targetCount: 1, fileCount: 3, incompleteCount: failed ? 1 : 0, excludedCount: 1, code: failed ? 'WORKFLOW_SOURCE_INCOMPLETE' : null,
        exclusions: [{ path: failed ? 'src/Legacy.java' : '.env', reason: failed ? '无法解析为 UTF-8 文本' : '受保护文件不提供读取' }] } },
    } } } })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/source-fixture')
  await page.locator('.workflow-module-rail button').filter({ hasText: '冻结源码' }).click(); await page.getByRole('button', { name: '交付物', exact: true }).click()
  const report = page.locator('.workflow-source-report'); await expect(report).toContainText(failed ? '采集未完成' : '源码已冻结')
  expect(fileReads).toBe(0); await expect(page.getByRole('complementary', { name: '节点执行详情' })).not.toContainText('private-source-id')
  if (failed) { await expect(report).toContainText('未完整读取 1 项'); await expect(report).toContainText('新增采集节点'); await expect(report).toContainText('src/Legacy.java') }
  else { await page.getByRole('button', { name: '查看固定版本文件', exact: true }).click(); await expect(page.locator('.workflow-file-list a')).toHaveCount(1); await expect(page.locator('.workflow-file-list')).toContainText('未采集正文'); expect(fileReads).toBe(1) }
  await page.screenshot({ path: `test-results/workflow-source-${failed ? 'incomplete' : 'ready'}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-source-${failed ? 'incomplete' : 'ready'}-mobile.png`, fullPage: true })
})
