import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
test('取消后按需读取阶段报告、刷新下载及桌面窄屏', async ({ page }) => {
  const node: WorkflowNode = { ...presets.find(p => p.id === 'review.snapshot-full')!.node, id: 'source' }
  const req = requirement({ id: 'snapshot-partial', revision: 4, title: '查看阶段审查结果', state: 'CANCELLED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'private-node', nodeKey: node.id, state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'private-attempt', version: 3, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const run = attempt({ id: 'private-attempt', state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, roleName: null, modelState: null, commandState: 'COMPLETED' })
  let reads = 0, writes = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const path = new URL(route.request().url()).pathname
    if (route.request().method() !== 'GET') writes++
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/snapshot-partial') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/private-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/snapshot-partial-report')) {
      reads++
      return route.fulfill({ json: { content: '# 代码审查阶段报告（非完整报告）\n\n任务状态：已取消\n\n## 覆盖清单\n\n| 文件 | 状态 |\n| --- | --- |\n| src/Order.java | 已分析 |\n| src/Payment.java | 尚未完成分析 |\n\n## 已保存问题与证据\n\n### 空订单缺少检查\n\n尚未独立复核（仅候选）。\n\n测试状态：本轮未执行目标项目测试。\n\n<script>alert("unsafe")</script>\n\n![外部图片](https://example.invalid/track.png)', sha256: 'a'.repeat(64), capturedAt: '2026-09-29T07:00:00Z', planRevision: 4, analyzedUnits: reads === 1 ? 1 : 2, pendingUnits: reads === 1 ? 3 : 2, excludedUnits: 1 } })
    }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/snapshot-partial')
  await page.locator('.workflow-module-rail button').filter({ hasText: node.title }).click()
  await page.getByRole('button', { name: '阶段报告', exact: true }).click(); const report = page.getByRole('region', { name: '版本审查阶段报告' })
  expect(reads).toBe(0); await report.getByRole('button', { name: '查看阶段报告' }).click()
  await expect(report).toContainText('已分析 1 · 未完成 3 · 排除 1'); await expect(report).toContainText('尚未独立复核（仅候选）')
  await expect(report.locator('script, img')).toHaveCount(0); expect(reads).toBe(1)
  await report.getByRole('button', { name: '刷新阶段报告' }).click(); await expect(report).toContainText('已分析 2 · 未完成 2 · 排除 1'); expect(reads).toBe(2)
  const download = page.waitForEvent('download'); await report.getByRole('button', { name: '下载阶段报告' }).click(); expect((await download).suggestedFilename()).toBe('代码审查阶段报告.md')
  expect(writes).toBe(0); await page.screenshot({ path: 'test-results/workflow-snapshot-partial-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); await report.scrollIntoViewIfNeeded(); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-snapshot-partial-mobile.png', fullPage: false })
})
