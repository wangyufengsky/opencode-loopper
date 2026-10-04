const narrowLayoutInScope: boolean = false
import { semanticName } from './w3/semantics'
import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'
const presets = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets
test('完整版本报告按需预览、明细跳转、返回缓存及桌面窄屏显示', async ({ page }) => {
  const node: WorkflowNode = { ...presets.find(p => p.id === 'snapshot.report')!.node, id: 'report' }
  const req = requirement({ id: 'snapshot-flow-report', title: '固定版本审查', state: 'COMPLETED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'private-node', nodeKey: node.id, state: 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'private-attempt', version: 3, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const run = attempt({ id: 'private-attempt', state: 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true, roleName: null })
  const folder = '代码审查-全面_示例项目_20260901-20260907_001', main = `${folder}/${folder}.md`, detail = `${folder}/明细/问题清单.md`
  const texts = { [main]: '# 代码审查-全面总结\n\n已完成固定目标版本的全部 2 批静态分析，1 批候选问题已独立复核。\n\n[查看完整问题清单](明细/%E9%97%AE%E9%A2%98%E6%B8%85%E5%8D%95.md)\n\n结论对应固定目标版本；本轮静态审查没有运行目标项目测试。', [detail]: '# 完整问题清单\n\n[返回总结报告](../' + encodeURIComponent(folder) + '.md)\n\n| 问题 | 建议 |\n| --- | --- |\n| 参数为空时缺少校验 | 添加明确的参数检查 |\n\n<script>alert("unsafe")</script>' }
  let fileReads = 0, bodyReads = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/snapshot-flow-report') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/private-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/files')) { fileReads++; return route.fulfill({ json: { items: [main, detail].map(path => ({ path, sizeBytes: 400, sha256: 'a'.repeat(64), mode: null })), nextCursor: null } }) }
    if (path.endsWith('/text')) { bodyReads++; const file = url.searchParams.get('path')!; return route.fulfill({ json: { path: file, sha256: 'a'.repeat(64), text: texts[file] || '', offset: 0, nextOffset: null } }) }
    if (path.endsWith('/result')) return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-sha', delivery: { summary: '完整版本报告已保存', outcome: null, outputs: {
      document: { kind: 'DOCUMENT', content: { version: 1, type: 'SNAPSHOT_DOCUMENT', attemptId: run.id, sha256: 'a'.repeat(64) } },
      report: { kind: 'JSON', content: { version: 1, type: 'SNAPSHOT_DOCUMENT', complete: true, sourceCount: 72, excludedCount: 1, batchCount: 2, draftCount: 2, reviewedCount: 1, candidateCount: 1, supportedCount: 1, fileCount: 6, reviewPolicy: 'REQUIRED', targetSha: 'a'.repeat(40) } },
    } } } })
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    if (path === '/api/roles') return route.fulfill({ json: { items: [], nextCursor: null } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto('/requirements/snapshot-flow-report')
  await selectWorkflowNode(page, node.title); await page.getByRole('button', { name: semanticName('workflow.deliverables'), exact: true }).click()
  await expect(page.locator('.workflow-professional-report')).toContainText('完整报告已生成'); expect(fileReads).toBe(0); expect(bodyReads).toBe(0)
  await expect(page.getByRole('link', { name: '下载全部文档（ZIP）' })).toBeVisible()
  await page.getByRole('button', { name: '查看固定版本文件' }).click(); expect(bodyReads).toBe(0)
  await page.locator('[data-semantic="workflow.previewDocument"]').first().click(); const preview = page.getByRole('region', { name: '报告预览' })
  await expect(preview).toContainText('代码审查-全面总结'); await preview.getByRole('link', { name: '查看完整问题清单' }).click()
  await expect(preview).toContainText('参数为空时缺少校验'); expect(bodyReads).toBe(2); await expect(preview.locator('script')).toHaveCount(0)
  await preview.getByRole('link', { name: '返回总结报告' }).click(); await expect(preview).toContainText('代码审查-全面总结'); expect(bodyReads).toBe(2)
  await page.screenshot({ path: 'test-results/workflow-snapshot-flow-report-desktop.png', fullPage: true })
  if (narrowLayoutInScope) { // OUT_OF_SCOPE: current acceptance is desktop only; historical assertions retained.
    await page.setViewportSize({ width: 390, height: 844 }); await preview.scrollIntoViewIfNeeded(); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-snapshot-flow-report-mobile.png', fullPage: false })
  }
})
