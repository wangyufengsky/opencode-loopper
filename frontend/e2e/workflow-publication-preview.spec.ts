import { expect, test } from '@playwright/test'
import { requirement, execution } from '../src/components/workflow/workflowRunTestFixtures'
import { newNode } from '../src/components/workflow/graph'
import type { WorkflowPublicationSource, WorkflowPublicationPreview } from '../src/types/domain'
test('从需求画布选择固定代码成果、查看真实失败状态和累计改动', async ({ page }) => {
  const node = { ...newNode('free.write'), id: 'work', title: '开发与修正' }
  const req = requirement({ id: 'publication-preview', title: '订单流程开发', state: 'COMPLETED', revision: 2, graph: { schemaVersion: 1, nodes: [node], edges: [], inputs: [] } })
  const snapshot = execution(req.state); snapshot.execution.id = req.id; snapshot.execution.revision = 2; snapshot.execution.nodes = [{ id: 'private-node', nodeKey: 'work', state: 'FAILED', attemptCount: 2, latestAttemptId: 'private-attempt', version: 4, outcome: null }]; snapshot.control = { ...snapshot.control, id: req.id, configured: true, state: 'DONE' }
  const source: WorkflowPublicationSource = { nodeKey: 'work', nodeTitle: '开发与修正', attemptId: 'private-attempt', ordinal: 2, attemptState: 'FAILED', outputName: 'code', outputTitle: '代码成果', createdAt: '2026-09-29T08:00:00Z', changedFiles: 3, totalFiles: 24 }
  const preview: WorkflowPublicationPreview = { requirementId: req.id, requirementVersion: req.version, planRevision: 2, requirementState: 'COMPLETED', source, workspaceKind: 'GIT', sourceBranch: 'feature/order-flow', reference: { version: 1, snapshotId: 'private-snapshot', sha256: 'a'.repeat(64) }, deliverySha256: 'b'.repeat(64), baseTree: 'private-base', resultTree: 'private-result', added: 1, modified: 1, deleted: 1, totalBytes: 15360, sha256: 'c'.repeat(64) }
  let lists = 0, previews = 0, changes = 0, writes = 0
  await page.route('http://127.0.0.1:41773/api/**', async route => {
    const url = new URL(route.request().url()), path = url.pathname
    if (route.request().method() !== 'GET') writes++
    if (path.endsWith('/events')) return route.fulfill({ contentType: 'text/event-stream', body: 'data: {"type":"connected"}\n\n' })
    if (path === `/api/workflows/requirements/${req.id}`) return route.fulfill({ json: req })
    if (path.endsWith('/execution')) return route.fulfill({ json: snapshot })
    if (path.endsWith('/finish')) return route.fulfill({ json: { requirementId: req.id, state: req.state, version: req.version, intent: null, pending: { attempts: 0, resources: 0 } } })
    if (path.endsWith('/publication')) return route.fulfill({ json: null })
    if (path.endsWith('/publication/sources')) { lists++; expect(url.searchParams.get('revision')).toBe('2'); return route.fulfill({ json: { items: [source], nextCursor: null } }) }
    if (path.endsWith('/publication/preview')) { previews++; expect(url.searchParams.get('attempt')).toBe(source.attemptId); return route.fulfill({ json: preview }) }
    if (path.endsWith('/changes')) { changes++; expect(path).toContain('/nodes/work/attempts/private-attempt/outputs/code/'); return route.fulfill({ json: { items: [{ path: 'src/订单处理/OrderService.java', kind: 'MODIFY', beforeBlob: 'before', afterBlob: 'after' }, { path: 'src/test/OrderServiceTest.java', kind: 'ADD', beforeBlob: null, afterBlob: 'after' }, { path: 'docs/旧版订单处理说明.md', kind: 'DELETE', beforeBlob: 'before', afterBlob: null }], nextCursor: null } }) }
    if (path === '/api/settings') return route.fulfill({ json: { runtime: {}, openCode: {}, limits: {}, retryWait: {}, publication: {} } })
    return route.fulfill({ json: [] })
  })
  await page.setViewportSize({ width: 1600, height: 1000 }); await page.goto(`/requirements/${req.id}`)
  const panel = page.getByRole('region', { name: '需求代码成果' }); expect(lists).toBe(0)
  await panel.getByRole('button', { name: '查看代码成果', exact: true }).click(); await expect(panel).toContainText('开发与修正 · 代码成果'); expect(previews).toBe(0)
  await panel.getByRole('button', { name: /开发与修正 · 代码成果/ }).click(); await expect(panel).toContainText('该节点执行失败'); expect(changes).toBe(0)
  await panel.getByRole('button', { name: '查看改动文件' }).click(); await expect(panel).toContainText('继承的上游代码'); await expect(panel).toContainText('旧版订单处理说明.md')
  await expect(panel.getByRole('link', { name: 'docs/旧版订单处理说明.md' })).toHaveCount(0); await expect(panel).not.toContainText('private-')
  expect([lists, previews, changes, writes]).toEqual([1, 1, 1, 0]); await page.screenshot({ path: 'test-results/workflow-publication-preview-desktop.png', fullPage: true })
  await page.setViewportSize({ width: 390, height: 844 }); await panel.scrollIntoViewIfNeeded(); expect(await page.locator('body').evaluate(el => el.scrollWidth <= innerWidth + 1)).toBe(true)
  await page.screenshot({ path: 'test-results/workflow-publication-preview-mobile.png', fullPage: true })
})
