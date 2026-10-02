import { selectWorkflowNode } from './fixtures/workflowNavigation'
import { expect, test } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { createServer, request } from 'node:http'
import type { AddressInfo } from 'node:net'
import { requirement, execution, attempt } from '../src/components/workflow/workflowRunTestFixtures'
import type { WorkflowNode } from '../src/types/workflow'

const preset = (JSON.parse(readFileSync('../src/main/resources/workflows/node-presets.json', 'utf8')) as { presets: { id: string; node: WorkflowNode }[] }).presets.find(value => value.id === 'source.design-document')!.node
for (const mode of ['reviewed', 'unreviewed', 'incomplete']) test(`文档汇总 ${mode} 展示真实状态与固定下载`, async ({ page, context }) => {
  const failed = mode === 'incomplete', reviewed = mode === 'reviewed', node = { ...preset, id: 'document' }
  const req = requirement({ id: 'document-fixture', title: '源码详细设计交付', state: failed ? 'STALLED' : 'COMPLETED', graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id
  snapshot.execution.nodes = [{ id: 'document-run', nodeKey: node.id, state: failed ? 'FAILED' : 'SUCCEEDED', attemptCount: 1, latestAttemptId: 'document-attempt', version: 2, outcome: null }]
  snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: failed ? 'STALLED' : 'DONE', reasonCode: failed ? 'WORKFLOW_RETRY_EXHAUSTED' : null }
  const run = attempt({ id: 'document-attempt', state: failed ? 'FAILED' : 'SUCCEEDED', deliveryAccepted: true, stopConfirmed: true })
  let resultReads = 0, archiveReads = 0
  // Chromium download requests bypass Playwright routing. A loopback HTTP fixture
  // serves that endpoint; page assets still come from the owned Vite server.
  const server = createServer((incoming, outgoing) => {
    if (incoming.url?.endsWith('/outputs/document/archive')) {
      archiveReads++; outgoing.writeHead(200, { 'Content-Type': 'application/zip', 'Content-Disposition': 'attachment; filename="design.zip"' })
      outgoing.end(Buffer.from([80, 75, 3, 4])); return
    }
    const proxy = request({ hostname: '127.0.0.1', port: 41773, path: incoming.url, method: incoming.method, headers: incoming.headers }, response => {
      outgoing.writeHead(response.statusCode ?? 502, response.headers); response.pipe(outgoing)
    })
    proxy.on('error', () => { outgoing.writeHead(502); outgoing.end() }); incoming.pipe(proxy)
  })
  await new Promise<void>(resolve => server.listen(0, '127.0.0.1', resolve))
  const origin = `http://127.0.0.1:${(server.address() as AddressInfo).port}`
  try {
  await context.route(`${origin}/api/**`, async route => {
    const path = new URL(route.request().url()).pathname
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === '/api/workflows/requirements/document-fixture') return route.fulfill({ json: req })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/attempts')) return route.fulfill({ json: { items: [run], nextCursor: null } })
    if (path.endsWith('/attempts/document-attempt')) return route.fulfill({ json: run })
    if (path.endsWith('/definition')) return route.fulfill({ json: node })
    if (path.endsWith('/files')) return route.fulfill({ json: { items: ['coverage.md', 'module-1-main.md', 'overview.md'].map(path => ({ path, sizeBytes: 100, sha256: 'fixed-hash', mode: null })), nextCursor: null } })
    // Java integration verifies real ZIP entries and exact document bytes.
    if (path.endsWith('/archive')) return route.continue()
    if (path.endsWith('/result')) { resultReads++; return route.fulfill({ json: { attemptId: run.id, state: run.state, sha256: 'private-delivery', delivery: { summary: failed ? '汇总范围不完整' : '固定文档已生成', outcome: null, outputs: {
      ...(failed ? {} : { document: { kind: 'DOCUMENT', content: { version: 1, type: 'DESIGN_DOCUMENT', attemptId: 'private-attempt', sha256: 'private-hash' } } }),
      report: { kind: 'JSON', content: { version: 1, type: 'DESIGN_DOCUMENT', complete: !failed, code: failed ? 'SOURCE_COVERAGE_INCOMPLETE' : null, sourceCount: 2, draftCount: 2, reviewedCount: reviewed ? 2 : 0, reviseCount: 0, fileCount: 3, reviewPolicy: reviewed ? 'REQUIRED' : 'NONE' } },
    } } } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`${origin}/requirements/document-fixture`)
  await selectWorkflowNode(page, node.title); expect(resultReads).toBe(0)
  await page.getByRole('button', { name: '交付物', exact: true }).click()
  const report = page.locator('.workflow-document-report'); await expect(report).toContainText(failed ? '文档未生成' : '文档已生成'); expect(resultReads).toBe(1)
  if (failed) { await expect(report).toContainText('补齐遗漏批次'); await expect(page.getByRole('link', { name: '下载全部文档（ZIP）', exact: true })).toHaveCount(0) }
  else {
    await expect(report).toContainText(reviewed ? '通过 2 / 2' : '未要求全部复核通过')
    await page.getByRole('button', { name: '查看固定版本文件', exact: true }).click(); await expect(page.locator('.workflow-file-list li a')).toHaveCount(3)
    expect(archiveReads).toBe(0); const [download] = await Promise.all([page.waitForEvent('download'), page.getByRole('link', { name: '下载全部文档（ZIP）', exact: true }).click()])
    expect(download.suggestedFilename()).toBe('design.zip'); expect(await download.failure()).toBeNull(); expect(archiveReads).toBe(1)
  }
  await expect(page.getByRole('complementary', { name: '节点执行详情' })).not.toContainText('private-attempt')
  await page.screenshot({ path: `test-results/workflow-document-${mode}-desktop.png`, fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: `test-results/workflow-document-${mode}-mobile.png`, fullPage: true })
  } finally {
    server.closeAllConnections(); await new Promise<void>((resolve, reject) => server.close(error => error ? reject(error) : resolve()))
  }
})
